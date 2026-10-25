package com.yaoxiovo.downloader.bridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yaoxiovo.downloader.BuildConfig;
import com.yaoxiovo.downloader.MainActivity;
import com.yaoxiovo.downloader.downloader.Downloader;
import com.yaoxiovo.downloader.model.BridgeResponse;
import com.yaoxiovo.downloader.model.DownloadProgress;
import com.yaoxiovo.downloader.model.DownloadRequest;
import com.yaoxiovo.downloader.model.MediaInfo;
import com.yaoxiovo.downloader.parser.MediaResolver;

import java.io.File;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Concrete AndroidBridge providing Web-to-Native communication.
 */
public class AndroidBridge implements AndroidBridgeInterface {

    private final MainActivity activity;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(3);
    private final Downloader downloader;
    private final Gson gson = new Gson();

    public AndroidBridge(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.downloader = Downloader.getInstance(activity);
    }

    @Override
    @JavascriptInterface
    public String getVersion() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    @JavascriptInterface
    public void resolve(String inputUrl, String callbackJsFunction) {
        executeResolve(inputUrl, callbackJsFunction);
    }

    @JavascriptInterface
    public void resolve(String inputUrl) {
        executeResolve(inputUrl, null);
    }

    private void executeResolve(String inputUrl, String callbackJsFunction) {
        backgroundExecutor.execute(() -> {
            try {
                MediaInfo info = MediaResolver.resolve(inputUrl);
                BridgeResponse<MediaInfo> response = BridgeResponse.success(info);
                String jsonStr = response.toJson();

                evaluateJs(() -> {
                    if (callbackJsFunction != null && !callbackJsFunction.trim().isEmpty()) {
                        webView.evaluateJavascript(callbackJsFunction + "(" + jsonStr + ");", null);
                    }
                    webView.evaluateJavascript(
                            "if (typeof window.onResolveSuccess === 'function') { window.onResolveSuccess(" + jsonStr + "); }",
                            null
                    );
                });
            } catch (Exception e) {
                BridgeResponse<Object> errResponse = BridgeResponse.error(500, e.getMessage());
                String errJson = errResponse.toJson();

                evaluateJs(() -> {
                    if (callbackJsFunction != null && !callbackJsFunction.trim().isEmpty()) {
                        webView.evaluateJavascript(callbackJsFunction + "(" + errJson + ");", null);
                    }
                    webView.evaluateJavascript(
                            "if (typeof window.onResolveError === 'function') { window.onResolveError(" + errJson + "); }",
                            null
                    );
                });
            }
        });
    }

    @Override
    @JavascriptInterface
    public void downloadMedia(String downloadRequestJson) {
        try {
            DownloadRequest request = DownloadRequest.fromJson(downloadRequestJson);
            handleDownloadVideo(request);
        } catch (Exception e) {
            showToast("下载请求解析失败: " + e.getMessage(), true);
        }
    }

    @JavascriptInterface
    public void downloadVideo(String url, String filename, String headersJson) {
        try {
            DownloadRequest req = new DownloadRequest();
            req.setUrl(url);
            req.setFilename(filename);
            req.setImages(false);

            if (headersJson != null && !headersJson.trim().isEmpty()) {
                Type mapType = new TypeToken<Map<String, String>>() {}.getType();
                Map<String, String> headers = gson.fromJson(headersJson, mapType);
                req.setHeaders(headers);
            }

            handleDownloadVideo(req);
        } catch (Exception e) {
            showToast("启动下载失败: " + e.getMessage(), true);
        }
    }

    private void handleDownloadVideo(DownloadRequest request) {
        downloader.downloadVideo(request, progress -> {
            notifyProgress(progress.getId(), progress.getProgress(), progress.getCurrentBytes(), progress.getTotalBytes(), progress.getSpeedBytesPerSec());
            
            // Also dispatch progress object to window.onDownloadProgress if present
            String progressJson = progress.toJson();
            evaluateJs(() -> webView.evaluateJavascript(
                    "if (typeof window.onDownloadProgress === 'function') { window.onDownloadProgress(" + progressJson + "); }",
                    null
            ));

            if (DownloadProgress.STATUS_COMPLETED.equals(progress.getStatus())) {
                notifyComplete(progress.getId(), progress.getFilePath() != null ? progress.getFilePath() : "");
            } else if (DownloadProgress.STATUS_FAILED.equals(progress.getStatus())) {
                notifyError(progress.getId(), 500, progress.getError() != null ? progress.getError() : "下载失败");
            }
        });
        showToast("已加入后台下载队列: " + request.getFilename(), false);
    }

