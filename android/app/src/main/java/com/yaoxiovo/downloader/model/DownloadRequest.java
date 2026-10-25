package com.yaoxiovo.downloader.model;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;

/**
 * Model representing download tasks initiated by Web frontend.
 */
public class DownloadRequest {
    private String id;
    private String title;
    private String author;
    private String url;
    private String filename;
    private Map<String, String> headers;
    private boolean isImages;
    private List<String> imageUrls;
    private String platform; // "douyin" or "bilibili"

    public DownloadRequest() {}

    public static DownloadRequest fromJson(String json) {
        return new Gson().fromJson(json, DownloadRequest.class);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public boolean isImages() {
        return isImages;
    }

    public void setImages(boolean images) {
        isImages = images;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }
}
