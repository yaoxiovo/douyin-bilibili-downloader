package com.yaoxiovo.downloader.util;

import okhttp3.OkHttpClient;
import java.util.concurrent.TimeUnit;

/**
 * Shared HTTP client and utility methods for network operations.
 */
public final class HttpUtil {

    private static volatile OkHttpClient defaultClient;
    private static volatile OkHttpClient manualRedirectClient;

    private HttpUtil() {
        // Private constructor for utility class
    }

    /**
     * Singleton OkHttpClient with default timeouts and automatic redirect following.
     */
    public static OkHttpClient getClient() {
        if (defaultClient == null) {
            synchronized (HttpUtil.class) {
                if (defaultClient == null) {
                    defaultClient = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return defaultClient;
    }

    /**
     * OkHttpClient configured NOT to automatically follow redirects.
     * Essential for tracking 301/302 short link redirection hops.
     */
    public static OkHttpClient getManualRedirectClient() {
        if (manualRedirectClient == null) {
            synchronized (HttpUtil.class) {
                if (manualRedirectClient == null) {
                    manualRedirectClient = getClient().newBuilder()
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .build();
                }
            }
        }
        return manualRedirectClient;
    }

    /**
     * Sanitize filename by stripping illegal filesystem characters.
     */
    public static String sanitizeFilename(String name) {
        if (name == null) {
            return "";
        }
        String clean = name.replace("#", "")
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.length() > 60) {
            clean = clean.substring(0, 60).trim();
        }
        return clean;
    }

    /**
     * Jitter sleep helper to avoid rate-limiting and anti-crawler triggers.
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Random jitter between 500ms and 1200ms.
     */
    public static void jitter() {
        long delay = 500 + (long) (Math.random() * 700);
        sleep(delay);
    }
}
