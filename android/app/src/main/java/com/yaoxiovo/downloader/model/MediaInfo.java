package com.yaoxiovo.downloader.model;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;

/**
 * Parsed Media Information matching core.js output structure.
 */
public class MediaInfo {
    private String id;
    private String platform; // "douyin" | "bilibili"
    private String type;     // "video" | "images"
    private String title;
    private String author;
    private String authorAvatar;
    private String cover;
    private Long durationMs;
    private String filename;
    private Integer imagesCount;
    private List<String> images;
    private List<PartInfo> parts;
    private Map<String, Object> stats;
    private StreamInfo stream;

    public static class PartInfo {
        private int page;
        private String cid;
        private String title;
        private Long durationMs;

        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public String getCid() { return cid; }
        public void setCid(String cid) { this.cid = cid; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public Long getDurationMs() { return durationMs; }
        public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    }

    public static class StreamInfo {
        private String url;
        private Long totalBytes;
        private String quality;
        private Map<String, String> headers;
        private Integer multiSegment;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(Long totalBytes) { this.totalBytes = totalBytes; }
        public String getQuality() { return quality; }
        public void setQuality(String quality) { this.quality = quality; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public Integer getMultiSegment() { return multiSegment; }
        public void setMultiSegment(Integer multiSegment) { this.multiSegment = multiSegment; }
    }

    public static MediaInfo fromJson(String json) {
        return new Gson().fromJson(json, MediaInfo.class);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getAuthorAvatar() { return authorAvatar; }
    public void setAuthorAvatar(String authorAvatar) { this.authorAvatar = authorAvatar; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Integer getImagesCount() { return imagesCount; }
    public void setImagesCount(Integer imagesCount) { this.imagesCount = imagesCount; }
    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }
    public List<PartInfo> getParts() { return parts; }
    public void setParts(List<PartInfo> parts) { this.parts = parts; }
    public Map<String, Object> getStats() { return stats; }
    public void setStats(Map<String, Object> stats) { this.stats = stats; }
    public StreamInfo getStream() { return stream; }
    public void setStream(StreamInfo stream) { this.stream = stream; }

    public String toJson() {
        return new Gson().toJson(this);
    }
}
