package com.yaoxiovo.hub;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
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
    private TextView address;
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
        root.addView(toolbar(), new LinearLayout.LayoutParams(-1, -2));
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

    private LinearLayout toolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.addView(control("‹", "返回上一页", this::goBack));
        address = label("", 14, INK);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);
        address.setSingleLine();
        address.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        address.setGravity(Gravity.CENTER);
        bar.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(control("↻", "刷新网页", this::refresh));
        bar.addView(control("×", "关闭网页", this::finish));
        return bar;
    }

    private TextView control(String glyph, String description, Runnable action) {
        TextView view = label(glyph, 24, ACCENT);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(dp(48));
        view.setMinHeight(dp(48));
        view.setContentDescription(description);
        view.setFocusable(true);
        view.setOnClickListener(ignored -> action.run());
        return view;
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
        if (isHttps(location)) address.setText(location.getHost());
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
