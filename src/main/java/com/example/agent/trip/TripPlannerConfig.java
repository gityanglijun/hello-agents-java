package com.example.agent.trip;

import com.example.agent.LoadDotenvUtil;

import java.util.List;
import java.util.Map;

/**
 * 旅行助手配置管理。
 * 对应 Python backend/app/config.py。
 *
 * 加载顺序: 指定文件 → ENV_FILE 环境变量 → .env → 系统环境变量 → 默认值
 */
public class TripPlannerConfig {

    private final String host;
    private final int port;
    private final List<String> corsOrigins;
    private final String amapApiKey;
    private final String unsplashAccessKey;
    private String modelName;

    private TripPlannerConfig(Map<String, String> env) {
        this.host = env.getOrDefault("SERVER_HOST", "0.0.0.0");
        this.port = Integer.parseInt(env.getOrDefault("SERVER_PORT", "8000"));
        this.corsOrigins = parseCorsOrigins(env.getOrDefault("CORS_ORIGINS", "*"));
        this.amapApiKey = env.getOrDefault("AMAP_API_KEY",
                env.getOrDefault("AMAP_MAPS_API_KEY", ""));
        this.unsplashAccessKey = env.getOrDefault("UNSPLASH_ACCESS_KEY", "");
    }

    /** 加载配置：先 .env.example 再 .env，.env 中的值覆盖 .env.example */
    public static TripPlannerConfig load() {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();

        // 未在文件中的 key，从系统环境变量补充
        for (Map.Entry<String, String> sysEnv : System.getenv().entrySet()) {
            env.putIfAbsent(sysEnv.getKey(), sysEnv.getValue());
        }

        System.out.println("📋 加载配置...");
        TripPlannerConfig config = new TripPlannerConfig(env);

        System.out.println("   HOST: " + config.host);
        System.out.println("   PORT: " + config.port);
        System.out.println("   CORS: " + String.join(", ", config.corsOrigins));
        System.out.println("   AMAP_KEY: " + (config.amapApiKey.isEmpty() ? "未设置" : "***"));
        System.out.println("   UNSPLASH_KEY: " + (config.unsplashAccessKey.isEmpty() ? "未设置" : "***"));

        return config;
    }

    private static List<String> parseCorsOrigins(String raw) {
        if (raw == null || raw.isBlank() || "*".equals(raw.trim())) {
            return List.of("*");
        }
        return List.of(raw.trim().split("\\s*,\\s*"));
    }

    // ==================== getters ====================

    public String getHost() { return host; }
    public int getPort() { return port; }
    public List<String> getCorsOrigins() { return corsOrigins; }
    public String getAmapApiKey() { return amapApiKey; }
    public String getUnsplashAccessKey() { return unsplashAccessKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
}
