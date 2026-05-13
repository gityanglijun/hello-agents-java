package com.example.agent.pattern;

import com.example.agent.Agent;
import com.example.agent.Config;
import com.example.agent.Message;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolParameter;
import com.example.agent.tool.ToolRegistry;

import java.util.*;

/**
 * 简单对话 Agent，支持可选的工具调用（基于提示词，非 OpenAI function calling）。
 *
 * 对应 Python hello_agents 的 SimpleAgent。
 *
 * 工具调用方式：
 *   LLM 在回复中输出 [TOOL_CALL:tool_name:parameters]，
 *   Agent 解析该标记 → 执行工具 → 将结果追加到对话 → LLM 继续回答。
 *
 * 与 FunctionCallAgent 的区别：
 *   - SimpleAgent: 提示词方式，兼容任意 LLM
 *   - FunctionCallAgent: 使用 OpenAI 原生 tools API
 */
public class SimpleAgent extends Agent {

    /** 工具调用前缀 */
    private static final String TOOL_CALL_PREFIX = "[TOOL_CALL:";

    private ToolRegistry toolRegistry;
    private boolean enableToolCalling;

    public SimpleAgent(String name, HelloAgentsLLM llm, String systemPrompt, Config config) {
        super(name, llm, systemPrompt, config);
        this.toolRegistry = null;
        this.enableToolCalling = true;
    }

    public SimpleAgent(String name, HelloAgentsLLM llm, String systemPrompt) {
        this(name, llm, systemPrompt, null);
    }

    public SimpleAgent(String name, HelloAgentsLLM llm) {
        this(name, llm, null, null);
    }

    // ==================== 工具支持 ====================

    /**
     * 添加工具到 Agent。自动创建 ToolRegistry（如果还没有）。
     * 如果工具是可展开的（expandable），会自动展开为多个独立工具。
     */
    public void addTool(Tool tool, boolean autoExpand) {
        if (toolRegistry == null) {
            toolRegistry = new ToolRegistry();
            enableToolCalling = true;
        }
        toolRegistry.registerTool(tool);
    }

    public void addTool(Tool tool) {
        addTool(tool, true);
    }

    /** 移除工具。 */
    public boolean removeTool(String toolName) {
        if (toolRegistry != null && toolRegistry.has(toolName)) {
            toolRegistry.unregister(toolName);
            return true;
        }
        return false;
    }

    /** 列出所有可用工具名。 */
    public List<String> listTools() {
        if (toolRegistry != null) {
            return toolRegistry.listTools();
        }
        return List.of();
    }

    /** 是否有可用工具。 */
    public boolean hasTools() {
        return enableToolCalling && toolRegistry != null
                && !toolRegistry.listTools().isEmpty();
    }

    // ==================== 系统提示词 ====================

    /** 构建增强的系统提示词，包含工具信息。仅在启用工具且有可用工具时注入。 */
    protected String getEnhancedSystemPrompt() {
        String basePrompt = systemPrompt != null ? systemPrompt : "你是一个有用的AI助手。";

        if (!hasTools()) {
            return basePrompt;
        }

        String toolsDesc = toolRegistry.describeTools();
        if (toolsDesc == null || toolsDesc.isEmpty() || "暂无可用工具".equals(toolsDesc)) {
            return basePrompt;
        }

        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n## 可用工具\n");
        sb.append("你可以使用以下工具来帮助回答问题：\n");
        sb.append(toolsDesc).append("\n");

        sb.append("\n## 工具调用格式\n");
        sb.append("当需要使用工具时，请使用以下格式：\n");
        sb.append("`[TOOL_CALL:{tool_name}:{parameters}]`\n\n");

        sb.append("### 参数格式说明\n");
        sb.append("1. **JSON格式（推荐）**：使用JSON对象，参数类型准确\n");
        sb.append("   示例：`[TOOL_CALL:note:{\"action\":\"create\",\"title\":\"任务1\"}]`\n\n");
        sb.append("2. **key=value格式**：多个参数用逗号分隔\n");
        sb.append("   示例：`[TOOL_CALL:calculator_multiply:a=12,b=8]`\n\n");
        sb.append("3. **简单传值**：直接写参数值\n");
        sb.append("   示例：`[TOOL_CALL:search:Python编程]`\n\n");

        sb.append("### 重要提示\n");
        sb.append("- 参数名必须与工具定义的参数名完全匹配\n");
        sb.append("- 推荐使用JSON格式传参，可避免特殊字符歧义\n");
        sb.append("- 工具调用结果会自动插入到对话中，然后你可以基于结果继续回答\n");

        return sb.toString();
    }

