package com.example.agent.embedding;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.LoadDotenvUtil;

/**
 * OpenAI 兼容 Embedding API 客户端。
 *
 * 复用 LLM 提供商的 API Key 和 Base URL，调用 /v1/embeddings 端点。
 * 适用于 DeepSeek、OpenAI、OneAPI、AI Gateway 等所有兼容 OpenAI 格式的嵌入服务。
 *
 * 环境变量（复用 HelloAgentsLLM 的配置）：
 *   LLM_API_KEY  — API 密钥
 *   LLM_BASE_URL — 服务地址（自动拼接 /embeddings 路径）
 *   LLM_EMBEDDING_MODEL — 嵌入模型名（默认 text-embedding-3-small）
 */
public class LLMEmbeddingClient {

    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Gson gson = new Gson();
    private final String embeddingsUrl;

    public LLMEmbeddingClient() {
        this(resolveApiKey(), resolveBaseUrl(), resolveModel());
    }

    public LLMEmbeddingClient(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model != null ? model : DEFAULT_MODEL;

        // 去掉尾部斜杠，拼接 /embeddings
        String base = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        this.embeddingsUrl = base + "/embeddings";

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 批量编码 */
    public List<float[]> encode(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        try {
            // OpenAI 兼容格式: {"model": "...", "input": [...]}
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", texts
            );
            String jsonBody = gson.toJson(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingsUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String preview = response.body();
                if (preview.length() > 200) preview = preview.substring(0, 200);
                System.out.println("[LLM-Embed] " + model + " 返回 " + response.statusCode()
                        + ": " + preview);
                return List.of();
            }

            return parseResponse(response.body());

        } catch (Exception e) {
            System.out.println("[LLM-Embed] 调用失败: " + e.getMessage());
            return List.of();
        }
    }

    /** 单文本编码 */
    public float[] encode(String text) {
        List<float[]> results = encode(List.of(text));
        return results.isEmpty() ? null : results.get(0);
    }

    private List<float[]> parseResponse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray data = root.getAsJsonArray("data");
            if (data == null || data.isEmpty()) {
                System.out.println("[LLM-Embed] 响应无 data 字段");
                return List.of();
            }

            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JsonObject item = data.get(i).getAsJsonObject();
                JsonArray embedding = item.getAsJsonArray("embedding");
                float[] vec = new float[embedding.size()];
                for (int j = 0; j < embedding.size(); j++) {
                    vec[j] = embedding.get(j).getAsFloat();
                }
                vectors.add(normalize(vec));
            }

            System.out.println("[LLM-Embed] 嵌入成功: " + model + " → "
                    + vectors.size() + " 个向量, "
                    + (vectors.isEmpty() ? 0 : vectors.get(0).length) + " 维");
            return vectors;

        } catch (Exception e) {
            System.out.println("[LLM-Embed] 解析失败: " + e.getMessage());
            return List.of();
        }
    }

    private static float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        }
        return vec;
    }

    // ==================== 配置解析 ====================

    private static String resolveApiKey() {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();
        String key = env.getOrDefault("LLM_API_KEY", System.getenv("LLM_API_KEY"));
        return key;
    }

    private static String resolveBaseUrl() {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();
        String url = env.getOrDefault("LLM_BASE_URL", System.getenv("LLM_BASE_URL"));
        return url;
    }

    private static String resolveModel() {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();
        String model = env.getOrDefault("LLM_EMBEDDING_MODEL",
                System.getenv("LLM_EMBEDDING_MODEL"));
        return model != null && !model.isBlank() ? model : DEFAULT_MODEL;
    }

    /** 检查 LLM 配置是否可用（API Key + Base URL 已设置） */
    public static boolean isConfigured() {
        String key = resolveApiKey();
        String url = resolveBaseUrl();
        return key != null && !key.isBlank() && url != null && !url.isBlank();
    }

    public String getModel() { return model; }

    /** 连通性测试 */
    public static boolean testConnection() {
        if (!isConfigured()) return false;
        try {
            LLMEmbeddingClient client = new LLMEmbeddingClient();
            float[] vec = client.encode("test");
            return vec != null && vec.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
