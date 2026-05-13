package com.example.agent.tool;

import com.example.agent.LoadDotenvUtil;
import com.example.agent.client.SerpApiHttpClient;
import com.example.agent.client.TavilyHttpClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索工具 — 多后端、支持结构化/文本双模式返回。
 * 对齐 Python hello_agents SearchTool。
 */
public class SearchTool extends Tool {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final int CHARS_PER_TOKEN = 4;
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final Set<String> SUPPORTED_RETURN_MODES = Set.of("text", "structured", "json", "dict");
    private static final Set<String> SUPPORTED_BACKENDS = Set.of(
            "hybrid", "advanced", "tavily", "serpapi", "duckduckgo", "searxng", "perplexity");

    private final String defaultBackend;
    private final List<String> availableBackends;

    private String tavilyApiKey;
    private String serpapiApiKey;

    public SearchTool(String backendStr) {
        super("search", "智能网页搜索引擎，支持 Tavily、SerpApi、DuckDuckGo 等后端，可返回结构化或文本化的搜索结果。");
        this.defaultBackend = (backendStr != null) ? backendStr.toLowerCase() : "hybrid";
        this.availableBackends = new ArrayList<>();
        setupBackends();
    }

    public SearchTool() {
        this(null);
    }

    // ==================== 初始化 ====================

    private void setupBackends() {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();

        tavilyApiKey = env.getOrDefault("TAVILY_API_KEY", System.getenv("TAVILY_API_KEY"));
        serpapiApiKey = env.getOrDefault("SERPAPI_API_KEY", System.getenv("SERPAPI_API_KEY"));

        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            availableBackends.add("tavily");
            System.out.println("✅ Tavily搜索源已启用");
        } else {
            System.out.println("⚠️ TAVILY_API_KEY 未设置");
        }

        if (serpapiApiKey != null && !serpapiApiKey.isBlank()) {
            availableBackends.add("serpapi");
            System.out.println("✅ SerpApi搜索源已启用");
        } else {
            System.out.println("⚠️ SERPAPI_API_KEY 未设置");
        }

        // DuckDuckGo 免费无需 API key，始终可用
        availableBackends.add("duckduckgo");
        System.out.println("✅ DuckDuckGo搜索源已启用（免费）");

