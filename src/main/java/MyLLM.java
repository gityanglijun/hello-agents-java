import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class MyLLM extends HelloAgentsLLM {

    public String apiKey;
    public String baseUrl;
    public String provider;
    public Float temperature;
    private final Map<String, Object> extraConfig;

    private MyLLM(Builder builder) {
        super(builder.resolvedModel, builder.buildClient());
        this.extraConfig = builder.extraConfig;
        this.apiKey = builder.resolvedApiKey;
        this.baseUrl = builder.resolvedBaseUrl;
        this.provider = builder.resolvedProvider;
        this.temperature = builder.temperature;
    }

    public static class Builder {
        // === 用户可设置的参数 ===
        private String model;
        private String apiKey;
        private String baseUrl;
        private String provider = "auto";
        private Float temperature = 0.7f;
        private int timeout = 60;
        private Map<String, Object> extraConfig = new HashMap<>();

        // === 内部解析后的值 ===
        private String resolvedModel;
        private String resolvedApiKey;
        private String resolvedBaseUrl;
        private String resolvedProvider;

        // === Fluent setters ===

        public Builder model(String val) {
            this.model = val;
            return this;
        }

        public Builder apiKey(String val) {
            this.apiKey = val;
            return this;
        }

        public Builder baseUrl(String val) {
            this.baseUrl = val;
            return this;
        }

        public Builder provider(String val) {
            this.provider = val;
            return this;
        }

        public Builder temperature(float val) {
            this.temperature = val;
            return this;
        }

        public Builder timeout(int val) {
            this.timeout = val;
            return this;
        }

        public Builder putExtra(String key, Object val) {
            this.extraConfig.put(key, val);
            return this;
        }

        public Builder extraConfig(Map<String, Object> val) {
            this.extraConfig = val != null ? val : new HashMap<>();
            return this;
        }

        // === 构建 ===

        public MyLLM build() {
            resolveConfig();
            return new MyLLM(this);
        }

        private void resolveConfig() {
            Map<String, String> env = LoadDotenvUtil.loadEnvFile();

            if ("modelscope".equals(provider)) {
                resolvedProvider = "modelscope";

                // 解析 ModelScope 凭证
                resolvedApiKey = (apiKey != null && !apiKey.isBlank())
                        ? apiKey : env.get("MODELSCOPE_API_KEY");
                resolvedBaseUrl = (baseUrl != null && !baseUrl.isBlank())
                        ? baseUrl : "https://api-inference.modelscope.cn/v1/";

                if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
                    throw new IllegalArgumentException("ModelScope API key not found. " +
                            "Please set MODELSCOPE_API_KEY environment variable.");
                }

                resolvedModel = (model != null && !model.isBlank())
                        ? model : env.getOrDefault("LLM_MODEL_ID", "Qwen/Qwen2.5-VL-72B-Instruct");

                System.out.println("正在使用自定义的 ModelScope Provider");

            } else {
                // 非 modelscope：复用父类的默认逻辑
                resolvedProvider = "auto";
                resolvedApiKey = (apiKey != null && !apiKey.isBlank())
                        ? apiKey : env.get("LLM_API_KEY");
                resolvedBaseUrl = (baseUrl != null && !baseUrl.isBlank())
                        ? baseUrl : env.get("LLM_BASE_URL");
                resolvedModel = (model != null && !model.isBlank())
                        ? model : env.get("LLM_MODEL_ID");
            }
        }

        private OpenAIClient buildClient() {
            return OpenAIOkHttpClient.builder()
                    .apiKey(resolvedApiKey)
                    .baseUrl(resolvedBaseUrl)
                    .timeout(Duration.ofSeconds(timeout))
                    .build();
        }
    }
}
