package com.cybertown.config;

/**
 * 应用配置 — 对应 Python config.py 中的 Settings。
 */
public class AppConfig {

    public static final String API_TITLE = "赛博小镇 API";
    public static final String API_VERSION = "1.0.0";
    public static final String API_HOST = "0.0.0.0";
    public static final int API_PORT = 8080;

    /** NPC 状态更新间隔(秒) */
    public static final int NPC_UPDATE_INTERVAL = 30;

    // LLM 配置 — 从环境变量读取
    public static final String LLM_MODEL_ID = env("LLM_MODEL_ID", "Qwen/Qwen2.5-72B-Instruct");
    public static final String LLM_API_KEY = env("LLM_API_KEY", null);
    public static final String LLM_BASE_URL = env("LLM_BASE_URL", "https://api-inference.modelscope.cn/v1/");

    // ==================== 环境变量工具 ====================

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultVal;
    }

    /** 验证配置 */
    public static boolean validate() {
        if (LLM_API_KEY == null || LLM_API_KEY.isBlank()) {
            System.out.println("⚠️  警告: 未设置LLM_API_KEY环境变量");
            System.out.println("   请在.env文件中配置LLM_API_KEY");
            System.out.println("   示例: LLM_API_KEY=\"your-api-key\"");
            return false;
        }
        System.out.println("✅ LLM配置:");
        System.out.println("   模型: " + LLM_MODEL_ID);
        System.out.println("   服务地址: " + LLM_BASE_URL);
        return true;
    }

    private AppConfig() {}
}
