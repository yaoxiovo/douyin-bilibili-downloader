package com.yaoxiovo.hub;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.util.Collections;

final class HubUiWebView {
    private static final String OFFLINE_ORIGIN = "https://appassets.androidplatform.net";
    private static final String OFFLINE_HOST = "appassets.androidplatform.net";

    private HubUiWebView() {}

    static WebView create(Context context, WebViewCompat.WebMessageListener listener) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            throw new IllegalStateException("请更新 Android System WebView 后重试。");
        }
        WebView webView = new WebView(context);
        webView.setBackgroundColor(0xfff8f0e1);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        WebViewAssetLoader files = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(context)).build();
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView source, WebResourceRequest request) {
                return true; // The bundled UI sends explicit navigation requests to Android.
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView source, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (OFFLINE_HOST.equals(uri.getHost()) && "https".equals(uri.getScheme())) {
                    WebResourceResponse content = files.shouldInterceptRequest(uri);
                    return content == null ? blocked() : content;
                }
                if (!request.isForMainFrame() && "https".equals(uri.getScheme()) &&
                    "download.yaoxi.wiki".equals(uri.getHost()) && uri.getPort() == -1 &&
                    uri.getPath() != null && uri.getPath().matches("/media/[a-f0-9-]{36}")) {
                    return null;
                }
                return blocked();
            }
        });
        WebViewCompat.addWebMessageListener(webView, "YaoxiNative",
            Collections.singleton(OFFLINE_ORIGIN), (view, message, origin, mainFrame, reply) -> {
                if (mainFrame && OFFLINE_ORIGIN.equals(origin.toString())) {
                    listener.onPostMessage(view, message, origin, true, reply);
                }
            });
        return webView;
    }

    static void load(WebView target, String file) {
        target.loadUrl(OFFLINE_ORIGIN + "/assets/" + file);
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
            Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
}
