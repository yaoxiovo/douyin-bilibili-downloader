package com.yaoxiovo.downloader.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yaoxiovo.downloader.model.MediaInfo;
import com.yaoxiovo.downloader.util.HttpUtil;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance Bilibili video & multi-part parser.
 * Ports HTML5 direct stream probing and b23.tv redirection resolution from core.js.
 */
public class BilibiliResolver {

    public static final String BILI_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    public static final String BILI_REFERER = "https://www.bilibili.com/";

    private static final Pattern PATTERN_INPUT_CHECK =
            Pattern.compile("bilibili\\.com|b23\\.tv|^(av\\d{5,15})$", Pattern.CASE_INSENSITIVE);

    private static final Pattern PATTERN_BV = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern PATTERN_AV = Pattern.compile("[&/?]av(\\d{5,15})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_DIRECT_AV = Pattern.compile("^(av\\d{5,15})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_PAGE = Pattern.compile("[?&]p=(\\d+)");
    private static final Pattern PATTERN_URL = Pattern.compile("https?://[^\\s]*", Pattern.CASE_INSENSITIVE);

    public static boolean isBiliInput(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }
        String trimmed = input.trim();
        return PATTERN_INPUT_CHECK.matcher(trimmed).find() || PATTERN_BV.matcher(trimmed).find();
    }

    public static class BiliParams {
        public String bvid;
        public String aid;
        public int page = 1;
    }

    /**
     * Resolves short links (b23.tv), av numbers, and query parameters to standard BV/aid and page index.
     */
    public static BiliParams normalize(String input) throws IOException {
        String trimmed = input.trim();
        BiliParams params = new BiliParams();

        Matcher directAvMatcher = PATTERN_DIRECT_AV.matcher(trimmed);
        if (directAvMatcher.find()) {
            params.aid = directAvMatcher.group(1).substring(2);
            params.page = 1;
            return params;
        }

        Matcher urlMatcher = PATTERN_URL.matcher(trimmed);
        String url = urlMatcher.find() ? urlMatcher.group(0).replaceAll("[)），，。]+$", "") : null;

        if (url != null && url.contains("b23.tv")) {
            OkHttpClient manualClient = HttpUtil.getManualRedirectClient();
            for (int hop = 0; hop < 4; hop++) {
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", BILI_UA)
                        .header("Referer", BILI_REFERER)
                        .get()
                        .build();

                try (Response response = manualClient.newCall(request).execute()) {
                    String loc = response.header("Location");
                    int code = response.code();
                    if (loc != null && (code >= 300 && code < 400)) {
                        URL base = new URL(url);
                        url = new URL(base, loc).toString();
                        if (!url.contains("b23.tv")) {
                            break;
                        }
                        continue;
                    }

                    String bodyText = "";
                    if (response.body() != null) {
                        bodyText = response.body().string();
                        if (bodyText.length() > 65536) {
                            bodyText = bodyText.substring(0, 65536);
                        }
                    }

                    Matcher bvInPage = PATTERN_BV.matcher(bodyText);
                    if (bvInPage.find()) {
                        url = "https://www.bilibili.com/video/" + bvInPage.group(0);
                    } else if (bodyText.contains("\"code\":-404") || bodyText.contains("\"code\": -404")) {
                        throw new IOException("b23.tv 短链已失效（\"啥都木有\"），请重新分享获取新链接");
                    }
                    break;
                }
            }
        }

        String src = (url != null) ? url : trimmed;

        Matcher bvMatcher = PATTERN_BV.matcher(src);
        Matcher avMatcher = PATTERN_AV.matcher(src);
        Matcher pageMatcher = PATTERN_PAGE.matcher(src);

        if (pageMatcher.find()) {
            try {
                params.page = Integer.parseInt(pageMatcher.group(1));
            } catch (NumberFormatException ignored) {
                params.page = 1;
            }
        }

        if (bvMatcher.find()) {
            params.bvid = bvMatcher.group(0);
            return params;
        }

        if (avMatcher.find()) {
            params.aid = avMatcher.group(1);
            return params;
        }

        throw new IOException("未能识别 B站链接：请提供 BV号 / av号 / b23.tv 短链 / 完整网址");
    }

