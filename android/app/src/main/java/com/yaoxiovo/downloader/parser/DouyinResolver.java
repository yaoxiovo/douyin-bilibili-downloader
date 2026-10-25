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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-performance Douyin (TikTok China) media parser.
 * Ports zero-dependency parsing logic from core.js with Aweme 8.8.0 signature-free feed protocol.
 */
public class DouyinResolver {

    public static final String DY_APP_UA =
            "Aweme/8.8.0 (iPhone; iOS 7.0; Scale/2.00) CFNetwork/758.3.1 Darwin/15.4.0";

    private static final String[] DY_FEED_HOSTS = {
            "https://aweme.snssdk.com",
            "https://api.amemv.com"
    };

    private static final String[] DY_PLAY_HOSTS = {
            "https://aweme.snssdk.com",
            "https://api.amemv.com",
            "https://api-play-hl.amemv.com"
    };

    private static final String DY_FEED_PARAMS =
            "version_code=8.8.0&app_name=aweme&channel=App%20Store&device_platform=iphone&device_type=iPhone8,1&os_version=12.4&aid=1128&ac=WIFI";

    private static final String[] DY_RATIOS = {"1080p", "720p", "540p"};

    private static final Pattern PATTERN_INPUT_CHECK =
            Pattern.compile("douyin\\.com|iesdouyin\\.com|^(7\\d{17,19})$", Pattern.CASE_INSENSITIVE);

    private static final Pattern PATTERN_URL_EXTRACT =
            Pattern.compile("https?://[^\\s]*?douyin\\.com[^\\s]*", Pattern.CASE_INSENSITIVE);

    private static final Pattern[] AWEME_ID_PATTERNS = {
            Pattern.compile("/video/(\\d{15,20})"),
            Pattern.compile("/(?:note|notes|slides|gallery)/(\\d{15,20})"),
            Pattern.compile("[?&]modal_id=(\\d{15,20})"),
            Pattern.compile("[?&]item_ids?=(\\d{15,20})"),
            Pattern.compile("/(\\d{15,20})/?$")
    };