    @Override
    @JavascriptInterface
    public void downloadAllImages(String imagesRequestJson) {
        try {
            DownloadRequest request = DownloadRequest.fromJson(imagesRequestJson);
            handleDownloadImages(request);
        } catch (Exception e) {
            showToast("图集下载请求解析失败: " + e.getMessage(), true);
        }
    }

    @JavascriptInterface
    public void downloadImages(String imagesJson, String prefix) {
        try {
            Type listType = new TypeToken<List<String>>() {}.getType();
            List<String> list = gson.fromJson(imagesJson, listType);

            DownloadRequest req = new DownloadRequest();
            req.setImageUrls(list);
            req.setFilename(prefix);
            req.setImages(true);

            handleDownloadImages(req);
        } catch (Exception e) {
            showToast("启动图集下载失败: " + e.getMessage(), true);
        }
    }

    private void handleDownloadImages(DownloadRequest request) {
        downloader.downloadImages(request, progress -> {
            notifyProgress(progress.getId(), progress.getProgress(), progress.getCurrentBytes(), progress.getTotalBytes(), progress.getSpeedBytesPerSec());
            
            String progressJson = progress.toJson();
            evaluateJs(() -> webView.evaluateJavascript(
                    "if (typeof window.onDownloadProgress === 'function') { window.onDownloadProgress(" + progressJson + "); }",
                    null
            ));

            if (DownloadProgress.STATUS_COMPLETED.equals(progress.getStatus())) {
                notifyComplete(progress.getId(), "图集保存完毕");
            } else if (DownloadProgress.STATUS_FAILED.equals(progress.getStatus())) {
                notifyError(progress.getId(), 500, progress.getError() != null ? progress.getError() : "图集保存失败");
            }
        });
        int count = request.getImageUrls() != null ? request.getImageUrls().size() : 0;
        showToast("已启动图集批量下载 (" + count + " 张)", false);
    }

    @Override
    @JavascriptInterface
    public void cancelDownload(String taskId) {
        boolean canceled = downloader.cancelTask(taskId);
        if (canceled) {
            showToast("已取消下载任务", false);
        }
    }

    @Override
    @JavascriptInterface
    public void copyToClipboard(String text) {
        mainHandler.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    ClipData clip = ClipData.newPlainText("Copied Text", text);
                    cm.setPrimaryClip(clip);
                    Toast.makeText(activity, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    @JavascriptInterface
    public String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                return text != null ? text.toString() : "";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    @JavascriptInterface
    public void showToast(String message, boolean isLong) {
        mainHandler.post(() -> Toast.makeText(activity, message, isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void toast(String msg) {
        showToast(msg, false);
    }

    @Override
    @JavascriptInterface
    public void openFile(String filePath, String mimeType) {
        mainHandler.post(() -> {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    Toast.makeText(activity, "文件不存在", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri uri;
                try {
                    uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ex) {
                    uri = Uri.fromFile(file);
                }
                intent.setDataAndType(uri, mimeType != null ? mimeType : "*/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(Intent.createChooser(intent, "打开文件"));
            } catch (Exception e) {
                Toast.makeText(activity, "打开文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    @JavascriptInterface
    public String getDownloadDirectory() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return dir != null ? dir.getAbsolutePath() : "";
    }

    @Override
    @JavascriptInterface
    public boolean hasStoragePermission() {
        return activity.checkPermissions();
    }

    @Override
    @JavascriptInterface
    public void requestStoragePermission() {
        mainHandler.post(activity::checkPermissions);
    }

    /**
     * Dispatch evaluation of JavaScript safely on the UI thread.
     */
    public void evaluateJs(String script) {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    private void evaluateJs(Runnable runnable) {
        mainHandler.post(runnable);
    }

    /**
     * Notify download progress to Web frontend.
     */
    public void notifyProgress(String taskId, int progress, long currentBytes, long totalBytes, long speedBytesPerSec) {
        String script = String.format("if (window.onDownloadProgress) { window.onDownloadProgress('%s', %d, %d, %d, %d); }",
                taskId, progress, currentBytes, totalBytes, speedBytesPerSec);
        evaluateJs(script);
    }

    /**
     * Notify download completion to Web frontend.
     */
    public void notifyComplete(String taskId, String filePath) {
        String escaped = filePath.replace("\\", "\\\\").replace("'", "\\'");
        String script = String.format("if (window.onDownloadComplete) { window.onDownloadComplete('%s', '%s'); }", taskId, escaped);
        evaluateJs(script);
    }

    /**
     * Notify download failure to Web frontend.
     */
    public void notifyError(String taskId, int errorCode, String errorMessage) {
        String escaped = errorMessage != null ? errorMessage.replace("'", "\\'") : "未知错误";
        String script = String.format("if (window.onDownloadError) { window.onDownloadError('%s', %d, '%s'); }", taskId, errorCode, escaped);
        evaluateJs(script);
    }
}
