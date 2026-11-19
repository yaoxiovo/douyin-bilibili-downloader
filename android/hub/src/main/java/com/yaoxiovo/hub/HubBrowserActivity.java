package com.yaoxiovo.hub;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public final class HubBrowserActivity extends Activity {
    private static final int PAPER = 0xfffffbf1;
    private static final int INK = 0xff3e3129;
    private static final int ACCENT = 0xff883c33;
    private static final String HISTORY = "browserHistory";

    private WebView webView;
    private WebView toolbar;
    private TextView error;
    private ProgressBar progress;
    private String lastUrl;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        Uri startingUrl = intent.getData();
        if (!isHttps(startingUrl)) {
            finish();
            return;
        }
        lastUrl = startingUrl.toString();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        try {
            toolbar = createToolbar();
            root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(59)));
        } catch (RuntimeException unavailable) {
            finish();
            return;
        }
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        FrameLayout surface = new FrameLayout(this);
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        error = label("", 15, INK);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(24), dp(16), dp(24), dp(16));
        error.setVisibility(View.GONE);
        try {
            webView = new WebView(this);
        } catch (RuntimeException exception) {
            surface.addView(error, new FrameLayout.LayoutParams(-1, -1));
            showError("此设备暂时无法加载应用内网页");
            return;
        }
        surface.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        surface.addView(error, new FrameLayout.LayoutParams(-1, -1));
        configureWebView();

        Bundle savedHistory = state == null ? null : state.getBundle(HISTORY);
        if (savedHistory == null || webView.restoreState(savedHistory) == null) {
            webView.loadUrl(lastUrl);
        } else if (webView.getUrl() != null) {
            lastUrl = webView.getUrl();
        }
        showAddress(lastUrl);
    }

    private WebView createToolbar() {
        WebView surface = HubUiWebView.create(this, (view, message, origin, mainFrame, reply) -> {
            int id = 0;
            try {
                org.json.JSONObject input = new org.json.JSONObject(message.getData());
                id = input.getInt("id");
                String method = input.getString("method");
                org.json.JSONObject result = HubApi.object();
                switch (method) {
                    case "address" -> result = HubApi.object("host", Uri.parse(lastUrl).getHost());
                    case "back" -> goBack();
                    case "refresh" -> refresh();
                    case "close" -> finish();
                    default -> throw new IllegalArgumentException();
                }
                reply.postMessage(HubApi.object("id", id, "value", result).toString());
            } catch (Exception error) {
                reply.postMessage(HubApi.object("id", id, "error", "浏览器操作不可用").toString());
            }
        });
        HubUiWebView.load(surface, "browser.html");
        return surface;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        // User-tapped links targeting a new tab stay in this browser.
        settings.setSupportMultipleWindows(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (isHttps(request.getUrl())) return false;
                if (request.isForMainFrame()) {
                    Toast.makeText(HubBrowserActivity.this, "仅支持安全的 HTTPS 网页链接", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                super.onPageStarted(view, url, icon);
                if ("about:blank".equals(url)) return;
                lastUrl = url;
                showAddress(url);
                error.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                showAddress(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError problem) {
                if (request.isForMainFrame()) showError("网页加载失败。请检查网络后刷新。");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError problem) {
                handler.cancel();
                if (problem.getUrl().equals(lastUrl)) showError("无法验证此网页的证书。");
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Toast.makeText(HubBrowserActivity.this, "网页已关闭，请重新打开链接", Toast.LENGTH_SHORT).show();
                finish();
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
    }

    private void showAddress(String url) {
        Uri location = Uri.parse(url);
        if (isHttps(location) && toolbar != null) {
            toolbar.evaluateJavascript("window.dispatchEvent(new Event('hub-browser-address'))", null);
        }
    }

    private void showError(String message) {
        if (webView != null) webView.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.INVISIBLE);
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private void goBack() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else finish();
    }

    private void refresh() {
        if (webView == null) return;
        if (error.getVisibility() == View.VISIBLE) webView.loadUrl(lastUrl);
        else webView.reload();
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (webView != null) {
            Bundle history = new Bundle();
            webView.saveState(history);
            state.putBundle(HISTORY, history);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
        if (toolbar != null) {
            ((ViewGroup) toolbar.getParent()).removeView(toolbar);
            toolbar.destroy();
            toolbar = null;
        }
        super.onDestroy();
    }

    static boolean isHttps(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) &&
            uri.getHost() != null && !uri.getHost().isEmpty() && uri.getUserInfo() == null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
