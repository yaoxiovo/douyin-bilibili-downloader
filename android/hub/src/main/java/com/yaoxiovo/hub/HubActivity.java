package com.yaoxiovo.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.Canvas;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
        HOME(0), WORK(1), MESSAGES(2), ACCOUNT(3), DNS(1), EDGE(1);

        final int tab;

        Page(int tab) {
            this.tab = tab;
        }
    }

    private Page page = Page.HOME;
    private String searchQuery = "";
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        origin = getPreferences(MODE_PRIVATE).getString("apiOrigin", DEFAULT_API_ORIGIN);
        if (state != null) {
            page = Page.valueOf(state.getString("page", Page.HOME.name()));
            searchQuery = state.getString("query", "");
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
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (page == Page.DNS || page == Page.EDGE) {
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
        domain.setOnClickListener(view -> openBrowser("https://yaoxi.wiki"));
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
            homeTile("主页", "个人主页", "yaoxi.wiki", OAT, () -> openBrowser("https://yaoxi.wiki")),
            homeTile("博客", "个人博客", "blog.yaoxi.wiki", PEACH, () -> openBrowser("https://blog.yaoxi.wiki")),
            50);
        homePair(
            homeTile("状态", "网站状态页", "status.yaoxi.wiki", SAGE, () -> openBrowser("https://status.yaoxi.wiki")),
            homeTile("数据", "Bing 搜索数据", "bing.yaoxi.wiki", LILAC, () -> openBrowser("https://bing.yaoxi.wiki")),
            95);

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

    private void showWork() {
        heading("工作台", "项目索引与云服务");
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
            else if (items == null || items.length() == 0) status.setText("最近没有构建失败或云操作记录。");
            else rows.removeView(status);
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                JSONObject message = items.optJSONObject(i);
                String title = message.optString("title");
                String detail = message.optString("detail") + "\n" + message.optString("time");
                String url = message.optString("url");
                add(rows, url.isEmpty() ? card(title, detail, null) : link(title, detail, url), 8);
            }
            animateIn(rows, 6);
        }, status::setText);
    }

    private void showAccount() {
        heading("账号统一中心", "项目登录与服务授权");
        section("我的账号");
        if (session == null) {
            add(content, card("OAuth2.0-yaoxi", origin.isEmpty() ? "请先设置 Hub API 地址" : "使用浏览器登录统一账号", this::signIn), 8);
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
            if (!"https".equals(target.getScheme()) || !"account.yaoxi.wiki".equals(target.getHost())) {
                toast("统一账号登录地址无效");
                return;
            }
            pendingState = reply.optString("state");
            pendingCode = reply.optString("code");
            launchedSignIn = true;
            openBrowser(url);
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
                            toast("请完成浏览器登录后再检查");
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
        return card(title + " ↗", subtitle, () -> openBrowser(url));
    }

    private void openBrowser(String url) {
        Uri uri = Uri.parse(url);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
            toast("链接无效");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            toast("设备上没有可打开链接的应用");
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
