package com.example.agent;
import java.util.HashMap;
import java.util.Map;

public class Config {

    // LLM 配置
    private final String defaultModel;
    private final String defaultProvider;
    private final double temperature;
    private final Integer maxTokens;

    // 系统配置
    private final boolean debug;
    private final String logLevel;

    // 其他配置
    private final int maxHistoryLength;

    private Config(Builder builder) {
        this.defaultModel = builder.defaultModel;
        this.defaultProvider = builder.defaultProvider;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.debug = builder.debug;
        this.logLevel = builder.logLevel;
        this.maxHistoryLength = builder.maxHistoryLength;
    }

    // Getters
    public String defaultModel() { return defaultModel; }
    public String defaultProvider() { return defaultProvider; }
    public double temperature() { return temperature; }
    public Integer maxTokens() { return maxTokens; }
    public boolean debug() { return debug; }
    public String logLevel() { return logLevel; }
    public int maxHistoryLength() { return maxHistoryLength; }

    public static Config fromEnv() {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();

        return new Builder()
                .debug("true".equalsIgnoreCase(
                        env.getOrDefault("DEBUG", System.getenv("DEBUG"))))
                .logLevel(env.getOrDefault("LOG_LEVEL",
                        System.getenv().getOrDefault("LOG_LEVEL", "INFO")))
                .temperature(Double.parseDouble(
                        env.getOrDefault("TEMPERATURE",
                                System.getenv().getOrDefault("TEMPERATURE", "0.7"))))
                .maxTokens(tryParseInt(
                        env.getOrDefault("MAX_TOKENS",
                                System.getenv("MAX_TOKENS"))))
                .build();
    }

    public Map<String, Object> toDict() {
        Map<String, Object> dict = new HashMap<>();
        dict.put("default_model", defaultModel);
        dict.put("default_provider", defaultProvider);
        dict.put("temperature", temperature);
        dict.put("max_tokens", maxTokens);
        dict.put("debug", debug);
        dict.put("log_level", logLevel);
        dict.put("max_history_length", maxHistoryLength);
        return dict;
    }

    private static Integer tryParseInt(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static class Builder {
        private String defaultModel = "gpt-3.5-turbo";
        private String defaultProvider = "openai";
        private double temperature = 0.7;
        private Integer maxTokens;
        private boolean debug;
        private String logLevel = "INFO";
        private int maxHistoryLength = 100;

        public Builder defaultModel(String val) { this.defaultModel = val; return this; }
        public Builder defaultProvider(String val) { this.defaultProvider = val; return this; }
        public Builder temperature(double val) { this.temperature = val; return this; }
        public Builder maxTokens(Integer val) { this.maxTokens = val; return this; }
        public Builder debug(boolean val) { this.debug = val; return this; }
        public Builder logLevel(String val) { this.logLevel = val; return this; }
        public Builder maxHistoryLength(int val) { this.maxHistoryLength = val; return this; }

        public Config build() { return new Config(this); }
    }
}