        if ("hybrid".equals(defaultBackend)) {
            if (!availableBackends.isEmpty()) {
                System.out.println("🔧 混合搜索模式已启用，可用后端: " + String.join(", ", availableBackends));
            } else {
                System.out.println("⚠️ 没有可用的 Tavily/SerpApi 搜索源，将回退到通用模式");
            }
        }
    }

    // ==================== 参数定义 ====================

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("input", "string", "搜索查询关键词", true)
        );
    }

    // ==================== run (文本模式，保持 Tool 基类兼容) ====================

    @Override
    public String run(Map<String, Object> parameters) {
        Map<String, Object> params = new LinkedHashMap<>(parameters);
        String mode = paramString(params, "mode", paramString(params, "return_mode", "text"));
        if (!SUPPORTED_RETURN_MODES.contains(mode)) mode = "text";

        Map<String, Object> payload = runStructured(params);

        if ("structured".equals(mode) || "json".equals(mode) || "dict".equals(mode)) {
            return GSON.toJson(payload);
        }

        return formatTextResponse(payload);
    }

    // ==================== runStructured (对齐 Python structured 模式) ====================

    /**
     * 执行搜索并返回结构化结果，格式：
     * { results: [{title, url, content, raw_content?}], backend, answer, notices }
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> runStructured(Map<String, Object> parameters) {
        String query = paramString(parameters, "input", paramString(parameters, "query", ""));
        if (query.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("results", List.of());
            err.put("backend", "none");
            err.put("answer", null);
            err.put("notices", List.of("错误：搜索查询不能为空"));
            return err;
        }

        String backend = paramString(parameters, "backend", this.defaultBackend);
        if (!SUPPORTED_BACKENDS.contains(backend)) backend = "hybrid";

        boolean fetchFullPage = paramBool(parameters, "fetch_full_page", false);
        int maxResults = paramInt(parameters, "max_results", DEFAULT_MAX_RESULTS);
        int maxTokens = paramInt(parameters, "max_tokens_per_source", 2000);
        int loopCount = paramInt(parameters, "loop_count", 0);

        System.out.println("🔍 开始搜索: " + query + " (后端: " + backend + ")");

        return structuredSearch(query, backend, fetchFullPage, maxResults, maxTokens, loopCount);
    }

    // ==================== 结构化搜索调度 ====================

    private Map<String, Object> structuredSearch(String query, String backend,
                                                  boolean fetchFullPage, int maxResults,
                                                  int maxTokens, int loopCount) {
        String target = "hybrid".equals(backend) ? "advanced" : backend;

        switch (target) {
            case "tavily":
                return searchTavily(query, fetchFullPage, maxResults, maxTokens);
            case "serpapi":
                return searchSerpApi(query, fetchFullPage, maxResults, maxTokens);
            case "duckduckgo":
                return searchDuckDuckGo(query, fetchFullPage, maxResults, maxTokens);
            case "advanced":
                return searchAdvanced(query, fetchFullPage, maxResults, maxTokens, loopCount);
            default:
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("results", List.of());
                err.put("backend", backend);
                err.put("answer", null);
                err.put("notices", List.of("不支持的后端: " + backend));
                return err;
        }
    }

    // ==================== Tavily ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> searchTavily(String query, boolean fetchFullPage,
                                              int maxResults, int maxTokens) {
        if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("results", List.of());
            err.put("backend", "tavily");
            err.put("answer", null);
            err.put("notices", List.of("TAVILY_API_KEY 未配置"));
            return err;
        }

        Map<String, Object> tavilyResult = TavilyHttpClient.searchStructured(
                query, tavilyApiKey, maxResults, fetchFullPage);

        List<Map<String, Object>> rawResults = (List<Map<String, Object>>) tavilyResult.getOrDefault("results", List.of());
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < Math.min(maxResults, rawResults.size()); i++) {
            Map<String, Object> item = rawResults.get(i);
            String raw = fetchFullPage ? (String) item.get("raw_content") : null;
            if (raw != null && fetchFullPage) {
                raw = limitText(raw, maxTokens);
            }
            results.add(normalizedResult(
                    str(item.get("title"), str(item.get("url"), "")),
                    str(item.get("url"), ""),
                    str(item.get("content"), ""),
                    raw));
        }

        return structuredPayload(results, "tavily",
                (String) tavilyResult.getOrDefault("answer", null), List.of());
    }

    // ==================== SerpApi ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> searchSerpApi(String query, boolean fetchFullPage,
                                               int maxResults, int maxTokens) {
        if (serpapiApiKey == null || serpapiApiKey.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("results", List.of());
            err.put("backend", "serpapi");
            err.put("answer", null);
            err.put("notices", List.of("SERPAPI_API_KEY 未配置"));
            return err;
        }

        Map<String, Object> serpResult = SerpApiHttpClient.searchStructured(
                query, serpapiApiKey, maxResults);

        String answer = null;
        Map<String, Object> answerBox = (Map<String, Object>) serpResult.get("answer_box");
        if (answerBox != null) {
            answer = str(answerBox.get("answer"), str(answerBox.get("snippet"), null));
        }

        List<Map<String, Object>> rawResults = (List<Map<String, Object>>) serpResult.getOrDefault("organic_results", List.of());
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < Math.min(maxResults, rawResults.size()); i++) {
            Map<String, Object> item = rawResults.get(i);
            String rawContent = str(item.get("snippet"), "");
            if (fetchFullPage) {
                rawContent = limitText(rawContent, maxTokens);
            }
            results.add(normalizedResult(
                    str(item.get("title"), str(item.get("link"), "")),
                    str(item.get("link"), ""),
                    str(item.get("snippet"), ""),
                    rawContent));
        }

        return structuredPayload(results, "serpapi", answer, List.of());
    }

    // ==================== DuckDuckGo ====================

    private Map<String, Object> searchDuckDuckGo(String query, boolean fetchFullPage,
                                                  int maxResults, int maxTokens) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encoded;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            Pattern linkPat = Pattern.compile(
                    "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
            Pattern snippetPat = Pattern.compile(
                    "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);

            Matcher sm = snippetPat.matcher(html);
            List<String> snippets = new ArrayList<>();
            while (sm.find()) snippets.add(sm.group(1).replaceAll("<[^>]+>", "").trim());

            Matcher rm = linkPat.matcher(html);
            int count = 0;
            while (rm.find() && count < maxResults) {
                String href = rm.group(1).trim();
                String title = rm.group(2).replaceAll("<[^>]+>", "").trim();
                if (title.isEmpty()) continue;

                String content = count < snippets.size() ? snippets.get(count) : "";
                String rawContent = content;
                if (fetchFullPage && !href.isBlank()) {
                    rawContent = limitText(content, maxTokens);
                }

                results.add(normalizedResult(title, href, content, rawContent));
                count++;
            }

            if (results.isEmpty()) {
                notices.add("DuckDuckGo 未返回有效结果");
            }

        } catch (Exception e) {
            notices.add("DuckDuckGo 搜索失败: " + e.getMessage());
        }

        return structuredPayload(results, "duckduckgo", null, notices);
    }

    // ==================== Advanced (混合优先) ====================

    private Map<String, Object> searchAdvanced(String query, boolean fetchFullPage,
                                                int maxResults, int maxTokens, int loopCount) {
        List<String> notices = new ArrayList<>();

        // 优先 Tavily
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            try {
                Map<String, Object> tavilyPayload = searchTavily(query, fetchFullPage, maxResults, maxTokens);
                List<?> tavilyResults = (List<?>) tavilyPayload.getOrDefault("results", List.of());
                if (!tavilyResults.isEmpty()) {
                    return tavilyPayload;
                }
                notices.add("⚠️ Tavily 未返回有效结果，尝试其他搜索源");
            } catch (Exception e) {
                notices.add("⚠️ Tavily 搜索失败：" + e.getMessage());
            }
        }

        // 其次 SerpApi
        if (serpapiApiKey != null && !serpapiApiKey.isBlank()) {
            try {
                Map<String, Object> serpPayload = searchSerpApi(query, fetchFullPage, maxResults, maxTokens);
                List<?> serpResults = (List<?>) serpPayload.getOrDefault("results", List.of());
                if (!serpResults.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<String> existingNotices = (List<String>) serpPayload.getOrDefault("notices", List.of());
                    List<String> allNotices = new ArrayList<>(notices);
                    allNotices.addAll(existingNotices);
                    serpPayload.put("notices", allNotices);
                    return serpPayload;
                }
                notices.add("⚠️ SerpApi 未返回有效结果，回退到通用搜索");
            } catch (Exception e) {
                notices.add("⚠️ SerpApi 搜索失败：" + e.getMessage());
            }
        }

        // 最后 DuckDuckGo
        try {
            Map<String, Object> ddgPayload = searchDuckDuckGo(query, fetchFullPage, maxResults, maxTokens);
            @SuppressWarnings("unchecked")
            List<String> existingNotices = (List<String>) ddgPayload.getOrDefault("notices", List.of());
            List<String> allNotices = new ArrayList<>(notices);
            allNotices.addAll(existingNotices);
            ddgPayload.put("notices", allNotices);
            return ddgPayload;
        } catch (Exception e) {
            notices.add("⚠️ DuckDuckGo 搜索失败：" + e.getMessage());
        }

        return structuredPayload(List.of(), "advanced", null, notices);
    }

    // ==================== 格式化：结构化 → 文本 ====================

    private String formatTextResponse(Map<String, Object> payload) {
        String query = str(payload.get("query"), "");
        String backend = str(payload.get("backend"), this.defaultBackend);
        String answer = (String) payload.get("answer");
        @SuppressWarnings("unchecked")
        List<String> notices = (List<String>) payload.getOrDefault("notices", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) payload.getOrDefault("results", List.of());

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 搜索关键词：").append(query).append("\n");
        sb.append("🧭 使用搜索源：").append(backend).append("\n");

        if (answer != null && !answer.isBlank()) {
            sb.append("💡 直接答案：").append(answer).append("\n");
        }

        if (!results.isEmpty()) {
            sb.append("\n📚 参考来源：\n");
            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> item = results.get(i);
                String title = str(item.get("title"), str(item.get("url"), ""));
                sb.append("[").append(i + 1).append("] ").append(title).append("\n");
                String content = (String) item.get("content");
                if (content != null && !content.isBlank()) {
                    sb.append("    ").append(content).append("\n");
                }
                String itemUrl = (String) item.get("url");
                if (itemUrl != null && !itemUrl.isBlank()) {
                    sb.append("    来源: ").append(itemUrl).append("\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("❌ 未找到相关搜索结果。\n");
        }

        if (!notices.isEmpty()) {
            sb.append("⚠️ 注意事项：\n");
            for (String notice : notices) {
                if (notice != null && !notice.isBlank()) {
                    sb.append("- ").append(notice).append("\n");
                }
            }
        }

        return sb.toString().trim();
    }

    // ==================== 结构化工厂方法 ====================

    private static Map<String, Object> normalizedResult(String title, String url,
                                                         String content, String rawContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", (title != null && !title.isBlank()) ? title : url);
        payload.put("url", url != null ? url : "");
        payload.put("content", content != null ? content : "");
        if (rawContent != null) {
            payload.put("raw_content", rawContent);
        }
        return payload;
    }

    private static Map<String, Object> structuredPayload(List<Map<String, Object>> results,
                                                          String backend, String answer,
                                                          List<String> notices) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("results", results);
        payload.put("backend", backend);
        payload.put("answer", answer);
        payload.put("notices", notices != null ? notices : List.of());
        return payload;
    }

    // ==================== 工具方法 ====================

    private static String limitText(String text, int tokenLimit) {
        int charLimit = tokenLimit * CHARS_PER_TOKEN;
        if (text == null) return "";
        if (text.length() <= charLimit) return text;
        return text.substring(0, charLimit) + "... [truncated]";
    }

    private static String paramString(Map<String, Object> params, String key, String defaultVal) {
        Object v = params.get(key);
        if (v instanceof String s && !s.isBlank()) return s;
        return defaultVal;
    }

    private static int paramInt(Map<String, Object> params, String key, int defaultVal) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private static boolean paramBool(Map<String, Object> params, String key, boolean defaultVal) {
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return defaultVal;
    }

    private static String str(Object v, String defaultVal) {
        if (v instanceof String s && !s.isBlank()) return s;
        return defaultVal;
    }
}
