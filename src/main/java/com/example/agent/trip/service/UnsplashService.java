package com.example.agent.trip.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Unsplash 图片搜索服务。
 * 对应 Python backend/app/services/unsplash_service.py。
 */
public class UnsplashService {

    private static final String BASE_URL = "https://api.unsplash.com";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    private final String accessKey;

    public UnsplashService(String accessKey) {
        this.accessKey = accessKey;
    }

    /**
     * 搜索照片。
     * @param query   搜索关键词
     * @param perPage 每页数量
     * @return Unsplash API 返回的 results 数组
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchPhotos(String query, int perPage) {
        try {
            String url = BASE_URL + "/search/photos"
                    + "?query=" + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&per_page=" + perPage;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Client-ID " + accessKey)
                    .header("Accept-Version", "v1")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                Map<String, Object> body = GSON.fromJson(resp.body(),
                        new TypeToken<Map<String, Object>>() {}.getType());
                return (List<Map<String, Object>>) body.getOrDefault("results", List.of());
            }

            System.err.println("[Unsplash] API error " + resp.statusCode() + ": " + resp.body());
            return List.of();
        } catch (Exception e) {
            System.err.println("[Unsplash] Request failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取第一张匹配照片的 URL。
     * @param query 搜索关键词
     * @return 照片 URL 或 null
     */
    @SuppressWarnings("unchecked")
    public String getPhotoUrl(String query) {
        List<Map<String, Object>> photos = searchPhotos(query, 1);
        if (photos.isEmpty()) return null;

        Map<String, Object> photo = photos.get(0);
        Map<String, Object> urls = (Map<String, Object>) photo.get("urls");
        if (urls != null) {
            return (String) urls.getOrDefault("regular", urls.get("small"));
        }
        return null;
    }
}
