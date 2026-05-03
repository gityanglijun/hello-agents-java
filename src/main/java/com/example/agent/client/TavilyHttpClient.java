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
import java.util.Map;

import com.example.agent.LoadDotenvUtil;

public class TavilyHttpClient {

    private static final String API_ENDPOINT = "https://api.tavily.com/search";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static String search(String query) {
        System.out.println("🔍 正在执行 [Tavily] AI搜索: " + query);
        try {
            Map<String, String> env = LoadDotenvUtil.loadEnvFile();
            String apiKey = env.getOrDefault("TAVILY_API_KEY", System.getenv("TAVILY_API_KEY"));
            if (apiKey == null || apiKey.isBlank()) {
                return "错误：TAVILY_API_KEY 未配置。";
            }

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("api_key", apiKey);
            requestBody.addProperty("query", query);
            requestBody.addProperty("search_depth", "basic");
            requestBody.addProperty("include_answer", true);
            requestBody.addProperty("max_results", 3);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Tavily HTTP错误: " + response.statusCode();
            }

            return parseResponse(JsonParser.parseString(response.body()).getAsJsonObject());

        } catch (IOException | InterruptedException e) {
            return "Tavily搜索错误: " + e.getMessage();
        } catch (Exception e) {
            return "Tavily搜索未知错误: " + e.getMessage();
        }
    }

    private static String parseResponse(JsonObject json) {
        StringBuilder sb = new StringBuilder();

        if (json.has("answer") && !json.get("answer").isJsonNull()) {
            String answer = json.get("answer").getAsString();
            if (!answer.isBlank()) {
                sb.append("💡 AI直接答案: ").append(answer).append("\n\n");
            }
        }

        sb.append("🔗 相关结果:\n");
        if (json.has("results") && json.get("results").isJsonArray()) {
            JsonArray results = json.get("results").getAsJsonArray();
            for (int i = 0; i < Math.min(3, results.size()); i++) {
                JsonObject item = results.get(i).getAsJsonObject();
                String title = item.has("title") && !item.get("title").isJsonNull()
                        ? item.get("title").getAsString() : "";
                String content = item.has("content") && !item.get("content").isJsonNull()
                        ? item.get("content").getAsString() : "";
                String url = item.has("url") && !item.get("url").isJsonNull()
                        ? item.get("url").getAsString() : "";

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
        }

        return sb.toString().trim();
    }
}
