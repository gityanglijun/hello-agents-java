package com.example.agent.deepresearch.service;

import com.example.agent.deepresearch.DeepResearchConfig;
import com.example.agent.deepresearch.TextProcessingUtils;
import com.example.agent.tool.SearchTool;

import java.util.*;

/**
 * 搜索调度服务 — 使用 SearchTool 结构化模式，对齐 Python services/search.py。
 */
public class SearchService {

    private static final int MAX_TOKENS_PER_SOURCE = 2000;

    private final SearchTool searchTool;

    public SearchService() {
        this.searchTool = new SearchTool();
    }

    /**
     * 执行搜索，返回统一格式。
     * @return { payload, notices, answerText, backendLabel }
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dispatchSearch(String query, DeepResearchConfig config, int loopCount) {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("input", query);
        params.put("backend", config.getSearchApi());
        params.put("mode", "structured");
        params.put("fetch_full_page", config.isFetchFullPage());
        params.put("max_results", 5);
        params.put("max_tokens_per_source", MAX_TOKENS_PER_SOURCE);
        params.put("loop_count", loopCount);

        try {
            Map<String, Object> payload = searchTool.runStructured(params);

            List<Map<String, Object>> results = (List<Map<String, Object>>) payload.getOrDefault("results", List.of());
            List<String> notices = (List<String>) payload.getOrDefault("notices", List.of());
            String answerText = (String) payload.getOrDefault("answer", "");
            String backendLabel = (String) payload.getOrDefault("backend", config.getSearchApi());

            // 如果没有结构化结果但有 answer，用 answer 构造一个 fallback
            if (results.isEmpty() && answerText != null && !answerText.isBlank()) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("title", "AI 直接答案");
                fallback.put("url", "");
                fallback.put("content", answerText);
                results = List.of(fallback);
            }

            result.put("payload", results);
            result.put("notices", notices);
            result.put("answer_text", answerText != null ? answerText : "");
            result.put("backend_label", backendLabel);

        } catch (Exception e) {
            result.put("notices", List.of("搜索异常: " + e.getMessage()));
            result.put("payload", List.of());
            result.put("answer_text", "");
            result.put("backend_label", config.getSearchApi());
        }

        return result;
    }

    /** 将搜索结果格式化为 LLM 上下文。 */
    @SuppressWarnings("unchecked")
    public String prepareResearchContext(Map<String, Object> searchResult, DeepResearchConfig config) {
        List<Map<String, Object>> payload = (List<Map<String, Object>>) searchResult.getOrDefault("payload", List.of());
        String answerText = (String) searchResult.getOrDefault("answer_text", "");

        // 格式化为简单来源列表
        List<Map<String, String>> simpleSources = new ArrayList<>();
        for (var p : payload) {
            Map<String, String> s = new LinkedHashMap<>();
            s.put("title", (String) p.getOrDefault("title", "无标题"));
            s.put("url", (String) p.getOrDefault("url", ""));
            simpleSources.add(s);
        }
        String sourcesSummary = TextProcessingUtils.formatSources(simpleSources);

        // 去重并格式化完整上下文
        String fullContext = TextProcessingUtils.deduplicateAndFormatSources(
                payload, MAX_TOKENS_PER_SOURCE, config.isFetchFullPage());

        StringBuilder sb = new StringBuilder();
        if (!answerText.isBlank()) {
            sb.append("AI 直接答案:\n").append(answerText).append("\n\n");
        }
        if (!fullContext.isBlank()) {
            sb.append(fullContext);
        }

        return sb.toString();
    }
}
