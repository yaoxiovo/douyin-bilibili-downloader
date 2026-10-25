package com.yaoxiovo.downloader.downloader;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.yaoxiovo.downloader.R;
import com.yaoxiovo.downloader.model.DownloadProgress;
import com.yaoxiovo.downloader.model.DownloadRequest;
import com.yaoxiovo.downloader.util.HttpUtil;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native streaming media downloader with Scoped Storage / MediaStore integration,
 * foreground notification updates, and real-time progress callbacks.
 */
public class Downloader {

    private static final String TAG = "Downloader";
    private static final String CHANNEL_ID = "media_download_channel";
    private static final int NOTIFICATION_BASE_ID = 2000;

    private static volatile Downloader instance;

    private final Context appContext;
    private final NotificationManager notificationManager;
    private final ExecutorService executorService;
    private final ConcurrentHashMap<String, DownloadTaskHandle> activeTasks;

    public interface ProgressCallback {
        void onProgress(DownloadProgress progress);
    }

    private static class DownloadTaskHandle {
        final Call networkCall;
        final AtomicBoolean isCanceled;

        DownloadTaskHandle(Call networkCall, AtomicBoolean isCanceled) {
            this.networkCall = networkCall;
            this.isCanceled = isCanceled;
        }
    }

    private Downloader(Context context) {
        this.appContext = context.getApplicationContext();
        this.notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        this.executorService = Executors.newFixedThreadPool(4);
        this.activeTasks = new ConcurrentHashMap<>();
        initNotificationChannel();
    }

    public static Downloader getInstance(Context context) {
        if (instance == null) {
            synchronized (Downloader.class) {
                if (instance == null) {
                    instance = new Downloader(context);
                }
            }
        }
        return instance;
    }