    // ==================== run ====================

    @Override
    public String run(String inputText) {
        return run(inputText, 3);
    }

    /**
     * 运行 Agent，支持可选的多轮工具调用。
     *
     * @param inputText          用户输入
     * @param maxToolIterations  最大工具调用迭代次数
     */
    public String run(String inputText, int maxToolIterations) {
        System.out.println("🤖 " + name + " 正在处理: " + inputText);

        List<Map<String, String>> messages = buildPromptMessages(inputText);

        // 如果没有工具，直接调用 LLM
        if (!hasTools()) {
            String response = llm.thinkMessages(
                    toAgentMessages(messages));
            if (response != null) {
                addMessage(new Message(inputText, Message.ROLE_USER));
                addMessage(new Message(response, Message.ROLE_ASSISTANT));
            }
            System.out.println("✅ " + name + " 响应完成");
            return response;
        }

        // 带工具调用的迭代循环
        int iteration = 0;
        String finalResponse = "";

        while (iteration < maxToolIterations) {
            String response = llm.thinkMessages(
                    toAgentMessages(messages));

            // 检查是否有工具调用
            List<ToolCallMatch> toolCalls = parseToolCalls(response);

            if (!toolCalls.isEmpty()) {
                // 执行工具调用
                List<String> toolResults = new ArrayList<>();
                String cleanResponse = response;

                for (var call : toolCalls) {
                    String result = executeToolCall(call.toolName, call.parameters);
                    toolResults.add(result);
                    cleanResponse = cleanResponse.replace(call.original, "").trim();
                }

                // 追加 assistant 回复（去除工具调用标记后的内容）
                messages.add(Map.of("role", "assistant", "content", cleanResponse));

                // 追加工具结果
                String toolResultsText = String.join("\n\n", toolResults);
                messages.add(Map.of("role", "user", "content",
                        "工具执行结果：\n" + toolResultsText + "\n\n请基于这些结果给出完整的回答。"));

                iteration++;
                continue;
            }

            // 没有工具调用 → 最终回答
            finalResponse = response;
            break;
        }

        // 超过最大迭代次数，获取最后一次回答
        if (iteration >= maxToolIterations && finalResponse.isEmpty()) {
            finalResponse = llm.thinkMessages(
                    toAgentMessages(messages));
        }

        // 保存历史
        addMessage(new Message(inputText, Message.ROLE_USER));
        addMessage(new Message(finalResponse, Message.ROLE_ASSISTANT));

        System.out.println("✅ " + name + " 响应完成" + (iteration > 0 ? " (工具调用 " + iteration + " 轮)" : ""));
        return finalResponse;
    }

    // ==================== 内部方法 ====================

    /** 构建带系统提示词和历史的 messages（Map 格式）。 */
    private List<Map<String, String>> buildPromptMessages(String inputText) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", getEnhancedSystemPrompt()));

        for (Message msg : history) {
            messages.add(Map.of("role", msg.role(), "content", msg.content()));
        }

