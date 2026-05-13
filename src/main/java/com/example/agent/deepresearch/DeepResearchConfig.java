package com.example.agent.deepresearch;

import com.example.agent.LoadDotenvUtil;
import java.util.Map;

/**
 * 深度研究配置，对应 Python deepresearch/config.py Configuration。
 */
public class DeepResearchConfig {

    private int maxWebResearchLoops = 3;
    private String llmProvider = "deepseek";
    private String searchApi = "hybrid";
    private boolean enableNotes = true;
    private String notesWorkspace = "./notes";
    private boolean fetchFullPage = true;
    private boolean stripThinkingTokens = true;
    private String llmModelId;
    private String llmApiKey;
    private String llmBaseUrl;
    private int serverPort = 8001;

    public static DeepResearchConfig fromEnv() {
        DeepResearchConfig c = new DeepResearchConfig();
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();
        for (var e : System.getenv().entrySet()) {
            env.putIfAbsent(e.getKey(), e.getValue());
        }

        String loops = env.get("MAX_WEB_RESEARCH_LOOPS");
        if (loops != null) c.maxWebResearchLoops = Integer.parseInt(loops);
        c.llmProvider = env.getOrDefault("LLM_PROVIDER", "deepseek");
        c.searchApi = env.getOrDefault("SEARCH_API", "hybrid");
        c.enableNotes = !"false".equalsIgnoreCase(env.get("ENABLE_NOTES"));
        c.notesWorkspace = env.getOrDefault("NOTES_WORKSPACE", "./notes");
        c.fetchFullPage = !"false".equalsIgnoreCase(env.get("FETCH_FULL_PAGE"));
        c.stripThinkingTokens = !"false".equalsIgnoreCase(env.get("STRIP_THINKING_TOKENS"));
        c.llmModelId = env.getOrDefault("LLM_MODEL_ID", System.getenv("LLM_MODEL_ID"));
        c.llmApiKey = env.getOrDefault("LLM_API_KEY", System.getenv("LLM_API_KEY"));
        c.llmBaseUrl = env.getOrDefault("LLM_BASE_URL", System.getenv("LLM_BASE_URL"));
        String port = env.get("DR_SERVER_PORT");
        if (port != null) c.serverPort = Integer.parseInt(port);
        return c;
    }

    // Getters
    public int getMaxWebResearchLoops() { return maxWebResearchLoops; }
    public String getLlmProvider() { return llmProvider; }
    public String getSearchApi() { return searchApi; }
    public void setSearchApi(String api) { this.searchApi = api; }
    public boolean isEnableNotes() { return enableNotes; }
    public String getNotesWorkspace() { return notesWorkspace; }
    public boolean isFetchFullPage() { return fetchFullPage; }
    public boolean isStripThinkingTokens() { return stripThinkingTokens; }
    public String getLlmModelId() { return llmModelId; }
    public String getLlmApiKey() { return llmApiKey; }
    public String getLlmBaseUrl() { return llmBaseUrl; }
    public int getServerPort() { return serverPort; }
}