    public static boolean isDouyinInput(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }
        String trimmed = input.trim();
        return PATTERN_INPUT_CHECK.matcher(trimmed).find();
    }

    /**
     * Resolves raw input string or shared text into an aweme ID.
     */
    public static String resolveAwemeId(String input) throws IOException {
        String trimmed = input.trim();
        if (trimmed.matches("^(7\\d{17,19})$")) {
            return trimmed;
        }

        Matcher urlMatcher = PATTERN_URL_EXTRACT.matcher(trimmed);
        if (!urlMatcher.find()) {
            throw new IOException("输入中找不到抖音分享链接或视频ID");
        }

        String rawUrl = urlMatcher.group(0).replaceAll("[)），，。]+$", "");
        String currentUrl = rawUrl;

        OkHttpClient manualClient = HttpUtil.getManualRedirectClient();

        for (int hop = 0; hop < 5 && currentUrl.contains("v.douyin.com/"); hop++) {
            Request request = new Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", DY_APP_UA)
                    .get()
                    .build();

            try (Response response = manualClient.newCall(request).execute()) {
                String location = response.header("Location");
                if (location == null || location.isEmpty()) {
                    break;
                }
                URL base = new URL(currentUrl);
                currentUrl = new URL(base, location).toString();
                HttpUtil.sleep(150);
            }
        }

        for (Pattern pattern : AWEME_ID_PATTERNS) {
            Matcher m = pattern.matcher(currentUrl);
            if (m.find()) {
                return m.group(1);
            }
        }

        if (currentUrl.matches("^https?://www\\.douyin\\.com/?$")) {
            throw new IOException("抖音短链无效（服务端把它兜底跳到了主页）：多半是复制时丢了字符或链接已过期，请重新「分享→复制链接」");
        }

        throw new IOException("抖音链接重定向后未能提取视频ID：" + currentUrl);
    }

    /**
     * Fetches aweme detail via the un-signed feed API.
     */
    public static JsonObject fetchAwemeFeed(String awemeId) throws IOException {
        Exception lastException = null;
        OkHttpClient client = HttpUtil.getClient();

        for (String host : DY_FEED_HOSTS) {
            for (int attempt = 0; attempt < 3; attempt++) {
                String feedUrl = host + "/aweme/v1/feed/?aweme_id=" + awemeId + "&" + DY_FEED_PARAMS;
                Request request = new Request.Builder()
                        .url(feedUrl)
                        .header("User-Agent", DY_APP_UA)
                        .get()
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("HTTP " + response.code());
                    }

                    String bodyString = response.body().string();
                    JsonObject root = JsonParser.parseString(bodyString).getAsJsonObject();
                    JsonArray list = root.has("aweme_list") && root.get("aweme_list").isJsonArray()
                            ? root.getAsJsonArray("aweme_list")
                            : new JsonArray();

                    for (JsonElement el : list) {
                        if (el.isJsonObject()) {
                            JsonObject item = el.getAsJsonObject();
                            if (item.has("aweme_id") && awemeId.equals(item.get("aweme_id").getAsString())) {
                                return item;
                            }
                        }
                    }

                    if (list.size() > 0) {
                        throw new IOException("作品已删除或不可见（接口只返回了推荐内容）");
                    } else {
                        int statusCode = root.has("status_code") ? root.get("status_code").getAsInt() : -1;
                        throw new IOException("接口返回空（status_code=" + statusCode + "），作品可能已删除或不可见");
                    }
                } catch (Exception e) {
                    lastException = e;
                    HttpUtil.sleep(800 * (attempt + 1));
                }
            }
            HttpUtil.jitter();
        }

        throw new IOException("抖音 feed 接口两套域名均请求失败: " +
                (lastException != null ? lastException.getMessage() : "未知错误"));
    }

    /**
     * Probes candidate play streams across 1080p, 720p, 540p ratios.
     */
    public static List<CandidateStream> probeCandidates(String videoUri) {
        if (videoUri == null || videoUri.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, CandidateStream> seen = new LinkedHashMap<>();
        OkHttpClient client = HttpUtil.getClient();

        for (String ratio : DY_RATIOS) {
            for (String host : DY_PLAY_HOSTS) {
                String url = host + "/aweme/v1/play/?video_id=" + videoUri + "&ratio=" + ratio + "&line=0";
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", DY_APP_UA)
                        .header("Range", "bytes=0-0")
                        .get()
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    int code = response.code();
                    if (code == 206 || code == 200) {
                        long total = 0;
                        String contentRange = response.header("Content-Range");
                        if (contentRange != null && contentRange.contains("/")) {
                            String[] parts = contentRange.split("/");
                            if (parts.length > 1) {
                                try {
                                    total = Long.parseLong(parts[1].trim());
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                        if (total <= 0 && response.body() != null) {
                            total = response.body().contentLength();
                        }

                        if (total > 0) {
                            String finalUrl = response.request().url().toString();
                            String key = finalUrl + "|" + total;
                            if (!seen.containsKey(key)) {
                                seen.put(key, new CandidateStream(url, total, ratio));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Fallthrough to next candidate
                }
                HttpUtil.sleep(120);
            }
        }

        List<CandidateStream> result = new ArrayList<>(seen.values());
        Collections.sort(result, (a, b) -> Long.compare(b.totalBytes, a.totalBytes));
        return result;
    }

    private static String pickBrowserCover(JsonObject video, List<String> images) {
        Pattern friendlyPattern = Pattern.compile("\\.(webp|jpe?g|png|gif)(\\?|$)", Pattern.CASE_INSENSITIVE);

        if (video != null) {
            if (video.has("cover") && video.getAsJsonObject("cover").has("url_list")) {
                JsonArray urls = video.getAsJsonObject("cover").getAsJsonArray("url_list");
                for (JsonElement u : urls) {
                    String str = u.getAsString();
                    if (str.contains("~tplv") && friendlyPattern.matcher(str).find()) {
                        return str;
                    }
                }
            }
            if (video.has("origin_cover") && video.getAsJsonObject("origin_cover").has("url_list")) {
                JsonArray urls = video.getAsJsonObject("origin_cover").getAsJsonArray("url_list");
                for (JsonElement u : urls) {
                    String str = u.getAsString();
                    if (str.contains("~tplv") && friendlyPattern.matcher(str).find()) {
                        return str;
                    }
                }
            }
            if (video.has("dynamic_cover") && video.getAsJsonObject("dynamic_cover").has("url_list")) {
                JsonArray urls = video.getAsJsonObject("dynamic_cover").getAsJsonArray("url_list");
                if (urls.size() > 0) {
                    return urls.get(0).getAsString();
                }
            }
        }

        if (images != null && !images.isEmpty()) {
            return images.get(0);
        }

        if (video != null && video.has("cover") && video.getAsJsonObject("cover").has("url_list")) {
            JsonArray urls = video.getAsJsonObject("cover").getAsJsonArray("url_list");
            if (urls.size() > 0) {
                return urls.get(0).getAsString();
            }
        }

        return "";
    }

    /**
     * Resolves Douyin media metadata and optimal stream/image set.
     */
    public static MediaInfo resolve(String input) throws IOException {
        String awemeId = resolveAwemeId(input);
        JsonObject item = fetchAwemeFeed(awemeId);

        MediaInfo info = new MediaInfo();
        info.setPlatform("douyin");
        info.setId(awemeId);

        String title = item.has("desc") ? item.get("desc").getAsString() : awemeId;
        info.setTitle(title);

        String author = "";
        String authorAvatar = "";
        if (item.has("author") && item.getAsJsonObject("author").isJsonObject()) {
            JsonObject authorObj = item.getAsJsonObject("author");
            if (authorObj.has("nickname")) {
                author = authorObj.get("nickname").getAsString();
            }
            if (authorObj.has("avatar_thumb") && authorObj.getAsJsonObject("avatar_thumb").has("url_list")) {
                JsonArray avatarUrls = authorObj.getAsJsonObject("avatar_thumb").getAsJsonArray("url_list");
                if (avatarUrls.size() > 0) {
                    authorAvatar = avatarUrls.get(0).getAsString();
                }
            }
        }
        info.setAuthor(author);
        info.setAuthorAvatar(authorAvatar);

        // Stats
        Map<String, Object> stats = new HashMap<>();
        if (item.has("statistics") && item.getAsJsonObject("statistics").isJsonObject()) {
            JsonObject statsObj = item.getAsJsonObject("statistics");
            if (statsObj.has("digg_count")) stats.put("like", statsObj.get("digg_count").getAsLong());
            if (statsObj.has("comment_count")) stats.put("comment", statsObj.get("comment_count").getAsLong());
            if (statsObj.has("collect_count")) stats.put("fav", statsObj.get("collect_count").getAsLong());
        }
        info.setStats(stats);

        // Images detection
        List<String> imageUrls = new ArrayList<>();
        if (item.has("images") && item.get("images").isJsonArray()) {
            JsonArray imgArr = item.getAsJsonArray("images");
            for (JsonElement imgEl : imgArr) {
                if (imgEl.isJsonObject()) {
                    JsonObject imgObj = imgEl.getAsJsonObject();
                    String selectedUrl = null;
                    if (imgObj.has("download_url_list") && imgObj.get("download_url_list").isJsonArray()) {
                        JsonArray dlList = imgObj.getAsJsonArray("download_url_list");
                        if (dlList.size() > 0) {
                            selectedUrl = dlList.get(0).getAsString();
                        }
                    }
                    if (selectedUrl == null && imgObj.has("url_list") && imgObj.get("url_list").isJsonArray()) {
                        JsonArray uList = imgObj.getAsJsonArray("url_list");
                        if (uList.size() > 0) {
                            selectedUrl = uList.get(0).getAsString();
                        }
                    }
                    if (selectedUrl != null) {
                        imageUrls.add(selectedUrl);
                    }
                }
            }
        }

        JsonObject videoObj = item.has("video") && item.getAsJsonObject("video").isJsonObject()
                ? item.getAsJsonObject("video")
                : null;

        info.setCover(pickBrowserCover(videoObj, imageUrls));

        long durationMs = 0;
        if (videoObj != null && videoObj.has("duration")) {
            durationMs = videoObj.get("duration").getAsLong();
        }
        info.setDurationMs(durationMs);

        String filename = HttpUtil.sanitizeFilename(title) + "_" + awemeId;
        info.setFilename(filename);

        if (!imageUrls.isEmpty()) {
            info.setType("images");
            info.setImages(imageUrls);
            info.setImagesCount(imageUrls.size());
        } else {
            info.setType("video");
            info.setImagesCount(0);

            String videoUri = "";
            List<String> fallbackUrls = new ArrayList<>();
            if (videoObj != null && videoObj.has("play_addr") && videoObj.getAsJsonObject("play_addr").isJsonObject()) {
                JsonObject playAddr = videoObj.getAsJsonObject("play_addr");
                if (playAddr.has("uri")) {
                    videoUri = playAddr.get("uri").getAsString();
                }
                if (playAddr.has("url_list") && playAddr.get("url_list").isJsonArray()) {
                    for (JsonElement u : playAddr.getAsJsonArray("url_list")) {
                        fallbackUrls.add(u.getAsString());
                    }
                }
            }

            HttpUtil.jitter();
            List<CandidateStream> candidates = probeCandidates(videoUri);

            MediaInfo.StreamInfo streamInfo = new MediaInfo.StreamInfo();
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", DY_APP_UA);
            streamInfo.setHeaders(headers);

            if (!candidates.isEmpty()) {
                CandidateStream best = candidates.get(0);
                streamInfo.setUrl(best.url);
                streamInfo.setTotalBytes(best.totalBytes);
                streamInfo.setQuality(best.quality);
            } else if (!fallbackUrls.isEmpty()) {
                streamInfo.setUrl(fallbackUrls.get(0));
                streamInfo.setTotalBytes(0L);
                streamInfo.setQuality("fallback");
            } else {
                throw new IOException("未能提取到抖音有效播放地址");
            }
            info.setStream(streamInfo);
        }

        return info;
    }

    public static class CandidateStream {
        public final String url;
        public final long totalBytes;
        public final String quality;

        public CandidateStream(String url, long totalBytes, String quality) {
            this.url = url;
            this.totalBytes = totalBytes;
            this.quality = quality;
        }
    }
}
