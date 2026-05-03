package com.example.agent.llm;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.example.agent.LoadDotenvUtil;

public class MyLLM extends HelloAgentsLLM {

    public String apiKey;
    public String baseUrl;
    public String provider;
    public Float temperature;
    public Integer maxTokens;
    public Integer timeout;

    private final Map<String, Object> extraConfig;

    private MyLLM(Builder builder) {
        super(builder.model, builder.buildClient(), builder.provider);
        this.extraConfig = builder.extraConfig;
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.provider = builder.provider;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.timeout = builder.timeout;
    }

    public static class Builder {
        // === 用户可设置的参数 ===
        private String model;
        private String apiKey;
        private String baseUrl;
        private String provider = "auto";
        private Float temperature = 0.7f;
        public Integer maxTokens;
        private Integer timeout = 60;
        private Map<String, Object> extraConfig = new HashMap<>();

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

        public Builder maxTokens(Integer maxTokens){
            this.maxTokens = maxTokens;
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

            // provider 为 auto 或未手动设置时，自动检测
            if (provider == null || "auto".equals(provider)) {
                String detected = HelloAgentsLLM.autoDetectProvider(apiKey, baseUrl);
                if (!"auto".equals(detected)) {
                    System.out.println("自动检测到 Provider: " + detected);
                    provider = detected;
                }
            }

            if ("modelscope".equals(provider)) {
                // 使用父类的通用凭证解析
                String[] creds = HelloAgentsLLM.resolveCredentials("modelscope", apiKey, baseUrl);
                apiKey = creds[0];
                baseUrl = creds[1];

                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalArgumentException("ModelScope API key not found. " +
                            "Please set MODELSCOPE_API_KEY environment variable.");
                }

                model = (model != null && !model.isBlank())
                        ? model : env.getOrDefault("LLM_MODEL_ID", "Qwen/Qwen2.5-VL-72B-Instruct");

                System.out.println("正在使用自定义的 ModelScope Provider");

            } else {
                // 非 modelscope：复用父类的通用凭证解析
                String[] creds = HelloAgentsLLM.resolveCredentials(provider, apiKey, baseUrl);
                apiKey = creds[0];
                baseUrl = creds[1];
                model = (model != null && !model.isBlank())
                        ? model : env.get("LLM_MODEL_ID");
            }
        }

        private OpenAIClient buildClient() {
            return OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .timeout(Duration.ofSeconds(timeout))
                    .build();
        }
    }
}
