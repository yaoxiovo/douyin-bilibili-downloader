package com.yaoxiovo.hub;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class HubApi {
    static JSONObject publicRepositories(int offset) throws Exception {
        URI url = URI.create("https://api.github.com/users/yaoxiovo/repos?per_page=100&page=" + (offset / 100 + 1));
        JSONArray source = new JSONArray(request(url, null, null));
        JSONArray items = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject repo = source.getJSONObject(i);
            items.put(object("name", repo.getString("name"), "url", repo.getString("html_url")));
        }
        return object("items", items, "more", source.length() == 100);
    }

    static JSONObject publicMessages() throws Exception {
        URI url = URI.create("https://api.github.com/repos/yaoxiovo/douyin-bilibili-downloader/actions/runs?per_page=10");
        JSONArray source = new JSONObject(request(url, null, null)).getJSONArray("workflow_runs");
        JSONArray items = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject run = source.getJSONObject(i);
            if ("failure".equals(run.optString("conclusion"))) {
                items.put(object("title", run.getString("name") + " · 构建失败", "detail",
                    run.optString("display_title"), "url", run.getString("html_url"), "time", run.getString("updated_at")));
            }
        }
        return object("items", items);
    }

    static String validateOrigin(String input) {
        URI uri = URI.create(input.trim());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null ||
                uri.getQuery() != null || uri.getFragment() != null ||
                (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("请输入完整 HTTPS 服务地址");
        }
        return "https://" + uri.getRawAuthority();
    }

    static JSONObject send(String origin, String path, String token, JSONObject data) throws Exception {
        if (origin.isEmpty()) throw new IOException("请先在账号统一中心配置 Hub API 地址");
        return new JSONObject(request(URI.create(origin + path), token, data.toString()));
    }

    static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        } catch (JSONException exception) {
            throw new IllegalArgumentException(exception);
        }
        return result;
    }

    private static String request(URI uri, String token, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(25_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "YaoXi-Hub-Android");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (java.io.OutputStream stream = connection.getOutputStream()) {
                    stream.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            boolean success = status >= 200 && status < 300;
            InputStream input = success ? connection.getInputStream() : connection.getErrorStream();
            String text = input == null ? "" : read(input);
            if (!success) {
                String message = "接口 HTTP " + status;
                try { message = new JSONObject(text).optString("error", message); } catch (JSONException ignored) {}
                throw new IOException(message);
            }
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int length; (length = source.read(buffer)) != -1; ) {
                bytes.write(buffer, 0, length);
                if (bytes.size() > 4_000_000) throw new IOException("接口返回内容过大");
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
