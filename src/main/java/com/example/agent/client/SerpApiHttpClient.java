package com.example.agent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * SerpApi HTTP 客户端 — 对齐 Python SerpApi GoogleSearch。
 */
public class SerpApiHttpClient {

    private static final String API_ENDPOINT = "https://serpapi.com/search";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 文本搜索（兼容旧接口） */
    public static String search(String query) {
        Map<String, String> env = com.example.agent.LoadDotenvUtil.loadEnvFile();
        String apiKey = env.getOrDefault("SERPAPI_API_KEY", System.getenv("SERPAPI_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            return "错误：SERPAPI_API_KEY 未配置。";
        }
        Map<String, Object> result = searchStructured(query, apiKey, 3);
        return formatText(result);
    }

    /**
     * 结构化搜索 — 对齐 Python GoogleSearch(params).get_dict()。
     * @return { organic_results: [{title, link, snippet}], answer_box: {answer, snippet} }
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> searchStructured(String query, String apiKey, int maxResults) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            String url = buildUrl(Map.of(
                    "engine", "google",
                    "q", query,
                    "api_key", apiKey,
                    "gl", "cn",
                    "hl", "zh-cn",
                    "num", String.valueOf(maxResults)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                result.put("organic_results", List.of());
                result.put("answer_box", null);
                result.put("error", "HTTP错误: " + response.statusCode());
                return result;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            // answer_box
            Map<String, Object> answerBox = null;
            if (json.has("answer_box") && !json.get("answer_box").isJsonNull()) {
                JsonObject ab = json.get("answer_box").getAsJsonObject();
                answerBox = new LinkedHashMap<>();
                if (ab.has("answer")) answerBox.put("answer", ab.get("answer").getAsString());
                else if (ab.has("snippet")) answerBox.put("snippet", ab.get("snippet").getAsString());
                if (ab.has("title")) answerBox.put("title", ab.get("title").getAsString());
            }
            result.put("answer_box", answerBox);

            // organic_results (包含 URL!)
            List<Map<String, Object>> organicResults = new ArrayList<>();
            if (json.has("organic_results") && json.get("organic_results").isJsonArray()) {
                JsonArray arr = json.get("organic_results").getAsJsonArray();
                for (int i = 0; i < Math.min(maxResults, arr.size()); i++) {
                    JsonObject item = arr.get(i).getAsJsonObject();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("title", jsonStr(item, "title", ""));
                    entry.put("link", jsonStr(item, "link", ""));
                    entry.put("snippet", jsonStr(item, "snippet", ""));
                    organicResults.add(entry);
                }
            }
            result.put("organic_results", organicResults);

            // knowledge_graph
            if (json.has("knowledge_graph") && !json.get("knowledge_graph").isJsonNull()) {
                JsonObject kg = json.get("knowledge_graph").getAsJsonObject();
                Map<String, Object> kgMap = new LinkedHashMap<>();
                if (kg.has("description")) kgMap.put("description", kg.get("description").getAsString());
                if (kg.has("title")) kgMap.put("title", kg.get("title").getAsString());
                result.put("knowledge_graph", kgMap);
            }

        } catch (IOException | InterruptedException e) {
            result.put("organic_results", List.of());
            result.put("answer_box", null);
            result.put("error", "搜索时发生错误: " + e.getMessage());
        } catch (Exception e) {
            result.put("organic_results", List.of());
            result.put("answer_box", null);
            result.put("error", "搜索时发生未知错误: " + e.getMessage());
        }

        return result;
    }

    /**
     * 图片搜索 — 使用 SerpApi Google Images (engine=google_images)。
     * 自动根据查询语言切换 Google 区域：中文用 gl=cn，其他用 gl=us。
     * @return [{original, thumbnail, title, source, link}]
     */
    public static List<Map<String, String>> searchImages(String query, String apiKey, int maxResults) {
        // 自动检测语言：含中文用 cn，否则用 us（英文/日文等用美国节点结果更多）
        boolean isChinese = query.matches(".*[\\u4e00-\\u9fff]+.*");
        String gl = isChinese ? "cn" : "us";
        String hl = isChinese ? "zh-cn" : "en";

        return searchImagesInternal(query, apiKey, maxResults, gl, hl, true);
    }

    /**
     * 内部图片搜索，尝试指定区域，失败时自动降级重试。
     */
    private static List<Map<String, String>> searchImagesInternal(
            String query, String apiKey, int maxResults,
            String gl, String hl, boolean allowRetry) {

        List<Map<String, String>> images = new ArrayList<>();

        try {
            String url = buildUrl(Map.of(
                    "engine", "google_images",
                    "q", query,
                    "api_key", apiKey,
                    "gl", gl,
                    "hl", hl,
                    "num", String.valueOf(maxResults),
                    "safe", "off"
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[SerpApi Images] HTTP " + response.statusCode()
                        + " for query='" + query + "' gl=" + gl);
                // 降级：换个区域重试一次
                if (allowRetry && !"us".equals(gl)) {
                    return searchImagesInternal(query, apiKey, maxResults, "us", "en", false);
                }
                return images;
            }

            String body = response.body();
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            // SerpApi 可能返回错误信息
            if (json.has("error")) {
                System.err.println("[SerpApi Images] API error for query='" + query
                        + "': " + json.get("error"));
                return images;
            }

            // 检查搜索状态
            if (json.has("search_metadata")) {
                JsonObject meta = json.get("search_metadata").getAsJsonObject();
                String status = jsonStr(meta, "status", "");
                if (!"Success".equals(status)) {
                    System.err.println("[SerpApi Images] search status=" + status
                            + " for query='" + query + "'");
                }
            }

            if (json.has("images_results") && json.get("images_results").isJsonArray()) {
                JsonArray arr = json.get("images_results").getAsJsonArray();
                System.err.println("[SerpApi Images] query='" + query + "' gl=" + gl
                        + " → " + arr.size() + " results");
                for (int i = 0; i < Math.min(maxResults, arr.size()); i++) {
                    JsonObject item = arr.get(i).getAsJsonObject();
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("original", jsonStr(item, "original", ""));
                    entry.put("thumbnail", jsonStr(item, "thumbnail", ""));
                    entry.put("title", jsonStr(item, "title", ""));
                    entry.put("source", jsonStr(item, "source", ""));
                    entry.put("link", jsonStr(item, "link", ""));
                    images.add(entry);
                }
            } else {
                System.err.println("[SerpApi Images] no 'images_results' in response for query='"
                        + query + "' gl=" + gl + ". Keys: " + json.keySet());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[SerpApi Images] network error for query='" + query + "': " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[SerpApi Images] unexpected error for query='" + query + "': " + e.getMessage());
        }

        return images;
    }

    private static String formatText(Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();

        @SuppressWarnings("unchecked")
        Map<String, Object> answerBox = (Map<String, Object>) result.get("answer_box");
        if (answerBox != null) {
            String answer = (String) answerBox.getOrDefault("answer",
                    answerBox.getOrDefault("snippet", ""));
            if (!answer.isBlank()) {
                sb.append("💡 直接答案: ").append(answer).append("\n\n");
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.getOrDefault("organic_results", List.of());
        if (!results.isEmpty()) {
            sb.append("🔗 相关结果:\n");
            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> item = results.get(i);
                sb.append("[").append(i + 1).append("] ")
                        .append(item.getOrDefault("title", "")).append("\n");
                String snippet = (String) item.getOrDefault("snippet", "");
                if (!snippet.isBlank()) {
                    sb.append("    ").append(snippet).append("\n");
                }
                String link = (String) item.getOrDefault("link", "");
                if (!link.isBlank()) {
                    sb.append("    来源: ").append(link).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString().trim();
    }

    // ==================== 工具方法 ====================

    private static String buildUrl(Map<String, String> params) {
        StringBuilder sb = new StringBuilder(API_ENDPOINT);
        sb.append("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) sb.append("&");
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append("=")
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private static String jsonStr(JsonObject obj, String key, String defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultVal;
    }
}
