package com.yaoxiovo.hub;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class HubActivity extends Activity {
    private static final int INK = 0xff16213a;
    private static final int MUTED = 0xff53627b;
    private static final int BLUE = 0xff2456e9;
    private static final int BACKGROUND = 0xfff5f7fb;
    private static final int SURFACE = 0xffffffff;
    private static final int LINE = 0xffdde5f0;
    private static final int SELECTED = 0xffeaf0ff;

    private static final String[] TABS = {"首页", "工作台", "消息", "我的"};
    private static final String[] TAB_ICONS = {"⌂", "▦", "▤", "◎"};
    private static final String[] REPOSITORIES = {
        "yaoxi-ovo", "blog", "astro", "music", "jpg", "umami", "blogjpg",
        "Android-music", "novel", "RMB", "cloud-status", "OAuth2.0-yaoxi",
        "yaoxi-ovo-new", "douyin-bilibili-downloader"
    };

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
        if (state != null) {
            page = Page.valueOf(state.getString("page", Page.HOME.name()));
            searchQuery = state.getString("query", "");
        }
        render();
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

        TextView brand = text("YX  ·  YaoXi Hub", 19, INK, true);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(20), 0, dp(20), 0);
        brand.setBackgroundColor(SURFACE);
        root.addView(brand, new LinearLayout.LayoutParams(-1, dp(56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
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

    private void showHome() {
        LinearLayout hero = column();
        GradientDrawable gradient = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{0xff132d94, 0xff4269f1});
        gradient.setCornerRadius(dp(24));
        hero.setBackground(gradient);
        hero.setPadding(dp(23), dp(23), dp(23), dp(23));
        hero.addView(text("YAOXI HUB", 12, 0xffe1e9ff, true));
        add(hero, text("所有项目，一个入口", 29, SURFACE, true), 7);
        add(hero, text("访问常用站点、公开仓库与云控制台。", 14, 0xffe8eeff, false), 7);
        add(hero, action("打开工作台 →", () -> open(Page.WORK)), 16);
        content.addView(hero);

        section("站点直达");
        add(content, link("个人主页", "yaoxi.wiki", "https://yaoxi.wiki"), 8);
        add(content, link("个人博客", "blog.yaoxi.wiki", "https://blog.yaoxi.wiki"), 8);
        add(content, link("网站状态页", "status.yaoxi.wiki", "https://status.yaoxi.wiki"), 8);
        add(content, link("Bing 搜索数据", "bing.yaoxi.wiki", "https://bing.yaoxi.wiki"), 8);
    }

    private void showWork() {
        heading("工作台", "项目入口与云服务管理");
        section("快捷管理");
        add(content, card("DNS 解析", "腾讯云 DNSPod", () -> open(Page.DNS)), 8);
        add(content, card("EdgeOne", "站点与缓存", () -> open(Page.EDGE)), 8);

        section("公开 GitHub 仓库");
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索仓库名称");
        search.setText(searchQuery);
        search.setTextSize(15);
        search.setPadding(dp(13), dp(12), dp(13), dp(12));
        search.setBackground(shape(SURFACE, 12, true));
        add(content, search, 8);

        TextView count = text("", 13, MUTED, false);
        add(content, count, 10);
        LinearLayout results = column();
        content.addView(results);
        showRepositories(results, count);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int length) {
                searchQuery = value.toString();
                showRepositories(results, count);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
    }

    private void showRepositories(LinearLayout results, TextView count) {
        results.removeAllViews();
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String name : REPOSITORIES) {
            if (name.toLowerCase(Locale.ROOT).contains(query)) {
                add(results, link(name, "GitHub", "https://github.com/yaoxiovo/" + name), 7);
                matches++;
            }
        }
        count.setText(matches == 0 ? "没有匹配的仓库。" : matches + " 个仓库");
    }

    private void showMessages() {
        heading("消息", "站点、部署与云操作通知");
        add(content, card("消息源尚未接入", "接入后可在这里查看通知。", null), 20);
    }

    private void showAccount() {
        heading("账号统一中心", "项目登录与服务授权");
        section("我的账号");
        add(content, card("OAuth2.0-yaoxi", "统一登录未接入", null), 8);
        section("服务授权");
        add(content, card("GitHub", "公开仓库快照", null), 8);
        add(content, card("腾讯云", "API 未接入", null), 8);
    }

    private void showCloud(boolean dns) {
        content.addView(action("← 返回工作台", () -> open(Page.WORK)));
        add(content, text(dns ? "DNS 解析" : "EdgeOne", 30, INK, true), 16);
        String console = dns ? "DNSPod 控制台" : "EdgeOne 控制台";
        add(content, card("API 未接入", "可前往腾讯云控制台查看和管理。", null), 12);
        add(content, link("打开" + console, "腾讯云", dns
            ? "https://console.cloud.tencent.com/cns"
            : "https://console.cloud.tencent.com/edgeone"), 10);
    }

    private void heading(String title, String subtitle) {
        content.addView(text(title, 30, INK, true));
        add(content, text(subtitle, 14, MUTED, false), 5);
    }

    private void section(String title) {
        add(content, text(title, 19, INK, true), 21);
    }

    private View link(String title, String subtitle, String url) {
        return card(title + " ↗", subtitle, () -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, "设备上没有可打开链接的应用", Toast.LENGTH_SHORT).show();
            }
        });
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
        tile.setBackground(shape(SURFACE, 14, true));
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
