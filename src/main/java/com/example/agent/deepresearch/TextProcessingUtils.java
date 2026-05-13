package com.example.agent.deepresearch;

import java.util.*;

/**
 * 文本处理工具，对应 Python utils.py。
 */
public final class TextProcessingUtils {

    private static final int CHARS_PER_TOKEN = 4;

    /** 移除 LLM 回复中的 &lt;think&gt;...&lt;/think&gt; 段落（支持嵌套）。 */
    public static String stripThinkingTokens(String text) {
        if (text == null || text.isBlank()) return text != null ? text : "";

        StringBuilder result = new StringBuilder();
        int depth = 0;
        int lastEnd = 0;

        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("<think>", i)) {
                if (depth == 0) {
                    result.append(text, lastEnd, i);
                }
                depth++;
                i += 7;
            } else if (text.startsWith("</think>", i)) {
                depth--;
                if (depth == 0) {
                    lastEnd = i + 8;
                }
                i += 8;
            } else {
                i++;
            }
        }

        if (depth == 0 && lastEnd > 0) {
            result.append(text, lastEnd, text.length());
        } else if (depth == 0 && lastEnd == 0) {
            return text; // no thinking tokens found
        }

        return result.toString().trim();
    }

    /** 移除 LLM 回复中的 [TOOL_CALL:...] 标记。 */
    public static String stripToolCalls(String text) {
        if (text == null || text.isBlank()) return text != null ? text : "";
        return text.replaceAll("\\[TOOL_CALL:[^\\]]*\\]", "").trim();
    }

    /** 将搜索结果格式化为简要来源列表。 */
    public static String formatSources(List<Map<String, String>> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var r : searchResults) {
            String title = r.getOrDefault("title", "");
            String url = r.getOrDefault("url", "");
            sb.append("* ").append(title);
            if (!url.isBlank()) sb.append(" : ").append(url);
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 对搜索结果去重并按来源格式化，适用于 LLM 上下文。对齐 Python deduplicate_and_format_sources。 */
    public static String deduplicateAndFormatSources(List<Map<String, Object>> searchResponse,
                                                      int maxTokensPerSource,
                                                      boolean fetchFullPage) {
        if (searchResponse == null || searchResponse.isEmpty()) return "";

        // 用 URL 去重，没有 URL 的条目直接跳过（对齐 Python: if not url: continue）
        Map<String, Map<String, Object>> uniqueSources = new LinkedHashMap<>();
        for (var source : searchResponse) {
            String url = (String) source.get("url");
            if (url == null || url.isBlank()) continue;
            if (!uniqueSources.containsKey(url)) {
                uniqueSources.put(url, source);
            }
        }

        int maxChars = maxTokensPerSource * CHARS_PER_TOKEN;
        StringBuilder sb = new StringBuilder();
        for (var source : uniqueSources.values()) {
            String title = (String) source.getOrDefault("title",
                    source.getOrDefault("url", ""));
            String url = (String) source.getOrDefault("url", "");
            String content = (String) source.getOrDefault("content", "");

            sb.append("信息来源: ").append(title).append("\n\n");
            sb.append("URL: ").append(url).append("\n\n");
            // content 不截断（对齐 Python）
            if (!content.isBlank()) {
                sb.append("信息内容: ").append(content).append("\n\n");
            }

            // 仅 fetchFullPage 时截断 raw_content
            if (fetchFullPage) {
                String rawContent = (String) source.getOrDefault("raw_content", "");
                if (!rawContent.isBlank()) {
                    String rawTruncated = rawContent.length() > maxChars
                            ? rawContent.substring(0, maxChars) + "... [truncated]" : rawContent;
                    sb.append("详细信息内容限制为 ").append(maxTokensPerSource)
                      .append(" 个 token: ").append(rawTruncated).append("\n\n");
                }
            }
        }
        return sb.toString().trim();
    }
}