        messages.add(Map.of("role", "user", "content", inputText));
        return messages;
    }

    /** 将 Map 格式 messages 转为 Agent Message 列表。 */
    private List<Message> toAgentMessages(List<Map<String, String>> mapMessages) {
        List<Message> result = new ArrayList<>();
        for (var m : mapMessages) {
            String role = m.get("role");
            String content = m.get("content");
            result.add(new Message(content,
                    "system".equals(role) ? Message.ROLE_SYSTEM
                            : "assistant".equals(role) ? Message.ROLE_ASSISTANT
                            : Message.ROLE_USER));
        }
        return result;
    }

    // ==================== 工具调用解析 ====================

    /** 工具调用匹配结果。 */
    private record ToolCallMatch(
            String toolName,
            String parameters,
            String original   // 原始匹配文本，用于从响应中移除
    ) {}

    /**
     * 解析 LLM 回复中的 [TOOL_CALL:name:params] 标记。
     * 使用括号计数法处理 JSON body 中的嵌套 [] 和 {}，兼容 key=value 格式。
     */
    private List<ToolCallMatch> parseToolCalls(String text) {
        List<ToolCallMatch> calls = new ArrayList<>();
        if (text == null) return calls;

        int searchFrom = 0;
        while (true) {
            int start = text.indexOf(TOOL_CALL_PREFIX, searchFrom);
            if (start == -1) break;

            int bodyStart = start + TOOL_CALL_PREFIX.length();
            int colon = text.indexOf(':', bodyStart);
            if (colon == -1) { searchFrom = start + 1; continue; }

            String toolName = text.substring(bodyStart, colon).trim();

            // 括号计数：从 ':' 之后开始扫描，正确处理 JSON 中的 {}、[] 和字符串
            int bodyPos = colon + 1;
            int depth = 0;
            boolean inString = false;
            int bodyEnd = bodyPos;  // fallback: 一直扫描到文本末尾

            for (int i = bodyPos; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (c == '\\') { i++; continue; }  // 跳过转义字符
                    if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') { inString = true; continue; }
                if (c == '{' || c == '[') depth++;
                if (c == '}' || c == ']') {
                    depth--;
                    if (c == ']' && depth < 0) {
                        bodyEnd = i;
                        break;
                    }
                }
            }

            String body = text.substring(bodyPos, bodyEnd).trim();
            String original = text.substring(start, bodyEnd + 1);
            calls.add(new ToolCallMatch(toolName, body, original));
            searchFrom = bodyEnd + 1;
        }
        return calls;
    }

    // ==================== 工具执行 ====================

    /** 执行单个工具调用。 */
    private String executeToolCall(String toolName, String parameters) {
        if (toolRegistry == null) {
            return "❌ 错误：未配置工具注册表";
        }

        try {
            Tool tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                return "❌ 错误：未找到工具 '" + toolName + "'";
            }

            Map<String, Object> paramDict = parseToolParameters(tool, parameters);
            String result = tool.run(paramDict);
            return "🔧 工具 " + toolName + " 执行结果：\n" + result;

        } catch (Exception e) {
            return "❌ 工具调用失败：" + e.getMessage();
        }
    }

    /**
     * 智能解析工具参数。
     * 支持格式：
     *   1. JSON: {"key": "value"}
     *   2. key=value  (单个)
     *   3. key=value,key2=value2  (多个)
     *   4. 直接传值 (简单场景)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolParameters(Tool tool, String parameters) {
        Map<String, Object> paramDict = new LinkedHashMap<>();

        String trimmed = parameters.trim();

        // 1. JSON 格式
        if (trimmed.startsWith("{")) {
            try {
                paramDict = new com.google.gson.Gson().fromJson(trimmed, Map.class);
                return convertParameterTypes(tool, paramDict);
            } catch (Exception e) {
                // JSON 解析失败，继续尝试其他方式
            }
        }

        // 2. key=value 格式（可能逗号分隔多参数）
        if (trimmed.contains("=")) {
            String[] pairs = trimmed.split(",");
            for (String pair : pairs) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    paramDict.put(kv[0].trim(), kv[1].trim());
                }
            }
            return convertParameterTypes(tool, paramDict);
        }

        // 3. 直接传值 → 根据工具推断参数名
        List<ToolParameter> toolParams = tool.getParameters();
        if (!toolParams.isEmpty()) {
            String paramName = toolParams.get(0).name();
            paramDict.put(paramName, trimmed);
        } else {
            paramDict.put("input", trimmed);
        }

        return paramDict;
    }

    /** 根据工具定义的参数类型，转换参数字符串为正确类型。 */
    private Map<String, Object> convertParameterTypes(Tool tool, Map<String, Object> paramDict) {
        List<ToolParameter> toolParams = tool.getParameters();
        Map<String, String> paramTypes = new LinkedHashMap<>();
        for (var p : toolParams) {
            paramTypes.put(p.name(), p.type());
        }

        Map<String, Object> converted = new LinkedHashMap<>();
        for (var entry : paramDict.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String type = paramTypes.get(key);

            if (type != null && value instanceof String s) {
                try {
                    converted.put(key, switch (type) {
                        case "integer" -> Integer.parseInt(s);
                        case "number", "float", "double" -> {
                            double d = Double.parseDouble(s);
                            yield d == (long) d ? (long) d : d;
                        }
                        case "boolean" -> Boolean.parseBoolean(s);
                        default -> s;
                    });
                } catch (NumberFormatException e) {
                    converted.put(key, value);  // 保持原值
                }
            } else {
                converted.put(key, value);
            }
        }
        return converted;
    }
}