    /**
     * Fetches video detail metadata from api.bilibili.com/x/web-interface/view.
     */
    public static JsonObject fetchView(String bvid, String aid) throws IOException {
        String query = (bvid != null && !bvid.isEmpty()) ? "bvid=" + bvid : "aid=" + aid;
        String viewUrl = "https://api.bilibili.com/x/web-interface/view?" + query;

        Request request = new Request.Builder()
                .url(viewUrl)
                .header("User-Agent", BILI_UA)
                .header("Referer", BILI_REFERER)
                .get()
                .build();

        try (Response response = HttpUtil.getClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("B站 view 接口网络错误: HTTP " + response.code());
            }

            String bodyString = response.body().string();
            JsonObject root = JsonParser.parseString(bodyString).getAsJsonObject();
            int code = root.has("code") ? root.get("code").getAsInt() : -1;
            if (code != 0) {
                String msg = root.has("message") ? root.get("message").getAsString() : String.valueOf(code);
                throw new IOException("B站 view 接口返回错误: " + msg);
            }

            return root.getAsJsonObject("data");
        }
    }

    /**
     * Resolves playurl for specific bvid and cid using html5 platform parameter.
     */
    public static MediaInfo.StreamInfo fetchPlayUrl(String bvid, String cid) throws IOException {
        String playUrl = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid +
                "&cid=" + cid + "&qn=80&fnval=1&platform=html5&high_quality=1";

        Request request = new Request.Builder()
                .url(playUrl)
                .header("User-Agent", BILI_UA)
                .header("Referer", BILI_REFERER)
                .get()
                .build();

        try (Response response = HttpUtil.getClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("B站 playurl 接口网络错误: HTTP " + response.code());
            }

            String bodyString = response.body().string();
            JsonObject root = JsonParser.parseString(bodyString).getAsJsonObject();
            int code = root.has("code") ? root.get("code").getAsInt() : -1;
            if (code != 0) {
                String msg = root.has("message") ? root.get("message").getAsString() : String.valueOf(code);
                throw new IOException("B站 playurl 接口返回错误: " + msg);
            }

            JsonObject data = root.getAsJsonObject("data");
            JsonArray durl = data.has("durl") && data.get("durl").isJsonArray()
                    ? data.getAsJsonArray("durl")
                    : new JsonArray();

            if (durl.size() == 0) {
                throw new IOException("B站未返回可下载地址（免登录上限 720p，部分番剧/课程不开放）");
            }

            JsonObject seg = durl.get(0).getAsJsonObject();
            String streamUrl = seg.get("url").getAsString();
            long totalBytes = seg.has("size") ? seg.get("size").getAsLong() : 0L;

            int rawQuality = data.has("quality") ? data.get("quality").getAsInt() : 0;
            String qualityStr = rawQuality >= 64 ? "720p" : (rawQuality > 0 ? String.valueOf(rawQuality) : "default");

            MediaInfo.StreamInfo streamInfo = new MediaInfo.StreamInfo();
            streamInfo.setUrl(streamUrl);
            streamInfo.setTotalBytes(totalBytes);
            streamInfo.setQuality(qualityStr);
            streamInfo.setMultiSegment(durl.size() > 1 ? durl.size() : 0);

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", BILI_UA);
            headers.put("Referer", BILI_REFERER);
            streamInfo.setHeaders(headers);

            return streamInfo;
        }
    }

    /**
     * Resolves complete Bilibili video information and direct stream URL.
     */
    public static MediaInfo resolve(String input) throws IOException {
        BiliParams params = normalize(input);
        JsonObject viewData = fetchView(params.bvid, params.aid);

        String bvid = viewData.has("bvid") ? viewData.get("bvid").getAsString() : params.bvid;
        String rawTitle = viewData.has("title") ? viewData.get("title").getAsString() : bvid;

        List<MediaInfo.PartInfo> partList = new ArrayList<>();
        if (viewData.has("pages") && viewData.get("pages").isJsonArray()) {
            JsonArray pagesArr = viewData.getAsJsonArray("pages");
            for (JsonElement pEl : pagesArr) {
                if (pEl.isJsonObject()) {
                    JsonObject pObj = pEl.getAsJsonObject();
                    MediaInfo.PartInfo p = new MediaInfo.PartInfo();
                    p.setPage(pObj.has("page") ? pObj.get("page").getAsInt() : 1);
                    p.setCid(pObj.has("cid") ? String.valueOf(pObj.get("cid").getAsLong()) : "");
                    p.setTitle(pObj.has("part") ? pObj.get("part").getAsString() : "");
                    long durSec = pObj.has("duration") ? pObj.get("duration").getAsLong() : 0L;
                    p.setDurationMs(durSec * 1000L);
                    partList.add(p);
                }
            }
        }

        if (partList.isEmpty()) {
            MediaInfo.PartInfo fallbackPart = new MediaInfo.PartInfo();
            fallbackPart.setPage(1);
            fallbackPart.setCid(viewData.has("cid") ? String.valueOf(viewData.get("cid").getAsLong()) : "");
            fallbackPart.setTitle(rawTitle);
            long durSec = viewData.has("duration") ? viewData.get("duration").getAsLong() : 0L;
            fallbackPart.setDurationMs(durSec * 1000L);
            partList.add(fallbackPart);
        }

        int targetIndex = Math.min(Math.max(params.page, 1), partList.size()) - 1;
        MediaInfo.PartInfo selectedPart = partList.get(targetIndex);

        MediaInfo info = new MediaInfo();
        info.setPlatform("bilibili");
        info.setId(bvid);
        info.setType("video");

        String displayTitle = partList.size() > 1
                ? rawTitle + " P" + selectedPart.getPage() + ":" + selectedPart.getTitle()
                : rawTitle;
        info.setTitle(displayTitle);

        String author = "";
        if (viewData.has("owner") && viewData.getAsJsonObject("owner").isJsonObject()) {
            JsonObject owner = viewData.getAsJsonObject("owner");
            if (owner.has("name")) {
                author = owner.get("name").getAsString();
            }
            if (owner.has("face")) {
                info.setAuthorAvatar(owner.get("face").getAsString());
            }
        }
        info.setAuthor(author);

        if (viewData.has("pic")) {
            info.setCover(viewData.get("pic").getAsString());
        }

        info.setDurationMs(selectedPart.getDurationMs() != null && selectedPart.getDurationMs() > 0
                ? selectedPart.getDurationMs()
                : (viewData.has("duration") ? viewData.get("duration").getAsLong() * 1000L : 0L));

        Map<String, Object> stats = new HashMap<>();
        if (viewData.has("stat") && viewData.getAsJsonObject("stat").isJsonObject()) {
            JsonObject stat = viewData.getAsJsonObject("stat");
            if (stat.has("like")) stats.put("like", stat.get("like").getAsLong());
            if (stat.has("view")) stats.put("view", stat.get("view").getAsLong());
            if (stat.has("favorite")) stats.put("fav", stat.get("favorite").getAsLong());
        }
        info.setStats(stats);
        info.setParts(partList);

        String safeTitle = HttpUtil.sanitizeFilename(displayTitle);
        info.setFilename(safeTitle + "_" + bvid);

        MediaInfo.StreamInfo streamInfo = fetchPlayUrl(bvid, selectedPart.getCid());
        info.setStream(streamInfo);

        return info;
    }
}
