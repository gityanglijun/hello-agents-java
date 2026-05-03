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
import java.util.HashMap;
import java.util.Map;

import com.example.agent.LoadDotenvUtil;

public class SerpApiHttpClient {

    private static final String API_ENDPOINT = "https://serpapi.com/search";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 一个基于SerpApi REST API的网页搜索引擎工具。
     * 直接使用Java HttpClient调用API，无需外部SerpApi客户端库。
     */
    public static String search(String query) {
        System.out.println("🔍 正在执行 [SerpApi HttpClient] 网页搜索: " + query);
        try {
            // 从环境变量或.env文件获取API密钥
            Map<String, String> envFile = LoadDotenvUtil.loadEnvFile();
            String apiKey = envFile.getOrDefault("SERPAPI_API_KEY", System.getenv("SERPAPI_API_KEY"));
            if (apiKey == null || apiKey.isBlank()) {
                return "错误：SERPAPI_API_KEY 未在 .env 文件中配置。";
            }

            // 构建查询参数
            Map<String, String> params = new HashMap<>();
            params.put("engine", "google");
            params.put("q", query);
            params.put("api_key", apiKey);
            params.put("gl", "cn");      // 国家代码
            params.put("hl", "zh-cn");   // 语言代码

            // 构建URL
            String url = buildUrlWithParams(params);

            // 创建HTTP请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            // 发送请求
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "HTTP错误: " + response.statusCode() + " - " + response.body();
            }

            // 解析JSON响应
            JsonObject results = JsonParser.parseString(response.body()).getAsJsonObject();

            // 智能解析：优先寻找最直接的答案
            String parsedResult = parseSearchResults(results);
            if (parsedResult != null) {
                return parsedResult;
            }

            return "对不起，没有找到关于 '" + query + "' 的信息。";

        } catch (IOException | InterruptedException e) {
            return "搜索时发生错误: " + e.getMessage();
        } catch (Exception e) {
            return "搜索时发生未知错误: " + e.getMessage();
        }
    }

    private static String buildUrlWithParams(Map<String, String> params) {
        StringBuilder urlBuilder = new StringBuilder(API_ENDPOINT);
        urlBuilder.append("?");

        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                urlBuilder.append("&");
            }
            urlBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                     .append("=")
                     .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }

        return urlBuilder.toString();
    }

    private static String parseSearchResults(JsonObject results) {
        // 1. 检查answer_box_list
        if (results.has("answer_box_list")) {
            JsonElement answerBoxList = results.get("answer_box_list");
            if (answerBoxList.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement item : answerBoxList.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        sb.append(item.getAsString()).append("\n");
                    } else if (item.isJsonObject()) {
                        JsonObject obj = item.getAsJsonObject();
                        // 尝试提取可能的文本字段
                        if (obj.has("answer")) {
                            sb.append(obj.get("answer").getAsString()).append("\n");
                        } else if (obj.has("text")) {
                            sb.append(obj.get("text").getAsString()).append("\n");
                        } else if (obj.has("title")) {
                            sb.append(obj.get("title").getAsString()).append("\n");
                        }
                    }
                }
                String result = sb.toString().trim();
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }

        // 2. 检查answer_box
        if (results.has("answer_box")) {
            JsonElement answerBox = results.get("answer_box");
            if (answerBox.isJsonObject()) {
                JsonObject answerBoxObj = answerBox.getAsJsonObject();
                if (answerBoxObj.has("answer")) {
                    return answerBoxObj.get("answer").getAsString();
                } else if (answerBoxObj.has("text")) {
                    return answerBoxObj.get("text").getAsString();
                } else if (answerBoxObj.has("title")) {
                    return answerBoxObj.get("title").getAsString();
                } else if (answerBoxObj.has("snippet")) {
                    return answerBoxObj.get("snippet").getAsString();
                }
            }
        }

        // 3. 检查knowledge_graph
        if (results.has("knowledge_graph")) {
            JsonElement knowledgeGraph = results.get("knowledge_graph");
            if (knowledgeGraph.isJsonObject()) {
                JsonObject kgObj = knowledgeGraph.getAsJsonObject();
                if (kgObj.has("description")) {
                    return kgObj.get("description").getAsString();
                } else if (kgObj.has("title")) {
                    return kgObj.get("title").getAsString();
                } else if (kgObj.has("text")) {
                    return kgObj.get("text").getAsString();
                }
            }
        }

        // 4. 检查organic_results
        if (results.has("organic_results")) {
            JsonElement organicResults = results.get("organic_results");
            if (organicResults.isJsonArray()) {
                JsonArray organicArray = organicResults.getAsJsonArray();
                if (organicArray.size() > 0) {
                    StringBuilder snippets = new StringBuilder();
                    int limit = Math.min(3, organicArray.size());
                    for (int i = 0; i < limit; i++) {
                        JsonElement result = organicArray.get(i);
                        if (result.isJsonObject()) {
                            JsonObject resultObj = result.getAsJsonObject();
                            String title = resultObj.has("title")
                                ? resultObj.get("title").getAsString()
                                : "";
                            String snippet = resultObj.has("snippet")
                                ? resultObj.get("snippet").getAsString()
                                : "";
                            if (!title.isEmpty() || !snippet.isEmpty()) {
                                snippets.append("[").append(i + 1).append("] ")
                                       .append(title).append("\n").append(snippet)
                                       .append("\n\n");
                            }
                        }
                    }
                    String result = snippets.toString().trim();
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            }
        }

        return null;
    }

    public static void main(String[] args) {
        // 测试方法
        String result = search("Java快速排序算法");
        System.out.println("搜索结果:");
        System.out.println(result);
    }
}