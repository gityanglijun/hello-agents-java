package com.example.agent.pattern;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * SimpleAgent 子类，记录工具调用情况。
 * 对齐 Python hello_agents ToolAwareSimpleAgent。
 *
 * 主要特性：
 * - 工具调用监听：通过回调函数记录每次工具调用的详细信息
 * - 参数清理：自动清理和规范化工具参数
 */
public class ToolAwareSimpleAgent extends SimpleAgent {

    private final Consumer<Map<String, Object>> toolCallListener;

    public ToolAwareSimpleAgent(String name, HelloAgentsLLM llm, String systemPrompt,
                                 Consumer<Map<String, Object>> toolCallListener) {
        super(name, llm, systemPrompt);
        this.toolCallListener = toolCallListener;
    }

    public ToolAwareSimpleAgent(String name, HelloAgentsLLM llm, String systemPrompt) {
        this(name, llm, systemPrompt, null);
    }

    // ==================== 工具执行（覆盖，注入监听） ====================

    @Override
    protected String executeToolCall(String toolName, String parameters) {
        ToolRegistry registry = getToolRegistry();
        if (registry == null) {
            String result = "❌ 错误：未配置工具注册表";
            notifyListener(toolName, parameters, Map.of(), result);
            return result;
        }

        try {
            Tool tool = registry.getTool(toolName);
            if (tool == null) {
                String result = "❌ 错误：未找到工具 '" + toolName + "'";
                notifyListener(toolName, parameters, Map.of(), result);
                return result;
            }

            Map<String, Object> parsedParams = parseToolParameters(tool, parameters);
            Map<String, Object> sanitized = sanitizeParameters(parsedParams);
            String result = tool.run(sanitized);
            String formatted = "🔧 工具 " + toolName + " 执行结果：\n" + result;

            notifyListener(toolName, parameters, sanitized, formatted);
            return formatted;

        } catch (Exception e) {
            String result = "❌ 工具调用失败：" + e.getMessage();
            notifyListener(toolName, parameters, Map.of(), result);
            return result;
        }
    }

    // ==================== 监听器通知 ====================

    @SuppressWarnings("unchecked")
    private void notifyListener(String toolName, String rawParameters,
                                 Map<String, Object> parsedParameters, String result) {
        if (toolCallListener == null) return;

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agent", this.name);
            payload.put("tool", toolName);
            payload.put("raw_parameters", rawParameters);
            payload.put("parsed_parameters", parsedParameters);
            payload.put("result", result);
            toolCallListener.accept(payload);
        } catch (Exception ignored) {
            // 防御性兜底，监听器异常不应中断主流程
        }
    }

    // ==================== 参数清理（对齐 Python _sanitize_parameters） ====================

    @SuppressWarnings("unchecked")
    static Map<String, Object> sanitizeParameters(Map<String, Object> params) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (var entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Integer || value instanceof Double || value instanceof Float
                    || value instanceof Boolean || value instanceof java.util.List
                    || value instanceof java.util.Map) {
                sanitized.put(key, value);
                continue;
            }

            if (value instanceof String s) {
                String normalized = s.trim();

                // 去掉外层引号
                if (normalized.length() >= 2) {
                    char first = normalized.charAt(0);
                    char last = normalized.charAt(normalized.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        normalized = normalized.substring(1, normalized.length() - 1).trim();
                    }
                }

                // task_id → int
                if ("task_id".equals(key)) {
                    try {
                        sanitized.put(key, Integer.parseInt(normalized));
                        continue;
                    } catch (NumberFormatException ignored) {}
                }

                // tags → list
                if ("tags".equals(key)) {
                    try {
                        java.util.List<String> parsed = new com.google.gson.Gson()
                                .fromJson(normalized, java.util.List.class);
                        sanitized.put(key, parsed);
                        continue;
                    } catch (Exception ignored) {}
                    // fallback: 逗号分隔
                    String[] parts = normalized.split(",");
                    java.util.List<String> tagList = new java.util.ArrayList<>();
                    for (String p : parts) {
                        String trimmed = p.trim();
                        if (!trimmed.isEmpty()) tagList.add(trimmed);
                    }
                    sanitized.put(key, tagList);
                    continue;
                }

                sanitized.put(key, normalized);
                continue;
            }

            sanitized.put(key, value);
        }
        return sanitized;
    }

    // ==================== 便捷方法 ====================

    /** 将 ToolRegistry 附加到 Agent（对齐 Python attach_registry）。 */
    public static void attachRegistry(ToolAwareSimpleAgent agent, ToolRegistry registry) {
        if (registry != null && !registry.listTools().isEmpty()) {
            for (String toolName : registry.listTools()) {
                var tool = registry.getTool(toolName);
                if (tool != null) {
                    agent.addTool(tool);
                }
            }
        }
    }
}
