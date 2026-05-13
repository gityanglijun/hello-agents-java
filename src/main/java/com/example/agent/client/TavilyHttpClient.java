package com.example.agent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TavilyHttpClient {

    private static final String API_ENDPOINT = "https://api.tavily.com/search";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 文本搜索（兼容旧接口） */
    public static String search(String query) {
        Map<String, String> env = com.example.agent.LoadDotenvUtil.loadEnvFile();
        String apiKey = env.getOrDefault("TAVILY_API_KEY", System.getenv("TAVILY_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            return "错误：TAVILY_API_KEY 未配置。";
        }
        Map<String, Object> result = searchStructured(query, apiKey, 3, false);
        return formatText(result);
    }

    /**
     * 结构化搜索 — 对齐 Python TavilyClient.search()。
     * @return { results: [{title, url, content, raw_content?}], answer }
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> searchStructured(String query, String apiKey,
                                                        int maxResults, boolean includeRawContent) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("api_key", apiKey);
            requestBody.addProperty("query", query);
            requestBody.addProperty("search_depth", "basic");
            requestBody.addProperty("include_answer", true);
            requestBody.addProperty("include_raw_content", includeRawContent);
            requestBody.addProperty("max_results", maxResults);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                result.put("results", List.of());
                result.put("answer", null);
                result.put("error", "Tavily HTTP错误: " + response.statusCode());
                return result;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            // answer
            String answer = null;
            if (json.has("answer") && !json.get("answer").isJsonNull()) {
                answer = json.get("answer").getAsString();
            }
            result.put("answer", answer);

            // results
            List<Map<String, Object>> results = new ArrayList<>();
            if (json.has("results") && json.get("results").isJsonArray()) {
                JsonArray arr = json.get("results").getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject item = arr.get(i).getAsJsonObject();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("title", jsonStr(item, "title", ""));
                    entry.put("url", jsonStr(item, "url", ""));
                    entry.put("content", jsonStr(item, "content", ""));

                    // raw_content (when include_raw_content=true)
                    if (includeRawContent && item.has("raw_content") && !item.get("raw_content").isJsonNull()) {
                        entry.put("raw_content", item.get("raw_content").getAsString());
                    }
                    results.add(entry);
                }
            }
            result.put("results", results);

        } catch (IOException | InterruptedException e) {
            result.put("results", List.of());
            result.put("answer", null);
            result.put("error", "Tavily搜索错误: " + e.getMessage());
        } catch (Exception e) {
            result.put("results", List.of());
            result.put("answer", null);
            result.put("error", "Tavily搜索未知错误: " + e.getMessage());
        }

        return result;
    }

    private static String formatText(Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();
        String answer = (String) result.get("answer");
        if (answer != null && !answer.isBlank()) {
            sb.append("💡 AI直接答案: ").append(answer).append("\n\n");
        }

        sb.append("🔗 相关结果:\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.getOrDefault("results", List.of());
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> item = results.get(i);
            String title = (String) item.getOrDefault("title", "");
            String content = (String) item.getOrDefault("content", "");
            String url = (String) item.getOrDefault("url", "");

            sb.append("[").append(i + 1).append("] ").append(title).append("\n");
            if (!content.isBlank()) {
                sb.append("    ").append(
                        content.length() > 150 ? content.substring(0, 150) + "..." : content
                ).append("\n");
            }
            if (!url.isBlank()) {
                sb.append("    来源: ").append(url).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private static String jsonStr(JsonObject obj, String key, String defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultVal;
    }
}
