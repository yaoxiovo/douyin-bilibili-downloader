package com.yaoxiovo.downloader.model;

import com.google.gson.Gson;

/**
 * Real-time download progress model emitted to Web frontend via JS callback.
 */
public class DownloadProgress {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DOWNLOADING = "DOWNLOADING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELED = "CANCELED";

    private String id;
    private int progress;             // 0 - 100
    private long currentBytes;
    private long totalBytes;
    private long speedBytesPerSec;
    private String status;
    private String filePath;
    private String error;

    public DownloadProgress() {}

    public DownloadProgress(String id, int progress, long currentBytes, long totalBytes,
                            long speedBytesPerSec, String status, String filePath, String error) {
        this.id = id;
        this.progress = progress;
        this.currentBytes = currentBytes;
        this.totalBytes = totalBytes;
        this.speedBytesPerSec = speedBytesPerSec;
        this.status = status;
        this.filePath = filePath;
        this.error = error;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public long getCurrentBytes() {
        return currentBytes;
    }

    public void setCurrentBytes(long currentBytes) {
        this.currentBytes = currentBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public long getSpeedBytesPerSec() {
        return speedBytesPerSec;
    }

    public void setSpeedBytesPerSec(long speedBytesPerSec) {
        this.speedBytesPerSec = speedBytesPerSec;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }
}
