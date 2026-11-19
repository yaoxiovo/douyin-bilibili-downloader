package com.yaoxiovo.hub;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsService;
import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class HubActivity extends Activity {
    private static final String DEFAULT_ORIGIN = "https://hub-api.yaoxi.wiki";
    private static final Set<String> OPERATIONS = new HashSet<>(Arrays.asList(
        "account", "analytics", "repositories", "messages", "projects", "project", "blog", "moderate",
        "domains", "records", "lines", "recordSave", "recordStatus", "zones", "acceleration",
        "edgeRecords", "edgeRecordSave", "edgeRecordStatus", "purgeHistory", "purge"));

    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private WebView browser;
    private String origin;
    private String session;
    private String owner = "";
    private String pendingState;
    private String pendingCode;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        origin = getPreferences(MODE_PRIVATE).getString("apiOrigin", DEFAULT_ORIGIN);
        if (state != null) {
            pendingState = state.getString("signInState");
            pendingCode = state.getString("signInCode");
        }
        try {
            browser = HubUiWebView.create(this, (view, message, source, mainFrame, reply) ->
                handle(message.getData(), reply));
            setContentView(browser);
            HubUiWebView.load(browser, "index.html");
        } catch (RuntimeException error) {
            TextView fallback = new TextView(this);
            fallback.setText("请更新 Android System WebView 后重试。");
            fallback.setGravity(android.view.Gravity.CENTER);
            setContentView(fallback);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (browser != null) browser.evaluateJavascript("window.dispatchEvent(new Event('hub-resume'))", null);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("signInState", pendingState);
        state.putString("signInCode", pendingCode);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (browser == null) super.onBackPressed();
        else browser.evaluateJavascript("typeof HubNavBack === 'function' && HubNavBack()", result -> {
            if (!"true".equals(result)) super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        network.shutdownNow();
        if (browser != null) {
            setContentView(new android.view.View(this));
            browser.destroy();
        }
        super.onDestroy();
    }

    private JSONObject info() {
        return HubApi.object("origin", origin, "account", owner, "pending", pendingState != null);
    }

    private void handle(String raw, JavaScriptReplyProxy reply) {
        if (raw == null || raw.length() > 20000) return;
        int id = 0;
        try {
            JSONObject input = new JSONObject(raw);
            id = input.getInt("id");
            final int ticket = id;
            JSONObject supplied = input.optJSONObject("payload");
            JSONObject payload = supplied == null ? new JSONObject() : supplied;
            Consumer<JSONObject> success = result -> answer(reply, ticket, result, null);
            Consumer<Exception> failed = error -> answer(reply, ticket, null, error);
            String activeOrigin = origin;
            String activeSession = session;
            switch (input.getString("method")) {
                case "info" -> success.accept(info());
                case "configure" -> {
                    String updated = HubApi.validateOrigin(payload.getString("url"));
                    if (!origin.equals(updated)) {
                        origin = updated;
                        getPreferences(MODE_PRIVATE).edit().putString("apiOrigin", origin).apply();
                        clearSession();
                    }
                    success.accept(info());
                }
                case "open" -> {
                    Uri target = Uri.parse(payload.getString("url"));
                    if (!HubBrowserActivity.isHttps(target)) throw new IllegalArgumentException("仅支持 HTTPS 链接");
                    startActivity(new Intent(this, HubBrowserActivity.class).setData(target));
                    success.accept(HubApi.object());
                }
                case "public" -> {
                    String resource = payload.getString("resource");
                    if (!"status".equals(resource) && !"wallpapers".equals(resource)) {
                        throw new IllegalArgumentException("数据源不可用");
                    }
                    fetch(() -> HubApi.publicFeed(activeOrigin, "/public/" + resource), success, failed);
                }
                case "repositories" -> {
                    int offset = payload.optInt("offset");
                    fetch(() -> HubApi.publicRepositories(offset), success, failed);
                }
                case "messages" -> fetch(HubApi::publicMessages, success, failed);
                case "api" -> {
                    String operation = payload.getString("operation");
                    if (!OPERATIONS.contains(operation)) throw new IllegalArgumentException("操作不可用");
                    if (activeSession == null) throw new IllegalArgumentException("请先登录");
                    JSONObject data = payload.getJSONObject("data");
                    fetch(() -> HubApi.send(activeOrigin, "/api/" + operation, activeSession, data), success, failed);
                }
                case "signIn" -> fetch(() -> HubApi.send(activeOrigin, "/auth/start", null, HubApi.object()), data -> {
                    if (!activeOrigin.equals(origin)) {
                        failed.accept(new IllegalStateException("服务地址已更改，请重新登录"));
                        return;
                    }
                    try {
                        Uri target = Uri.parse(data.getString("url"));
                        if (!HubBrowserActivity.isHttps(target) || !"account.yaoxi.wiki".equals(target.getHost()) ||
                            (target.getPort() != -1 && target.getPort() != 443) ||
                            !"/accounts-login.html".equals(target.getPath())) {
                            throw new IllegalArgumentException("统一账号登录地址无效");
                        }
                        pendingState = data.getString("state");
                        pendingCode = data.getString("code");
                        openSignInTab(target);
                        success.accept(info());
                    } catch (Exception error) {
                        pendingState = null;
                        pendingCode = null;
                        failed.accept(error);
                    }
                }, failed);
                case "poll" -> {
                    if (pendingState == null) { success.accept(info()); return; }
                    String state = pendingState;
                    String code = pendingCode;
                    fetch(() -> HubApi.send(activeOrigin, "/auth/poll", null, HubApi.object("state", state, "code", code)), data -> {
                        if (state.equals(pendingState) && !data.optBoolean("pending")) {
                            session = data.optString("session", null);
                            owner = data.optString("account");
                            pendingState = null;
                            pendingCode = null;
                        }
                        success.accept(info());
                    }, error -> {
                        if (state.equals(pendingState)) {
                            pendingState = null;
                            pendingCode = null;
                        }
                        failed.accept(error);
                    });
                }
                case "logout" -> {
                    clearSession();
                    if (activeSession != null) fetch(() -> HubApi.send(activeOrigin, "/auth/logout", activeSession,
                        HubApi.object()), ignored -> {}, ignored -> {});
                    success.accept(info());
                }
                default -> throw new IllegalArgumentException("操作不可用");
            }
        } catch (Exception error) {
            answer(reply, id, null, error);
        }
    }

    private void clearSession() {
        session = null;
        owner = "";
        pendingState = null;
        pendingCode = null;
    }

    private void fetch(Callable<JSONObject> action, Consumer<JSONObject> success, Consumer<Exception> failure) {
        network.execute(() -> {
            try {
                JSONObject result = action.call();
                runOnUiThread(() -> { if (!isDestroyed()) success.accept(result); });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) failure.accept(error); });
            }
        });
    }

    private void openSignInTab(Uri url) throws ActivityNotFoundException {
        Intent service = new Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION);
        ArrayList<String> packages = new ArrayList<>();
        for (ResolveInfo item : getPackageManager().queryIntentServices(service, 0)) {
            packages.add(item.serviceInfo.packageName);
        }
        String provider = CustomTabsClient.getPackageName(this, packages);
        if (provider == null) throw new ActivityNotFoundException("当前设备没有支持安全标签页的浏览器");
        CustomTabsIntent tab = new CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(new CustomTabColorSchemeParams.Builder().setToolbarColor(0xfffffbf1).build())
            .setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_OFF).build();
        tab.intent.setPackage(provider);
        tab.launchUrl(this, url);
    }

    private void answer(JavaScriptReplyProxy channel, int id, JSONObject data, Exception error) {
        if (isDestroyed()) return;
        JSONObject message = error == null ? HubApi.object("id", id, "value", data)
            : HubApi.object("id", id, "error", error.getMessage() == null ? "暂时无法完成操作" : error.getMessage());
        channel.postMessage(message.toString());
    }
}
