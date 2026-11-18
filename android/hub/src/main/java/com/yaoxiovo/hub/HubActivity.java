package com.yaoxiovo.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class HubActivity extends Activity {
    private static final int INK = 0xff3e3129;
    private static final int MUTED = 0xff715b4f;
    private static final int BLUE = 0xff883c33;
    private static final int BACKGROUND = 0xfff8f0e1;
    private static final int SURFACE = 0xfffffbf1;
    private static final int LINE = 0xffe6d4ba;
    private static final int SELECTED = 0xfff3d9ca;
    private static final int TAPE = 0xffebc7a0;
    private static final int OAT = 0xfff2e0bf;
    private static final int PEACH = 0xfff5dcd1;
    private static final int SAGE = 0xffdbe4d1;
    private static final int LILAC = 0xffe6deef;
    private static final String DEFAULT_API_ORIGIN = "https://hub-api.yaoxi.wiki";

    private static final String[] TABS = {"首页", "工作台", "消息", "我的"};
    private static final String[] TAB_ICONS = {"⌂", "▦", "▤", "◎"};
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final ArrayList<JSONObject> repositories = new ArrayList<>();
    private String origin = DEFAULT_API_ORIGIN;
    private String session;
    private String accountName = "";
    private String pendingState;
    private String pendingCode;
    private boolean launchedSignIn;
    private boolean pollRunning;

    private enum Page {
        HOME(0), WORK(1), MESSAGES(2), ACCOUNT(3), DNS(1), EDGE(1), ANALYTICS(0), PROJECTS(1), PROJECT(1), BLOG(1), STATUS(0);

        final int tab;

        Page(int tab) {
            this.tab = tab;
        }
    }

    private Page page = Page.HOME;
    private String searchQuery = "";
    private String projectId = "";
    private int bingSiteIndex;
    private String bingPeriod = "7d";
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        origin = getPreferences(MODE_PRIVATE).getString("apiOrigin", DEFAULT_API_ORIGIN);
        if (state != null) {
            page = Page.valueOf(state.getString("page", Page.HOME.name()));
            searchQuery = state.getString("query", "");
            projectId = state.getString("projectId", "");
            bingSiteIndex = state.getInt("bingSite");
            bingPeriod = state.getString("bingPeriod", "7d");
            pendingState = state.getString("signInState");
            pendingCode = state.getString("signInCode");
            launchedSignIn = pendingState != null && pendingCode != null;
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (launchedSignIn && pendingState != null) checkSignIn(10);
    }

    @Override
    protected void onDestroy() {
        clock.removeCallbacksAndMessages(null);
        network.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page.name());
        state.putString("query", searchQuery);
        state.putString("projectId", projectId);
        state.putInt("bingSite", bingSiteIndex);
        state.putString("bingPeriod", bingPeriod);
        if (launchedSignIn) {
            state.putString("signInState", pendingState);
            state.putString("signInCode", pendingCode);
        }
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (page == Page.PROJECT) {
            open(Page.PROJECTS);
        } else if (page == Page.DNS || page == Page.EDGE || page == Page.PROJECTS || page == Page.BLOG) {
            open(Page.WORK);
        } else if (page != Page.HOME) {
            open(Page.HOME);
        } else {
            super.onBackPressed();
        }
    }

    private void open(Page destination) {
        if (page != destination) {
            page = destination;
            render();
        }
    }

    private void render() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);

        root.addView(topBar(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new Notebook();
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(24));
        content.setFocusableInTouchMode(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        root.addView(navigation(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        switch (page) {
            case HOME -> showHome();
            case WORK -> showWork();
            case MESSAGES -> showMessages();
            case ACCOUNT -> showAccount();
            case DNS -> showCloud(true);
            case EDGE -> showCloud(false);
            case ANALYTICS -> showAnalytics();
            case PROJECTS -> showProjects();
            case PROJECT -> showProject();
            case BLOG -> showBlog();
            case STATUS -> showStatus();
        }
        content.requestFocus();
        animateIn(content, 12);
    }

    private LinearLayout navigation() {
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(8), dp(5), dp(8), dp(5));
        bar.setBackgroundColor(SURFACE);
        for (int index = 0; index < TABS.length; index++) {
            Page destination = Page.values()[index];
            boolean active = page.tab == index;
            TextView item = text(TAB_ICONS[index] + "\n" + TABS[index], 13, active ? BLUE : MUTED, active);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(TABS[index]);
            item.setSelected(active);
            item.setFocusable(true);
            item.setOnClickListener(view -> open(destination));
            if (active) item.setBackground(shape(SELECTED, 12, false));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(53), 1);
            params.setMargins(dp(2), 0, dp(2), 0);
            bar.addView(item, params);
        }
        return bar;
    }

    private LinearLayout topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(11), dp(18), dp(11));
        bar.setBackgroundColor(SURFACE);

        TextView mark = text("Yx", 18, SURFACE, true);
        mark.setTypeface(Typeface.SERIF, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(shape(BLUE, 11, false));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(mark, new LinearLayout.LayoutParams(dp(37), dp(37)));

        TextView wordmark = text("Yaoxi Hub", 20, INK, true);
        wordmark.setTypeface(Typeface.SERIF, Typeface.BOLD);
        LinearLayout.LayoutParams title = new LinearLayout.LayoutParams(0, -2, 1);
        title.leftMargin = dp(11);
        bar.addView(wordmark, title);

        TextView domain = text("yaoxi.wiki ↗", 12, BLUE, true);
        domain.setGravity(Gravity.CENTER);
        domain.setPadding(dp(9), 0, dp(9), 0);
        domain.setMinHeight(dp(48));
        domain.setFocusable(true);
        domain.setOnClickListener(view -> openInApp("https://yaoxi.wiki"));
        bar.addView(domain, new LinearLayout.LayoutParams(-2, -2));
        return bar;
    }

    private void showHome() {
        LinearLayout cover = column();
        GradientDrawable wash = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xfffff7e5, 0xfff0d5b0});
        wash.setCornerRadius(dp(20));
        wash.setStroke(dp(1), LINE);
        cover.setBackground(wash);
        cover.setElevation(dp(2));
        cover.setPadding(dp(17), dp(15), dp(17), dp(17));

        TextView tape = text("Yaoxi 的手账", 13, INK, true);
        tape.setGravity(Gravity.CENTER);
        tape.setPadding(dp(12), dp(5), dp(12), dp(5));
        tape.setBackground(shape(TAPE, 4, false));
        tape.setRotation(-4);
        cover.addView(tape, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout spread = new LinearLayout(this);
        spread.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout note = column();
        TextView headline = text("所有项目，\n一个入口", 28, INK, true);
        headline.setTypeface(Typeface.SERIF, Typeface.BOLD);
        note.addView(headline);
        add(note, text("站点、项目与云服务", 14, MUTED, false), 7);
        spread.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        if (getResources().getConfiguration().screenWidthDp >= 350 &&
                getResources().getConfiguration().fontScale <= 1.2f) {
            JournalArtwork artwork = new JournalArtwork(this);
            LinearLayout.LayoutParams picture = new LinearLayout.LayoutParams(dp(132), dp(125));
            picture.leftMargin = dp(6);
            spread.addView(artwork, picture);
            animateIn(artwork, 9, 85);
        }
        add(cover, spread, 12);
        add(cover, action("打开工作台 →", () -> open(Page.WORK)), 13);
        content.addView(cover);

        section("站点直达");
        homePair(
            homeTile("主页", "个人主页", "yaoxi.wiki", OAT, () -> openInApp("https://yaoxi.wiki")),
            homeTile("博客", "个人博客", "blog.yaoxi.wiki", PEACH, () -> openInApp("https://blog.yaoxi.wiki")),
            50);
        homePair(
            homeTile("壁纸", "壁纸站", "pic.yaoxi.wiki", LILAC, () -> openInApp("https://pic.yaoxi.wiki")),
            homeTile("状态", "网站状态页", "status.yaoxi.wiki", SAGE, () -> openInApp("https://status.yaoxi.wiki")),
            95);

        section("壁纸精选");
        showWallpapers();

        section("站点监控");
        showHomeStatus();

        section("数据概览");
        showHomeAnalytics();

        section("快捷管理");
        homePair(
            homeTile("DNS", "DNS 解析", "腾讯云 DNSPod", SAGE, () -> open(Page.DNS)),
            homeTile("EO", "EdgeOne", "站点与缓存", PEACH, () -> open(Page.EDGE)),
            130);
    }

    private View homeTile(String mark, String title, String subtitle, int tone, Runnable navigate) {
        LinearLayout tile = column();
        tile.setPadding(dp(12), dp(11), dp(12), dp(11));
        tile.setBackground(shape(SURFACE, 13, true));
        tile.setMinimumHeight(dp(108));
        tile.setElevation(dp(1));
        tile.setFocusable(true);
        tile.setOnClickListener(view -> navigate.run());

        TextView tab = text(mark, 12, INK, true);
        tab.setPadding(dp(8), dp(3), dp(8), dp(3));
        tab.setBackground(shape(tone, 5, false));
        tab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(tab, new LinearLayout.LayoutParams(-2, -2));
        add(tile, text(title, 16, INK, true), 7);
        add(tile, text(subtitle, 12, MUTED, false), 3);
        return tile;
    }

    private void homePair(View first, View second, int delay) {
        if (getResources().getConfiguration().screenWidthDp < 350 ||
                getResources().getConfiguration().fontScale > 1.2f) {
            add(content, first, 8);
            add(content, second, 8);
            return;
        }
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, 1);
        left.rightMargin = dp(8);
        row.addView(first, left);
        row.addView(second, new LinearLayout.LayoutParams(0, -1, 1));
        add(content, row, 8);
        animateIn(row, 8, delay);
    }

    private void showHomeAnalytics() {
        LinearLayout rows = column();
        content.addView(rows);
        if (session == null) {
            add(rows, card("Umami 与 Bing", "访问量与搜索表现 · 登录后查看数据", () -> open(Page.ANALYTICS)), 8);
            return;
        }
        TextView loading = text("正在读取统计数据…", 14, MUTED, false);
        add(rows, loading, 8);
        api("/api/analytics", HubApi.object(), reply -> {
            rows.removeAllViews();
            JSONObject umami = reply.optJSONObject("umami");
            String visits = available(umami) ? "近 7 天页面浏览 " + count(umami, "pageviews") : analyticsStatus(umami);
            JSONObject bing = reply.optJSONObject("bing");
            JSONObject primary = selectedBingSite(bing, 0);
            JSONObject window = bingWindow(primary, "7d");
            String traffic = window == null ? analyticsStatus(primary == null ? bing : primary)
                : "点击 " + count(window, "clicks") + " · 展示 " + count(window, "impressions") +
                    " · 截至 " + date(window.optLong("endAt"), ZoneId.of("UTC"));
            add(rows, card("Umami · 博客访问", visits, () -> open(Page.ANALYTICS)), 8);
            add(rows, card("Bing · 主站搜索", traffic, () -> open(Page.ANALYTICS)), 8);
            animateIn(rows, 7);
        }, failure -> {
            rows.removeAllViews();
            add(rows, card("Umami 与 Bing", failure, () -> open(Page.ANALYTICS)), 8);
        });
    }

    private void showAnalytics() {
        content.addView(action("← 返回首页", () -> open(Page.HOME)));
        section("数据概览");
        LinearLayout reports = column();
        add(content, reports, 8);
        add(content, link("Umami 大盘", "umami.yaoxi.wiki", "https://umami.yaoxi.wiki"), 10);
        add(content, link("Bing 大盘", "bing.yaoxi.wiki", "https://bing.yaoxi.wiki"), 8);
        if (session == null) {
            reports.addView(card("登录后查看统计", "前往账号统一中心", () -> open(Page.ACCOUNT)));
            return;
        }

        TextView loading = text("正在读取统计数据…", 14, MUTED, false);
        reports.addView(loading);
        api("/api/analytics", HubApi.object(), reply -> {
            reports.removeAllViews();
            JSONObject umami = reply.optJSONObject("umami");
            String detail = analyticsStatus(umami);
            if (available(umami)) {
                ZoneId zone = ZoneId.of("Asia/Shanghai");
                detail = umami.optString("name") + " · 近 7 天\n" +
                    "页面浏览 " + count(umami, "pageviews") + " · 访客 " + count(umami, "visitors") +
                    " · 会话 " + count(umami, "visits") + "\n" +
                    date(umami.optLong("startAt"), zone) + " 至 " + date(umami.optLong("endAt"), zone);
            }
            reports.addView(card("Umami", detail, null));
            if (available(umami)) {
                addTrend(reports, "Umami · 每日浏览量", umami.optJSONArray("trend"));
                addRanking(reports, "热门页面", umami.optJSONArray("topPages"), "浏览");
            }

            LinearLayout bingReport = column();
            add(reports, bingReport, 8);
            showBing(bingReport, reply.optJSONObject("bing"));
            animateIn(reports, 7);
        }, loading::setText);
    }

    private void showBing(LinearLayout report, JSONObject bing) {
        report.removeAllViews();
        JSONArray sites = bing == null ? null : bing.optJSONArray("sites");
        if (!available(bing) || sites == null || sites.length() == 0) {
            report.addView(card("Bing", analyticsStatus(bing), null));
            return;
        }

        if (bingSiteIndex < 0 || bingSiteIndex >= sites.length()) bingSiteIndex = 0;
        JSONObject chosen = sites.optJSONObject(bingSiteIndex);
        String name = chosen.optString("name");
        boolean month = "28d".equals(bingPeriod);
        report.addView(action(name + " · 选择站点", () -> {
            String[] names = new String[sites.length()];
            for (int index = 0; index < names.length; index++) names[index] = sites.optJSONObject(index).optString("name");
            new AlertDialog.Builder(this).setTitle("选择 Bing 站点")
                .setSingleChoiceItems(names, bingSiteIndex, (dialog, index) -> {
                    bingSiteIndex = index;
                    dialog.dismiss();
                    showBing(report, bing);
                }).setNegativeButton("取消", null).show();
        }));
        add(report, action(month ? "最近 28 天报表 · 切换" : "最近 7 天报表 · 切换", () -> {
            bingPeriod = month ? "7d" : "28d";
            showBing(report, bing);
        }), 6);

        JSONObject window = bingWindow(chosen, bingPeriod);
        String detail = analyticsStatus(chosen);
        if (window != null) {
            ZoneId utc = ZoneId.of("UTC");
            String percentage = window.isNull("ctr") ? "—" : String.format(Locale.US, "%.2f%%", window.optDouble("ctr"));
            detail = "点击 " + count(window, "clicks") + " · 展示 " + count(window, "impressions") +
                " · 点击率 " + percentage + "\n" +
                date(window.optLong("startAt"), utc) + " 至 " + date(window.optLong("endAt"), utc);
        }
        add(report, card("Bing · 搜索表现", detail, null), 6);
        if (window != null) {
            addTrend(report, "Bing · 每日点击量", window.optJSONArray("trend"));
            addRanking(report, "热门搜索词", window.optJSONArray("topQueries"), "点击");
        }
    }

    private JSONObject selectedBingSite(JSONObject source, int index) {
        JSONArray sites = source == null ? null : source.optJSONArray("sites");
        return sites == null ? null : sites.optJSONObject(index);
    }

    private JSONObject bingWindow(JSONObject site, String period) {
        JSONObject periods = site == null ? null : site.optJSONObject("periods");
        return periods == null ? null : periods.optJSONObject(period);
    }

    private boolean available(JSONObject source) {
        return source != null && "available".equals(source.optString("status"));
    }

    private String analyticsStatus(JSONObject source) {
        String status = source == null ? "" : source.optString("status");
        return switch (status) {
            case "not-configured" -> "服务端尚未配置数据源";
            case "no-data" -> "上游还没有此站点的报表";
            default -> "暂时无法读取统计数据";
        };
    }

    private String count(JSONObject source, String key) {
        return source.isNull(key) ? "—" : NumberFormat.getIntegerInstance().format(source.optLong(key));
    }

    private String date(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString();
    }

    private void showWallpapers() {
        TextView loading = text("正在读取壁纸…", 13, MUTED, false);
        add(content, loading, 8);
        LinearLayout tiles = new LinearLayout(this);
        add(content, tiles, 6);
        String apiOrigin = origin;
        fetch(() -> HubApi.publicFeed(apiOrigin, "/public/wallpapers"), result -> {
            JSONArray items = result.optJSONArray("items");
            if (items == null || items.length() == 0) {
                loading.setText("暂时没有公开壁纸");
                return;
            }
            loading.setVisibility(View.GONE);
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) continue;
                ImageView picture = new ImageView(this);
                picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                picture.setBackground(shape(SELECTED, 9, true));
                picture.setClipToOutline(true);
                String title = item.optString("title");
                picture.setContentDescription(title.isEmpty() || title.startsWith("file_") ? "打开壁纸预览" : title);
                picture.setOnClickListener(view -> openInApp(item.optString("url")));
                picture.setFocusable(true);
                LinearLayout.LayoutParams frame = new LinearLayout.LayoutParams(0, dp(112), 1);
                if (index > 0) frame.leftMargin = dp(7);
                tiles.addView(picture, frame);
                loadWallpaper(picture, item.optString("image"));
            }
            animateIn(tiles, 6);
        }, loading::setText);
    }

    private void loadWallpaper(ImageView picture, String address) {
        network.execute(() -> {
            try {
                Bitmap bitmap = HubApi.wallpaper(address);
                runOnUiThread(() -> {
                    if (isDestroyed() || !picture.isAttachedToWindow()) {
                        if (bitmap != null) bitmap.recycle();
                    } else {
                        picture.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && picture.isAttachedToWindow()) {
                        picture.setImageResource(android.R.drawable.ic_menu_gallery);
                    }
                });
            }
        });
    }

    private void showHomeStatus() {
        TextView loading = text("正在读取站点状态…", 13, MUTED, false);
        add(content, loading, 6);
        LinearLayout rows = column();
        add(content, rows, 6);
        String apiOrigin = origin;
        fetch(() -> HubApi.publicFeed(apiOrigin, "/public/status"), result -> {
            JSONArray items = result.optJSONArray("items");
            if (items == null || items.length() == 0) {
                loading.setText("暂时没有探测结果");
                return;
            }
            int alerts = 0;
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item != null && !"访问正常".equals(monitorLabel(item))) alerts++;
            }
            loading.setVisibility(View.GONE);
            rows.addView(card("站点状态", items.length() + " 个项目 · " +
                (alerts == 0 ? "访问正常" : alerts + " 个项目需关注"), () -> open(Page.STATUS)));
        }, loading::setText);
    }

    private void showStatus() {
        content.addView(action("← 返回首页", () -> open(Page.HOME)));
        section("站点监控");
        TextView loading = text("正在读取站点状态…", 14, MUTED, false);
        LinearLayout panel = column();
        add(content, panel, 8);
        panel.addView(loading);
        String apiOrigin = origin;
        fetch(() -> HubApi.publicFeed(apiOrigin, "/public/status"), result -> {
            JSONArray items = result.optJSONArray("items");
            if (items == null || items.length() == 0) {
                loading.setText("暂时没有探测结果");
                return;
            }
            panel.removeAllViews();
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) continue;
                add(panel, card(item.optString("name") + " · " + monitorLabel(item),
                    monitorDetail(item), () -> openProject(item.optString("id"))), 8);
            }
        }, loading::setText);
    }

    private String monitorLabel(JSONObject item) {
        JSONArray checks = item.optJSONArray("checks");
        String label = "访问正常";
        if (checks == null || checks.length() == 0) return "尚无探测结果";
        for (int index = 0; index < checks.length(); index++) {
            JSONObject check = checks.optJSONObject(index);
            if (check == null) continue;
            if ("unavailable".equals(check.optString("status"))) return "访问异常";
            if ("slow".equals(check.optString("status"))) label = "响应较慢";
            long expiry = check.optLong("expiresAt");
            if (expiry > 0 && expiry - System.currentTimeMillis() <= 14 * 86400000L) label = "证书即将到期";
        }
        return label;
    }

    private String monitorDetail(JSONObject item) {
        JSONArray checks = item.optJSONArray("checks");
        if (checks == null || checks.length() == 0) return "尚无探测结果";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < checks.length(); index++) {
            JSONObject check = checks.optJSONObject(index);
            if (check == null) continue;
            if (result.length() > 0) result.append('\n');
            result.append(check.optString("name"));
            if (check.has("latency")) result.append(" · ").append(check.optInt("latency")).append(" ms")
                .append(" · HTTP ").append(check.optInt("http"));
            else result.append(" · ").append(check.optString("error", "无法连接"));
            if (!check.isNull("expiresAt")) result.append(" · 证书到期 ").append(
                date(check.optLong("expiresAt"), ZoneId.of("Asia/Shanghai")));
        }
        return result.toString();
    }

    private void showProjects() {
        heading("项目详情", "集中查看站点、发布和云资源");
        if (needsProjectLogin()) return;
        TextView loading = text("正在读取项目…", 14, MUTED, false);
        LinearLayout rows = column();
        add(content, rows, 8);
        rows.addView(loading);
        api("/api/projects", HubApi.object(), result -> {
            JSONArray items = result.optJSONArray("items");
            if (items == null || items.length() == 0) { loading.setText("暂时没有项目"); return; }
            rows.removeAllViews();
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) continue;
                add(rows, card(item.optString("name"), item.optString("url"),
                    () -> openProject(item.optString("id"))), 8);
            }
        }, loading::setText);
    }

    private boolean needsProjectLogin() {
        if (session != null) return false;
        add(content, card("登录后查看", "前往账号统一中心", () -> open(Page.ACCOUNT)), 10);
        return true;
    }

    private void openProject(String id) {
        projectId = id;
        if (page == Page.PROJECT) render();
        else open(Page.PROJECT);
    }

    private void showProject() {
        content.addView(action("← 项目列表", () -> open(Page.PROJECTS)));
        if (needsProjectLogin()) return;
        TextView loading = text("正在读取项目详情…", 14, MUTED, false);
        add(content, loading, 10);
        api("/api/project", HubApi.object("id", projectId), result -> {
            JSONObject project = result.optJSONObject("project");
            if (project == null) { loading.setText("项目详情暂不可用"); return; }
            content.removeView(loading);
            section(project.optString("name"));
            add(content, link("打开站点", project.optString("host"), project.optString("url")), 8);
            JSONObject monitor = result.optJSONObject("monitor");
            if (monitor != null) add(content, card("站点监控 · " + monitorLabel(monitor), monitorDetail(monitor), null), 8);
            showProjectBuild(result.optJSONObject("github"));
            showProjectDns(result.optJSONObject("dns"));
            showProjectEdge(result.optJSONObject("edge"));
            if (project.optBoolean("analytics")) showProjectAnalytics(project);
            if ("blog".equals(project.optString("id"))) {
                add(content, card("博客运营", "草稿、发布与评论审核", () -> open(Page.BLOG)), 8);
            }
        }, loading::setText);
    }

    private void showProjectBuild(JSONObject source) {
        if (source == null) return;
        section("GitHub");
        add(content, link("仓库", source.optString("repoUrl"), source.optString("repoUrl")), 8);
        JSONObject latest = source.optJSONObject("latestRun");
        if (latest != null) {
            String status = latest.isNull("conclusion") ? latest.optString("status") : latest.optString("conclusion");
            String label = switch (status) {
                case "success" -> "通过";
                case "failure" -> "失败";
                case "queued" -> "排队中";
                case "in_progress" -> "构建中";
                default -> status;
            };
            add(content, link("最近构建 · " + label, latest.optString("title"), latest.optString("url")), 8);
        } else if ("unavailable".equals(source.optString("workflowStatus"))) {
            add(content, card("构建记录", "暂时无法读取", null), 8);
        }
        JSONObject failure = source.optJSONObject("latestFailure");
        if (failure != null) {
            JSONObject step = failure.optJSONObject("step");
            add(content, link("最近失败步骤", step == null ? failure.optString("title") : step.optString("name"),
                step == null ? failure.optString("url") : step.optString("url")), 8);
        }
        JSONObject release = source.optJSONObject("release");
        if (release != null) add(content, link("最新版本 · " + release.optString("tag"),
            release.optString("publishedAt"), release.optString("url")), 8);
    }

    private void showProjectDns(JSONObject source) {
        section("DNS 与加速");
        JSONObject domain = source == null ? null : source.optJSONObject("domain");
        JSONArray records = source == null ? null : source.optJSONArray("records");
        StringBuilder detail = new StringBuilder();
        if (records != null) for (int index = 0; index < records.length(); index++) {
            JSONObject row = records.optJSONObject(index);
            if (row == null) continue;
            if (detail.length() > 0) detail.append('\n');
            detail.append(row.optString("type")).append(" · ").append(row.optString("value"))
                .append(" · TTL ").append(row.optInt("ttl"));
        }
        if (detail.length() == 0) {
            detail.append(source != null && "unavailable".equals(source.optString("status"))
                ? "暂时无法读取" : "未找到相关 DNSPod 记录");
        }
        add(content, card("DNSPod", detail.toString(), () -> {
            open(Page.DNS);
            if (domain != null) showDomain(domain);
        }), 8);
    }

    private void showProjectEdge(JSONObject source) {
        JSONObject site = source == null ? null : source.optJSONObject("site");
        String detail = site == null
            ? (source != null && "unavailable".equals(source.optString("status")) ? "暂时无法读取" : "未找到对应的加速域名")
            : site.optString("ZoneName") + " · " + source.optString("enabled");
        add(content, card("EdgeOne", detail, () -> {
            open(Page.EDGE);
            if (site != null) showZone(site);
        }), 8);
    }

    private void showProjectAnalytics(JSONObject project) {
        section("流量与搜索");
        LinearLayout rows = column();
        add(content, rows, 8);
        String host = project.optString("host");
        Runnable openReport = () -> {
            bingSiteIndex = "blog".equals(project.optString("id")) ? 1 : 0;
            open(Page.ANALYTICS);
        };
        rows.addView(card("Umami 与 Bing", "查看数据概览", openReport));
        api("/api/analytics", HubApi.object(), result -> {
            JSONObject umami = result.optJSONObject("umami");
            JSONObject bing = result.optJSONObject("bing");
            JSONArray sites = bing == null ? null : bing.optJSONArray("sites");
            if (available(umami) && host.equals(umami.optString("name"))) {
                add(rows, card("Umami · 近 7 天", "浏览 " + count(umami, "pageviews") +
                    " · 访客 " + count(umami, "visitors"), openReport), 8);
            }
            if (sites == null) return;
            for (int index = 0; index < sites.length(); index++) {
                JSONObject site = sites.optJSONObject(index);
                if (site == null || !host.equals(site.optString("name"))) continue;
                JSONObject period = bingWindow(site, "7d");
                if (period != null) add(rows, card("Bing · 7 天报表", "点击 " + count(period, "clicks") +
                    " · 展示 " + count(period, "impressions"), openReport), 8);
                break;
            }
        }, ignored -> {});
    }

    private void showBlog() {
        heading("博客运营", "草稿、发布与评论审核");
        add(content, link("打开发布编辑器", "在博客后台编辑并发起发布", "https://blog.yaoxi.wiki/admin/"), 8);
        if (needsProjectLogin()) return;
        TextView loading = text("正在读取博客…", 14, MUTED, false);
        add(content, loading, 10);
        api("/api/blog", HubApi.object(), data -> {
            content.removeView(loading);
            JSONObject posts = data.optJSONObject("posts");
            section("仓库草稿");
            showBlogPosts(posts == null ? null : posts.optJSONArray("drafts"), true);
            section("最近发布");
            showBlogPosts(posts == null ? null : posts.optJSONArray("published"), false);
            section("待审评论与弹幕");
            JSONArray queue = data.optJSONArray("pending");
            if (queue == null || queue.length() == 0) {
                add(content, card("审核队列", queue == null ? "暂时无法读取" : "暂无待审条目", null), 8);
            } else {
                for (int index = 0; index < queue.length(); index++) {
                    JSONObject item = queue.optJSONObject(index);
                    if (item == null) continue;
                    add(content, card(item.optString("author"), item.optString("body"), () -> moderate(item)), 8);
                }
            }
        }, loading::setText);
    }

    private void showBlogPosts(JSONArray rows, boolean draft) {
        if (rows == null || rows.length() == 0) {
            add(content, card(draft ? "草稿" : "文章",
                rows == null ? "暂时无法读取" : draft ? "暂无已同步的仓库草稿" : "暂无发布记录", null), 8);
            return;
        }
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            String target = draft ? "https://blog.yaoxi.wiki/admin/" :
                "https://blog.yaoxi.wiki/posts/" + Uri.encode(row.optString("slug"), "/") + "/";
            add(content, link(row.optString("title"), row.optString("published"), target), 8);
        }
    }

    private void moderate(JSONObject comment) {
        String[] choices = { "通过", "拒绝" };
        new AlertDialog.Builder(this).setTitle("评论审核").setItems(choices, (dialog, choice) ->
            confirm("确认" + choices[choice], comment.optString("body"), () ->
                api("/api/moderate", HubApi.object("id", comment.optString("id"),
                    "action", choice == 0 ? "approve" : "reject"), result -> {
                        toast("审核已提交");
                        render();
                    }, this::toast))).setNegativeButton("取消", null).show();
    }

    private void addTrend(LinearLayout parent, String label, JSONArray rows) {
        add(parent, text(label, 15, INK, true), 12);
        if (rows == null || rows.length() == 0) {
            add(parent, text(rows == null ? "暂时无法读取趋势" : "当前区间暂无报表", 13, MUTED, false), 5);
            return;
        }
        ArrayList<Long> points = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null && !row.isNull("value")) points.add(row.optLong("value"));
        }
        if (points.isEmpty()) return;
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(68));
        size.topMargin = dp(6);
        parent.addView(new DailyTrend(this, label, points), size);
        add(parent, text(rows.optJSONObject(0).optString("day") + "　—　" +
            rows.optJSONObject(rows.length() - 1).optString("day"), 12, MUTED, false), 5);
    }

    private void addRanking(LinearLayout parent, String title, JSONArray rows, String units) {
        add(parent, text(title, 15, INK, true), 12);
        if (rows == null || rows.length() == 0) {
            add(parent, text(rows == null ? "暂时无法读到排行" : "当前区间暂无排行", 13, MUTED, false), 5);
            return;
        }
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null) add(parent, card(row.optString("name"), units + " " + count(row, "value"), null), 5);
        }
    }

    private void showWork() {
        heading("工作台", "项目索引与云服务");
        section("项目");
        add(content, card("项目详情", "站点、构建、DNS、EdgeOne 和数据", () -> open(Page.PROJECTS)), 8);
        add(content, card("博客运营", "草稿、近期发布与评论审核", () -> open(Page.BLOG)), 8);
        section("快捷管理");
        add(content, card("DNS 解析", "腾讯云 DNSPod", () -> open(Page.DNS)), 8);
        add(content, card("EdgeOne", "站点、解析与缓存", () -> open(Page.EDGE)), 8);
        section("GitHub 仓库");
        EditText search = field("搜索仓库名称", searchQuery);
        add(content, search, 8);
        TextView count = text("正在读取 GitHub…", 13, MUTED, false);
        add(content, count, 10);
        LinearLayout results = column();
        content.addView(results);
        repositories.clear();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int length) {
                searchQuery = value.toString();
                filterRepositories(results, count);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        loadRepositories(0, results, count);
    }

    private void loadRepositories(int offset, LinearLayout results, TextView count) {
        String activeSession = session;
        String host = origin;
        fetch(() -> activeSession == null ? HubApi.publicRepositories(offset) :
                HubApi.send(host, "/api/repositories", activeSession, HubApi.object("offset", offset)), reply -> {
            JSONArray items = reply.optJSONArray("items");
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.optJSONObject(index);
                    if (item != null) repositories.add(item);
                }
            }
            filterRepositories(results, count);
            if (reply.optBoolean("more")) loadRepositories(offset + 100, results, count);
        }, count::setText);
    }

    private void filterRepositories(LinearLayout results, TextView count) {
        results.removeAllViews();
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        int matches = 0;
        for (JSONObject repo : repositories) {
            String name = repo.optString("name");
            if (name.toLowerCase(Locale.ROOT).contains(query)) {
                add(results, link(name, repo.optBoolean("private") ? "GitHub · 私有" : "GitHub", repo.optString("url")), 7);
                matches++;
            }
        }
        count.setText(matches == 0 ? "没有匹配的仓库。" : matches + " 个仓库");
    }

    private void showMessages() {
        heading("消息", "GitHub 构建与云操作记录");
        LinearLayout rows = column();
        add(content, rows, 15);
        TextView status = text("正在读取消息…", 14, MUTED, false);
        rows.addView(status);
        String activeSession = session;
        String host = origin;
        fetch(() -> activeSession == null ? HubApi.publicMessages() :
            HubApi.send(host, "/api/messages", activeSession, HubApi.object()), result -> {
            JSONArray items = result.optJSONArray("items");
            String error = result.optString("githubError");
            if (!error.isEmpty()) status.setText(error);
            else if (items == null || items.length() == 0) status.setText("暂无站点、构建或云操作提醒。");
            else rows.removeView(status);
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                JSONObject message = items.optJSONObject(i);
                String title = message.optString("title");
                String detail = message.optString("detail") + "\n" + message.optString("time");
                String url = message.optString("url");
                String related = message.optString("projectId");
                if (!url.isEmpty()) add(rows, link(title, detail, url), 8);
                else add(rows, card(title, detail, related.isEmpty() ? null : () -> openProject(related)), 8);
            }
            animateIn(rows, 6);
        }, status::setText);
    }

    private void showAccount() {
        heading("账号统一中心", "项目登录与服务授权");
        section("我的账号");
        if (session == null) {
            add(content, card("OAuth2.0-yaoxi", origin.isEmpty() ? "请先设置 Hub API 地址" : "在安全标签页登录统一账号", this::signIn), 8);
            if (pendingState != null) add(content, action("检查登录结果", () -> checkSignIn(1)), 8);
        } else {
            add(content, card(accountName, "统一账号", null), 8);
            add(content, action("退出登录", this::signOut), 8);
        }
        section("服务设置");
        add(content, card("Hub API 地址", origin.isEmpty() ? "未配置" : origin, this::configureOrigin), 8);
        if (session != null) {
            LinearLayout status = column();
            add(content, status, 6);
            api("/api/account", HubApi.object(), reply -> {
                add(status, card("腾讯云", reply.optBoolean("tencentConfigured") ? "已配置服务端授权" : "服务端未配置", null), 8);
                add(status, card("GitHub", reply.optBoolean("githubPrivateConfigured") ? "已配置私有仓库授权" : "公开仓库 API", null), 8);
            }, error -> add(status, card("连接状态", error, null), 8));
        }
    }

    private void configureOrigin() {
        EditText address = field("https://", origin);
        new AlertDialog.Builder(this).setTitle("Hub API 地址").setView(dialogContent(address))
            .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                try {
                    String nextOrigin = HubApi.validateOrigin(address.getText().toString());
                    if (!origin.equals(nextOrigin)) {
                        clearSignIn();
                        origin = nextOrigin;
                        getPreferences(MODE_PRIVATE).edit().putString("apiOrigin", origin).apply();
                    }
                    render();
                } catch (IllegalArgumentException error) { toast("请输入有效的 HTTPS 服务地址"); }
            }).show();
    }

    private void signIn() {
        if (origin.isEmpty()) { configureOrigin(); return; }
        String host = origin;
        fetch(() -> HubApi.send(host, "/auth/start", null, HubApi.object()), reply -> {
            String url = reply.optString("url");
            Uri target = Uri.parse(url);
            if (!HubBrowserActivity.isHttps(target) || !"account.yaoxi.wiki".equals(target.getHost()) ||
                    (target.getPort() != -1 && target.getPort() != 443) ||
                    !"/accounts-login.html".equals(target.getPath())) {
                toast("统一账号登录地址无效");
                return;
            }
            pendingState = reply.optString("state");
            pendingCode = reply.optString("code");
            if (openSignInTab(target)) {
                launchedSignIn = true;
            } else {
                pendingState = null;
                pendingCode = null;
                launchedSignIn = false;
            }
        }, this::toast);
    }

    private void checkSignIn(int retries) {
        if (pollRunning || pendingState == null) return;
        pollRunning = true;
        String state = pendingState;
        String code = pendingCode;
        String host = origin;
        network.execute(() -> {
            try {
                JSONObject reply = HubApi.send(host, "/auth/poll", null, HubApi.object("state", state, "code", code));
                runOnUiThread(() -> {
                    pollRunning = false;
                    if (!state.equals(pendingState) || isDestroyed()) return;
                    if (reply.optBoolean("pending")) {
                        if (retries > 1) clock.postDelayed(() -> checkSignIn(retries - 1), 1500);
                        else {
                            if (page == Page.ACCOUNT) render();
                            toast("请在安全标签页完成登录后再检查");
                        }
                    } else {
                        session = reply.optString("session", null);
                        accountName = reply.optString("account");
                        pendingState = null;
                        pendingCode = null;
                        launchedSignIn = false;
                        render();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    pollRunning = false;
                    if (state.equals(pendingState) && !isDestroyed()) {
                        toast(error.getMessage());
                        if (page == Page.ACCOUNT) render();
                    }
                });
            }
        });
    }

    private void clearSignIn() {
        session = null;
        accountName = "";
        pendingState = null;
        pendingCode = null;
        launchedSignIn = false;
    }

    private void signOut() {
        String activeSession = session;
        String host = origin;
        clearSignIn();
        network.execute(() -> {
            try { HubApi.send(host, "/auth/logout", activeSession, HubApi.object()); }
            catch (Exception ignored) {}
        });
        render();
    }

    private void showCloud(boolean dns) {
        content.addView(action("← 返回工作台", () -> open(Page.WORK)));
        add(content, text(dns ? "DNS 解析" : "EdgeOne", 30, INK, true), 16);
        String console = dns ? "DNSPod 控制台" : "EdgeOne 控制台";
        add(content, link("打开" + console, "腾讯云", dns
            ? "https://console.cloud.tencent.com/cns"
            : "https://console.cloud.tencent.com/edgeone"), 10);
        if (session == null) {
            add(content, card("登录后管理云资源", "前往账号统一中心", () -> open(Page.ACCOUNT)), 8);
            return;
        }
        LinearLayout rows = column();
        content.addView(rows);
        listCloud(rows, dns ? "domains" : "zones", HubApi.object(), 0,
            dns ? "DomainList" : "Zones", dns ? "DomainCountInfo" : "TotalCount",
            row -> card(dns ? row.optString("Name") : row.optString("ZoneName"), row.optString(dns ? "Status" : "ActiveStatus"),
                () -> { if (dns) showDomain(row); else showZone(row); }));
    }

    private interface RowView { View make(JSONObject row); }

    private void listCloud(LinearLayout parent, String apiName, JSONObject args, int offset,
                           String resultKey, String totalKey, RowView rowView) {
        TextView loading = text("正在读取…", 14, MUTED, false);
        add(parent, loading, 9);
        JSONObject request = new JSONObject();
        try {
            java.util.Iterator<String> keys = args.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                request.put(key, args.opt(key));
            }
            request.put("offset", offset);
        } catch (org.json.JSONException exception) {
            loading.setText("分页参数无效");
            return;
        }
        api("/api/" + apiName, request, reply -> {
            JSONArray items = reply.optJSONArray(resultKey);
            if (items == null || items.length() == 0) { loading.setText("没有记录。"); return; }
            parent.removeView(loading);
            for (int i = 0; i < items.length(); i++) add(parent, rowView.make(items.optJSONObject(i)), 8);
            JSONObject count = reply.optJSONObject(totalKey);
            int total = count == null ? reply.optInt(totalKey, -1) :
                count.optInt("TotalCount", count.optInt("DomainTotal", -1));
            if (items.length() == 50 && (total < 0 || total > offset + items.length())) {
                View more = action("加载更多", () -> {});
                more.setOnClickListener(view -> {
                    parent.removeView(view);
                    listCloud(parent, apiName, args, offset + items.length(), resultKey, totalKey, rowView);
                });
                add(parent, more, 8);
            }
        }, loading::setText);
    }

    private void showDomain(JSONObject domain) {
        content.removeAllViews();
        content.addView(action("← DNS 域名", this::render));
        section(domain.optString("Name"));
        JSONObject args = HubApi.object("domain", domain.optString("Name"), "domainId", domain.optLong("DomainId"));
        add(content, action("新增记录", () -> recordDialog(domain, null, false)), 8);
        add(content, action("查看可用线路", () -> api("/api/lines", args, result -> {
            JSONArray lines = result.optJSONArray("LineList");
            ArrayList<String> labels = new ArrayList<>();
            if (lines != null) for (int index = 0; index < lines.length(); index++) {
                JSONObject line = lines.optJSONObject(index);
                if (line != null) labels.add(line.optString("Name"));
            }
            new AlertDialog.Builder(this).setTitle("可用线路")
                .setMessage(labels.isEmpty() ? "没有可用线路。" : String.join("\n", labels))
                .setPositiveButton("关闭", null).show();
        }, this::toast)), 8);
        LinearLayout rows = column();
        content.addView(rows);
        listCloud(rows, "records", args, 0, "RecordList", "RecordCountInfo",
            record -> card(record.optString("Name") + " · " + record.optString("Type"),
                record.optString("Value") + "\n" + record.optString("Line") + " · TTL " + record.optString("TTL")
                    + " · " + record.optString("Status"), record.optBoolean("DefaultNS") ? null : () -> recordActions(domain, record, false)));
        animateIn(content, 10);
    }

    private void recordActions(JSONObject site, JSONObject record, boolean edge) {
        boolean enable = "DISABLE".equalsIgnoreCase(record.optString("Status"));
        String change = enable ? "启用记录" : "暂停记录";
        String value = recordValue(record, edge, "Value", "Content");
        new AlertDialog.Builder(this).setTitle(record.optString("Name") + " · " + record.optString("Type"))
            .setItems(new String[]{"编辑记录", change}, (dialog, which) -> {
                if (which == 0) recordDialog(site, record, edge);
                else confirm(change, record.optString("Name") + " → " + value, () -> {
                    JSONObject args = edge ? HubApi.object("zone", site.optString("ZoneId"),
                        "recordId", record.optString("RecordId"), "enabled", enable)
                        : HubApi.object("domain", site.optString("Name"), "domainId", site.optLong("DomainId"),
                        "recordId", record.optLong("RecordId"), "enabled", enable);
                    api(edge ? "/api/edgeRecordStatus" : "/api/recordStatus", args,
                        reply -> showDnsRecords(site, edge), this::toast);
                });
            }).show();
    }

    private String recordValue(JSONObject record, boolean edge, String dnsKey, String edgeKey) {
        return record == null ? "" : record.optString(edge ? edgeKey : dnsKey);
    }

    private void showDnsRecords(JSONObject site, boolean edge) {
        if (edge) showEdgeRecords(site);
        else showDomain(site);
    }

    private void recordDialog(JSONObject site, JSONObject record, boolean edge) {
        EditText name = field(edge ? "完整记录名" : "主机记录", recordValue(record, edge, "Name", "Name"));
        EditText type = field("记录类型", recordValue(record, edge, "Type", "Type"));
        EditText value = field("记录值", recordValue(record, edge, "Value", "Content"));
        EditText line = field("线路", record == null ? edge ? "Default" : "默认" : recordValue(record, edge, "Line", "Location"));
        EditText ttl = field("TTL（秒）", recordValue(record, edge, "TTL", "TTL"));
        EditText mx = field("MX 优先级（MX 记录适用）", recordValue(record, edge, "MX", "Priority"));
        AlertDialog editor = new AlertDialog.Builder(this).setTitle(record == null ? "新增 DNS 记录" : "编辑 DNS 记录")
            .setView(dialogContent(name, type, value, line, ttl, mx))
            .setNegativeButton("取消", null).setPositiveButton("预览变更", null).create();
        editor.setOnShowListener(dialog -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                JSONObject request = HubApi.object("name", input(name), "type", input(type).toUpperCase(Locale.ROOT),
                    "value", input(value), "line", input(line), "ttl", Integer.parseInt(input(ttl)));
                if (edge) request.put("zone", site.optString("ZoneId"));
                else {
                    request.put("domain", site.optString("Name"));
                    request.put("domainId", site.optLong("DomainId"));
                }
                if (!input(mx).isEmpty()) request.put("mx", Integer.parseInt(input(mx)));
                if (record != null) {
                    if (edge) request.put("recordId", record.optString("RecordId"));
                    else request.put("recordId", record.optLong("RecordId"));
                    request.put("status", record.optString("Status"));
                    request.put("weight", record.optInt("Weight", -1));
                }
                String summary = input(name) + " · " + input(type) + " → " + input(value) + "\n" +
                    input(line) + " · TTL " + input(ttl);
                if (record != null) summary = "原记录：" + record.optString("Name") + " · " +
                    record.optString("Type") + " → " + recordValue(record, edge, "Value", "Content") + "\n" +
                    recordValue(record, edge, "Line", "Location") + " · TTL " + record.optString("TTL") + "\n\n变更后：" + summary;
                confirm("确认 DNS 变更", summary, () -> {
                    button.setEnabled(false);
                    api(edge ? "/api/edgeRecordSave" : "/api/recordSave", request,
                        reply -> { editor.dismiss(); showDnsRecords(site, edge); }, error -> {
                            button.setEnabled(true);
                            toast(error);
                        });
                });
            } catch (org.json.JSONException | NumberFormatException error) { toast("请填写有效的 TTL 和 MX 优先级"); }
        }));
        editor.show();
    }

    private void showEdgeRecords(JSONObject site) {
        content.removeAllViews();
        content.addView(action("← EdgeOne 站点详情", () -> showZone(site)));
        section(site.optString("ZoneName") + " · 托管 DNS");
        add(content, action("新增记录", () -> recordDialog(site, null, true)), 8);
        LinearLayout rows = column();
        content.addView(rows);
        listCloud(rows, "edgeRecords", HubApi.object("zone", site.optString("ZoneId")), 0, "DnsRecords", "TotalCount",
            record -> card(record.optString("Name") + " · " + record.optString("Type"),
                record.optString("Content") + "\n" + record.optString("Location") + " · TTL " + record.optString("TTL")
                    + " · " + record.optString("Status"), () -> recordActions(site, record, true)));
        animateIn(content, 10);
    }

    private void showZone(JSONObject site) {
        content.removeAllViews();
        content.addView(action("← EdgeOne 站点", this::render));
        section(site.optString("ZoneName"));
        String access = switch (site.optString("Type")) {
            case "full" -> "NS 托管";
            case "partial" -> "CNAME";
            case "dnsPodAccess" -> "DNSPod 托管";
            default -> site.optString("Type");
        };
        add(content, text("接入方式：" + access, 14, MUTED, false), 6);
        JSONObject args = HubApi.object("zone", site.optString("ZoneId"));
        add(content, action("清除单个 URL 缓存", () -> {
            EditText url = field("https://", "");
            new AlertDialog.Builder(this).setTitle("清除 URL 缓存").setView(dialogContent(url))
                .setNegativeButton("取消", null).setPositiveButton("查看目标", (dialog, which) ->
                    confirm("确认清除", input(url), () -> api("/api/purge", HubApi.object("zone",
                        site.optString("ZoneId"), "url", input(url)), reply -> {
                            toast("已提交任务 " + reply.optString("JobId"));
                            showZone(site);
                        }, this::toast))).show();
        }), 8);
        LinearLayout rows = column();
        add(content, action("加速域名", () -> {
            rows.removeAllViews();
            listCloud(rows, "acceleration", args, 0, "AccelerationDomains", "TotalCount",
                domain -> card(domain.optString("DomainName"), domain.optString("DomainStatus", domain.optString("Status")), null));
        }), 8);
        if ("full".equalsIgnoreCase(site.optString("Type"))) {
            add(content, action("EdgeOne 托管的 DNS 记录", () -> showEdgeRecords(site)), 8);
        }
        add(content, action("近 6 天缓存任务历史", () -> {
            rows.removeAllViews();
            listCloud(rows, "purgeHistory", args, 0, "Tasks", "TotalCount",
                task -> card(task.optString("Target", task.optString("JobId")), task.optString("Status"), null));
        }), 8);
        content.addView(rows);
        animateIn(content, 10);
    }

    private void api(String path, JSONObject data, Consumer<JSONObject> success, Consumer<String> failure) {
        String host = origin;
        String activeSession = session;
        fetch(() -> HubApi.send(host, path, activeSession, data), success, failure);
    }

    private void fetch(Callable<JSONObject> request, Consumer<JSONObject> success, Consumer<String> failure) {
        LinearLayout owner = content;
        network.execute(() -> {
            try {
                JSONObject result = request.call();
                runOnUiThread(() -> { if (!isDestroyed() && owner == content) success.accept(result); });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && owner == content) failure.accept(error.getMessage() == null ? "请求失败" : error.getMessage());
                });
            }
        });
    }

    private EditText field(String hint, String value) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(15);
        field.setPadding(dp(13), dp(12), dp(13), dp(12));
        field.setBackground(shape(SURFACE, 9, true));
        return field;
    }

    private View dialogContent(View... fields) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        for (View field : fields) add(form, field, 7);
        scroll.addView(form);
        return scroll;
    }

    private String input(EditText field) {
        return field.getText().toString().trim();
    }

    private void confirm(String title, String detail, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(detail)
            .setNegativeButton("取消", null).setPositiveButton("确认", (dialog, which) -> action.run()).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void heading(String title, String subtitle) {
        TextView heading = text(title, 30, INK, true);
        heading.setTypeface(Typeface.SERIF, Typeface.BOLD);
        content.addView(heading);
        add(content, text(subtitle, 14, MUTED, false), 5);
    }

    private void section(String title) {
        add(content, text(title, 19, INK, true), 21);
    }

    private View link(String title, String subtitle, String url) {
        return card(title + " ↗", subtitle, () -> openInApp(url));
    }

    private void openInApp(String url) {
        Uri uri = Uri.parse(url);
        if (!HubBrowserActivity.isHttps(uri)) {
            toast("仅支持安全的 HTTPS 链接");
            return;
        }
        startActivity(new Intent(this, HubBrowserActivity.class).setData(uri));
    }

    private boolean openSignInTab(Uri url) {
        Intent service = new Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION);
        ArrayList<String> packages = new ArrayList<>();
        for (ResolveInfo info : getPackageManager().queryIntentServices(service, 0)) {
            packages.add(info.serviceInfo.packageName);
        }
        String provider = CustomTabsClient.getPackageName(this, packages);
        if (provider == null) {
            toast("当前设备没有支持安全标签页的浏览器，无法登录统一账号");
            return false;
        }
        CustomTabsIntent tab = new CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(SURFACE).build())
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build();
        tab.intent.setPackage(provider);
        try {
            tab.launchUrl(this, url);
            return true;
        } catch (ActivityNotFoundException exception) {
            toast("无法打开账号安全标签页");
            return false;
        }
    }

    private void animateIn(View view, int distance) {
        animateIn(view, distance, 0);
    }

    private void animateIn(View view, int distance, int delay) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(distance));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(225)
            .setInterpolator(new DecelerateInterpolator()).start();
    }

    private final class Notebook extends LinearLayout {
        private final Paint ruling = new Paint();

        Notebook() {
            super(HubActivity.this);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            ruling.setColor(0x66d8bc9b);
            ruling.setStrokeWidth(dp(1));
            for (int y = dp(26); y < getHeight(); y += dp(26)) {
                canvas.drawLine(0, y, getWidth(), y, ruling);
            }
        }
    }

    private View action(String label, Runnable action) {
        TextView button = text(label, 14, BLUE, true);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setMinHeight(dp(48));
        button.setPadding(dp(13), 0, dp(13), 0);
        button.setBackground(shape(SURFACE, 12, true));
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private View card(String title, String subtitle, Runnable action) {
        LinearLayout tile = column();
        tile.setPadding(dp(14), dp(11), dp(14), dp(11));
        tile.setBackground(shape(SURFACE, 10, true));
        tile.addView(text(title, 15, INK, true));
        add(tile, text(subtitle, 13, MUTED, false), 3);
        if (action != null) {
            tile.setFocusable(true);
            tile.setOnClickListener(view -> action.run());
        }
        return tile;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private GradientDrawable shape(int color, int corner, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(corner));
        if (border) drawable.setStroke(dp(1), LINE);
        return drawable;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