    private void initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "媒体下载服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示抖音/B站视频及图集媒体下载状态与实时进度");
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Start downloading a single video file.
     */
    public String downloadVideo(DownloadRequest request, ProgressCallback callback) {
        final String taskId = request.getId() != null ? request.getId() : UUID.randomUUID().toString();
        final int notificationId = Math.abs(taskId.hashCode() % 10000) + NOTIFICATION_BASE_ID;
        final AtomicBoolean canceled = new AtomicBoolean(false);

        executorService.execute(() -> {
            notifyProgress(callback, new DownloadProgress(
                    taskId, 0, 0, 0, 0,
                    DownloadProgress.STATUS_DOWNLOADING, null, null
            ));
            updateNotification(notificationId, "正在连接下载...", request.getFilename(), 0, false);

            OkHttpClient client = HttpUtil.getClient();
            Request.Builder reqBuilder = new Request.Builder().url(request.getUrl()).get();

            if (request.getHeaders() != null) {
                for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
                    reqBuilder.header(entry.getKey(), entry.getValue());
                }
            }

            Call call = client.newCall(reqBuilder.build());
            activeTasks.put(taskId, new DownloadTaskHandle(call, canceled));

            OutputStream outStream = null;
            Uri mediaUri = null;
            File legacyFile = null;
            String savedLocation = null;

            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("下载请求失败: HTTP " + response.code());
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("下载响应内容为空");
                }

                long totalBytes = body.contentLength();
                String filename = request.getFilename();
                if (filename == null || filename.trim().isEmpty()) {
                    filename = "video_" + System.currentTimeMillis();
                }
                if (!filename.toLowerCase().endsWith(".mp4")) {
                    filename += ".mp4";
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
                    values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DouyinBilibili");
                    values.put(MediaStore.Video.Media.IS_PENDING, 1);

                    mediaUri = appContext.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    if (mediaUri == null) {
                        throw new IOException("无法创建 MediaStore 记录");
                    }
                    outStream = appContext.getContentResolver().openOutputStream(mediaUri);
                    savedLocation = mediaUri.toString();
                } else {
                    File movieDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DouyinBilibili");
                    if (!movieDir.exists()) {
                        movieDir.mkdirs();
                    }
                    legacyFile = new File(movieDir, filename);
                    outStream = new FileOutputStream(legacyFile);
                    savedLocation = legacyFile.getAbsolutePath();
                }

                if (outStream == null) {
                    throw new IOException("无法创建文件输出流");
                }

                byte[] buffer = new byte[8192];
                int bytesRead;
                long currentBytes = 0;
                long lastTime = System.currentTimeMillis();
                long lastBytes = 0;
                long speed = 0;
                long lastNotifyTime = 0;

                try (InputStream inStream = body.byteStream()) {
                    while ((bytesRead = inStream.read(buffer)) != -1) {
                        if (canceled.get()) {
                            throw new IOException("下载任务已取消");
                        }

                        outStream.write(buffer, 0, bytesRead);
                        currentBytes += bytesRead;

                        long now = System.currentTimeMillis();
                        if (now - lastTime >= 1000) {
                            speed = ((currentBytes - lastBytes) * 1000) / (now - lastTime);
                            lastBytes = currentBytes;
                            lastTime = now;
                        }

                        int progress = totalBytes > 0 ? (int) ((currentBytes * 100) / totalBytes) : 0;

                        if (now - lastNotifyTime >= 500) {
                            lastNotifyTime = now;
                            notifyProgress(callback, new DownloadProgress(
                                    taskId, progress, currentBytes, totalBytes, speed,
                                    DownloadProgress.STATUS_DOWNLOADING, savedLocation, null
                            ));
                            updateNotification(notificationId, "正在下载视频 (" + progress + "%)",
                                    formatSize(currentBytes) + " / " + (totalBytes > 0 ? formatSize(totalBytes) : "未知"),
                                    progress, false);
                        }
                    }
                    outStream.flush();
                }

                // Finalize MediaStore pending flag or trigger media scan
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                    ContentValues updateValues = new ContentValues();
                    updateValues.put(MediaStore.Video.Media.IS_PENDING, 0);
                    appContext.getContentResolver().update(mediaUri, updateValues, null, null);
                } else if (legacyFile != null) {
                    MediaScannerConnection.scanFile(appContext,
                            new String[]{legacyFile.getAbsolutePath()},
                            new String[]{"video/mp4"}, null);
                }

                notifyProgress(callback, new DownloadProgress(
                        taskId, 100, currentBytes, totalBytes, 0,
                        DownloadProgress.STATUS_COMPLETED, savedLocation, null
                ));
                updateNotification(notificationId, "下载完成", filename, 100, true);

            } catch (Exception e) {
                Log.e(TAG, "Download error for task " + taskId, e);
                // Clean up incomplete files
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                    try { appContext.getContentResolver().delete(mediaUri, null, null); } catch (Exception ignored) {}
                } else if (legacyFile != null && legacyFile.exists()) {
                    try { legacyFile.delete(); } catch (Exception ignored) {}
                }

                boolean isCanceledTask = canceled.get();
                String status = isCanceledTask ? DownloadProgress.STATUS_CANCELED : DownloadProgress.STATUS_FAILED;
                String errorMsg = isCanceledTask ? "下载已取消" : e.getMessage();

                notifyProgress(callback, new DownloadProgress(
                        taskId, 0, 0, 0, 0, status, null, errorMsg
                ));
                updateNotification(notificationId, isCanceledTask ? "下载已取消" : "下载失败", errorMsg, 0, true);
            } finally {
                activeTasks.remove(taskId);
                if (outStream != null) {
                    try { outStream.close(); } catch (Exception ignored) {}
                }
            }
        });

        return taskId;
    }

    /**
     * Batch download all images in an album note.
     */
    public String downloadImages(DownloadRequest request, ProgressCallback callback) {
        final String taskId = request.getId() != null ? request.getId() : UUID.randomUUID().toString();
        final int notificationId = Math.abs(taskId.hashCode() % 10000) + NOTIFICATION_BASE_ID;
        final AtomicBoolean canceled = new AtomicBoolean(false);

        executorService.execute(() -> {
            List<String> imageUrls = request.getImageUrls();
            if (imageUrls == null || imageUrls.isEmpty()) {
                notifyProgress(callback, new DownloadProgress(
                        taskId, 0, 0, 0, 0, DownloadProgress.STATUS_FAILED, null, "图集图片列表为空"
                ));
                return;
            }

            activeTasks.put(taskId, new DownloadTaskHandle(null, canceled));
            int total = imageUrls.size();
            String prefix = request.getFilename();
            if (prefix == null || prefix.trim().isEmpty()) {
                prefix = "image_album_" + System.currentTimeMillis();
            }

            OkHttpClient client = HttpUtil.getClient();
            int successCount = 0;

            for (int i = 0; i < total; i++) {
                if (canceled.get()) {
                    break;
                }

                String imgUrl = imageUrls.get(i);
                String imgName = prefix + "_" + String.format("%02d", (i + 1)) + ".jpg";

                Request req = new Request.Builder()
                        .url(imgUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "https://www.douyin.com/")
                        .get()
                        .build();

                OutputStream outStream = null;
                Uri imgUri = null;
                File legacyImgFile = null;

                try (Response res = client.newCall(req).execute()) {
                    if (res.isSuccessful() && res.body() != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ContentValues values = new ContentValues();
                            values.put(MediaStore.Images.Media.DISPLAY_NAME, imgName);
                            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DouyinBilibili");
                            values.put(MediaStore.Images.Media.IS_PENDING, 1);

                            imgUri = appContext.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                            if (imgUri != null) {
                                outStream = appContext.getContentResolver().openOutputStream(imgUri);
                            }
                        } else {
                            File picDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DouyinBilibili");
                            if (!picDir.exists()) picDir.mkdirs();
                            legacyImgFile = new File(picDir, imgName);
                            outStream = new FileOutputStream(legacyImgFile);
                        }

                        if (outStream != null) {
                            byte[] buf = new byte[8192];
                            int len;
                            try (InputStream in = res.body().byteStream()) {
                                while ((len = in.read(buf)) != -1) {
                                    outStream.write(buf, 0, len);
                                }
                                outStream.flush();
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imgUri != null) {
                                ContentValues updateVal = new ContentValues();
                                updateVal.put(MediaStore.Images.Media.IS_PENDING, 0);
                                appContext.getContentResolver().update(imgUri, updateVal, null, null);
                            } else if (legacyImgFile != null) {
                                MediaScannerConnection.scanFile(appContext,
                                        new String[]{legacyImgFile.getAbsolutePath()},
                                        new String[]{"image/jpeg"}, null);
                            }
                            successCount++;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed downloading image: " + imgUrl, e);
                } finally {
                    if (outStream != null) {
                        try { outStream.close(); } catch (Exception ignored) {}
                    }
                }

                int progress = (int) (((i + 1) * 100.0) / total);
                notifyProgress(callback, new DownloadProgress(
                        taskId, progress, i + 1, total, 0,
                        DownloadProgress.STATUS_DOWNLOADING, null, null
                ));
                updateNotification(notificationId, "正在下载图集 (" + (i + 1) + "/" + total + ")",
                        imgName, progress, false);
            }

            activeTasks.remove(taskId);

            if (canceled.get()) {
                notifyProgress(callback, new DownloadProgress(
                        taskId, 0, successCount, total, 0,
                        DownloadProgress.STATUS_CANCELED, null, "图集下载已取消"
                ));
                updateNotification(notificationId, "图集下载已取消", "已保存 " + successCount + " 张", 0, true);
            } else {
                notifyProgress(callback, new DownloadProgress(
                        taskId, 100, successCount, total, 0,
                        DownloadProgress.STATUS_COMPLETED, null, null
                ));
                updateNotification(notificationId, "图集下载完成", "共成功保存 " + successCount + " 张图片", 100, true);
            }
        });

        return taskId;
    }

    /**
     * Cancel an ongoing download task.
     */
    public boolean cancelTask(String taskId) {
        if (taskId == null) return false;
        DownloadTaskHandle handle = activeTasks.get(taskId);
        if (handle != null) {
            handle.isCanceled.set(true);
            if (handle.networkCall != null) {
                handle.networkCall.cancel();
            }
            return true;
        }
        return false;
    }

    private void notifyProgress(ProgressCallback callback, DownloadProgress progress) {
        if (callback != null) {
            try {
                callback.onProgress(progress);
            } catch (Exception ignored) {}
        }
    }

    private void updateNotification(int id, String title, String content, int progress, boolean autoCancel) {
        if (notificationManager == null) return;
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setOngoing(!autoCancel)
                    .setAutoCancel(autoCancel)
                    .setPriority(NotificationCompat.PRIORITY_LOW);

            if (!autoCancel && progress >= 0) {
                builder.setProgress(100, progress, false);
            } else {
                builder.setProgress(0, 0, false);
                builder.setSmallIcon(autoCancel ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_sys_download);
            }

            notificationManager.notify(id, builder.build());
        } catch (Exception ignored) {
            // Safe fallback if notifications are blocked
        }
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1048576) {
            return String.format("%.2f MB", bytes / 1048576.0);
        } else if (bytes >= 1024) {
            return String.format("%d KB", bytes / 1024);
        } else {
            return bytes + " B";
        }
    }
}
