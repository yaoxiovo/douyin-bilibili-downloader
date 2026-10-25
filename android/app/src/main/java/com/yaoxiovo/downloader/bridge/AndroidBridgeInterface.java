package com.yaoxiovo.downloader.bridge;

import android.webkit.JavascriptInterface;

/**
 * Android Web-Native Bridge Contract Interface.
 *
 * Registered in WebView as `window.AndroidBridge`.
 */
public interface AndroidBridgeInterface {

    /**
     * Get Android native client version.
     * @return Version string (e.g., "1.0.0")
     */
    @JavascriptInterface
    String getVersion();

    /**
     * Parse video or album URL.
     * Asynchronous execution: when parsing completes, calls back into JS function.
     *
     * @param inputUrl Raw URL or shared text
     * @param callbackJsFunction JS function name to receive JSON string of BridgeResponse<MediaInfo>
     */
    @JavascriptInterface
    void resolve(String inputUrl, String callbackJsFunction);

    /**
     * Start downloading a media file (single video or part).
     *
     * @param downloadRequestJson JSON string of DownloadRequest
     */
    @JavascriptInterface
    void downloadMedia(String downloadRequestJson);

    /**
     * Batch download all images in an album.
     *
     * @param imagesRequestJson JSON string of DownloadRequest with imageUrls
     */
    @JavascriptInterface
    void downloadAllImages(String imagesRequestJson);

    /**
     * Cancel an active download task.
     *
     * @param taskId Download task id
     */
    @JavascriptInterface
    void cancelDownload(String taskId);

    /**
     * Write text to system clipboard.
     *
     * @param text Text string to copy
     */
    @JavascriptInterface
    void copyToClipboard(String text);

    /**
     * Read text from system clipboard.
     *
     * @return Clipboard text, or empty string
     */
    @JavascriptInterface
    String getClipboardText();

    /**
     * Display a native Android toast.
     *
     * @param message Message to display
     * @param isLong True for Toast.LENGTH_LONG, false for Toast.LENGTH_SHORT
     */
    @JavascriptInterface
    void showToast(String message, boolean isLong);

    /**
     * Open a downloaded file with external default viewer/player.
     *
     * @param filePath Absolute path or content URI
     * @param mimeType MIME type (e.g., "video/mp4", "image/jpeg")
     */
    @JavascriptInterface
    void openFile(String filePath, String mimeType);

    /**
     * Get system download folder path.
     *
     * @return Absolute path of the downloads/media directory
     */
    @JavascriptInterface
    String getDownloadDirectory();

    /**
     * Check if application currently has storage/media permissions.
     *
     * @return true if granted, false otherwise
     */
    @JavascriptInterface
    boolean hasStoragePermission();

    /**
     * Trigger permission request dialog for storage/media permissions.
     */
    @JavascriptInterface
    void requestStoragePermission();
}
