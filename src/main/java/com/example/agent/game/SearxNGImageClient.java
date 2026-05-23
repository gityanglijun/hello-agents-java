package com.example.agent.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * SearxNG 图片搜索客户端 — 通过 HTTP 调用 SearxNG 的 JSON API。
 * 免费、无配额限制，聚合 Google/Bing/DuckDuckGo 等多个引擎的图片结果。
 */
public class SearxNGImageClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(java.net.ProxySelector.of(null))  // 绕过系统代理，直连 SearxNG
            .build();

    /**
     * 搜索图片。
     * @param baseUrl  SearxNG 地址，如 http://103.244.89.244:8088
     * @param query    搜索关键词
     * @param maxResults 最大返回数
     * @return [{original, thumbnail, title, source, link}]
     */
    public static List<Map<String, String>> searchImages(String baseUrl, String query, int maxResults) {
        List<Map<String, String>> images = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = baseUrl + "/search?q=" + encoded
                    + "&format=json&categories=images&safesearch=0";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", "GameResearchAgent/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[SearxNG Images] HTTP " + response.statusCode()
                        + " for query='" + query + "'");
                return images;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray results = root.getAsJsonArray("results");
            if (results == null || results.isEmpty()) {
                System.err.println("[SearxNG Images] no results for query='" + query + "'");
                return images;
            }

            // 垃圾引擎黑名单 — 图标库、素材站等跟游戏截图无关的引擎
            Set<String> junkEngines = Set.of("devicons", "lucide", "pinterest",
                    "artic", "material icons", "fontawesome");

            int count = 0;
            for (JsonElement e : results) {
                if (count >= maxResults) break;
                JsonObject item = e.getAsJsonObject();

                // 过滤垃圾引擎
                String engine = jsonStr(item, "engine").toLowerCase();
                if (junkEngines.contains(engine)) continue;

                String imgSrc = jsonStr(item, "img_src");
                if (imgSrc == null || imgSrc.isBlank()) continue;

                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("original", imgSrc);
                entry.put("thumbnail", jsonStr(item, "thumbnail_src"));
                entry.put("title", jsonStr(item, "title"));
                entry.put("source", "searxng");
                entry.put("link", jsonStr(item, "url"));
                images.add(entry);
                count++;
            }

            System.err.println("[SearxNG Images] query='" + query + "' → " + images.size() + " results");

        } catch (Exception e) {
            System.err.println("[SearxNG Images] error for query='" + query + "': " + e.getMessage());
        }
        return images;
    }

    private static String jsonStr(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }
}
