import com.google.gson.Gson;
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

/**
 * 百炼（阿里云 DashScope）文本嵌入 API 客户端。
 *
 * 文档: https://help.aliyun.com/zh/model-studio/text-embedding-api
 */
public class BailianEmbeddingClient {

    private static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final String DEFAULT_MODEL = "text-embedding-v2";

    private final HttpClient httpClient;
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final Gson gson = new Gson();

    public BailianEmbeddingClient() {
        this(resolveApiKey(), DEFAULT_ENDPOINT, DEFAULT_MODEL);
    }

    public BailianEmbeddingClient(String apiKey, String endpoint, String model) {
        this.apiKey = apiKey;
        this.endpoint = endpoint != null ? endpoint : DEFAULT_ENDPOINT;
        this.model = model != null ? model : DEFAULT_MODEL;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 批量编码文本，返回向量列表 */
    public List<float[]> encode(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        try {
            // 构建请求体
            Map<String, Object> input = Map.of("texts", texts);
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", input
            );
            String jsonBody = gson.toJson(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.out.println("[Bailian] API 返回 " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(200, response.body().length())));
                return List.of();
            }

            return parseResponse(response.body(), texts.size());

        } catch (Exception e) {
            System.out.println("[Bailian] API 调用失败: " + e.getMessage());
            return List.of();
        }
    }

    /** 单文本编码 */
    public float[] encode(String text) {
        List<float[]> results = encode(List.of(text));
        return results.isEmpty() ? null : results.get(0);
    }

    private List<float[]> parseResponse(String body, int expectedCount) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject output = root.getAsJsonObject("output");
            if (output == null) {
                System.out.println("[Bailian] 响应中无 output 字段");
                return List.of();
            }

            var embeddings = output.getAsJsonArray("embeddings");
            List<float[]> vectors = new ArrayList<>();

            for (int i = 0; i < embeddings.size(); i++) {
                JsonObject emb = embeddings.get(i).getAsJsonObject();
                var values = emb.getAsJsonArray("embedding");
                float[] vec = new float[values.size()];
                for (int j = 0; j < values.size(); j++) {
                    vec[j] = values.get(j).getAsFloat();
                }
                vectors.add(normalize(vec));
            }

            System.out.println("[Bailian] 嵌入成功: " + vectors.size() + " 个向量, "
                    + (vectors.isEmpty() ? 0 : vectors.get(0).length) + " 维");
            return vectors;

        } catch (Exception e) {
            System.out.println("[Bailian] 响应解析失败: " + e.getMessage());
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

    private static String resolveApiKey() {
        // 尝试从环境变量 / .env 读取
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();
        String key = env.getOrDefault("BAILIAN_API_KEY", System.getenv("BAILIAN_API_KEY"));
        if (key == null || key.isBlank()) {
            key = env.getOrDefault("DASHSCOPE_API_KEY", System.getenv("DASHSCOPE_API_KEY"));
        }
        return key;
    }

    /** 检查 API Key 是否已配置 */
    public static boolean isConfigured() {
        String key = resolveApiKey();
        return key != null && !key.isBlank();
    }
}
