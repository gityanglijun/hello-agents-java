package com.example.agent.pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.agent.Config;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.Message;
import com.example.agent.tool.ToolRegistry;

public class MySimpleAgent extends SimpleAgent {

    private static final String TOOL_CALL_PREFIX_MY = "[TOOL_CALL:";

    private static class ToolCall {
        final String toolName;
        final String parameters;
        final String original;

        ToolCall(String toolName, String parameters, String original) {
            this.toolName = toolName;
            this.parameters = parameters;
            this.original = original;
        }
    }

    private final ToolRegistry toolRegistry;
    private final boolean enableToolCalling;

    public MySimpleAgent(
            String name,
            HelloAgentsLLM llm,
            String systemPrompt,
            Config config,
            ToolRegistry toolRegistry,
            boolean enableToolCalling
    ) {
        super(name, llm, systemPrompt, config);
        this.toolRegistry = toolRegistry;
        this.enableToolCalling = enableToolCalling && toolRegistry != null;
        System.out.println("✅ " + name + " 初始化完成，工具调用: "
                + (this.enableToolCalling ? "启用" : "禁用"));
    }

    public MySimpleAgent(
            String name,
            HelloAgentsLLM llm,
            String systemPrompt,
            Config config
    ) {
        this(name, llm, systemPrompt, config, null, false);
    }

    public MySimpleAgent(
            String name,
            HelloAgentsLLM llm,
            String systemPrompt
    ) {
        this(name, llm, systemPrompt, null, null, false);
    }

    public MySimpleAgent(
            String name,
            HelloAgentsLLM llm
    ) {
        this(name, llm, null, null, null, false);
    }

    @Override
    public String run(String inputText, int maxToolIterations) {
        System.out.println("🤖 " + name + " 正在处理: " + inputText);

        // 构建消息列表
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", Message.ROLE_SYSTEM, "content", getEnhancedSystemPrompt()));

        for (Message msg : history) {
            messages.add(msg.toSimpleDict());
        }
        messages.add(Map.of("role", Message.ROLE_USER, "content", inputText));

        if (!enableToolCalling) {
            // 简单对话
            String response = llm.think(messages);
            addMessage(new Message(inputText, Message.ROLE_USER));
            if (response != null) {
                addMessage(new Message(response, Message.ROLE_ASSISTANT));
            }
            System.out.println("✅ " + name + " 响应完成");
            return response;
        }

        // 多轮工具调用
        return runWithTools(messages, inputText, maxToolIterations);
    }

    @Override
    public String getEnhancedSystemPrompt() {
        String basePrompt = super.getEnhancedSystemPrompt();

        if (!enableToolCalling || toolRegistry == null) {
            return basePrompt;
        }

        String toolsDesc = toolRegistry.describeTools();
        if (toolsDesc == null || toolsDesc.isEmpty() || "暂无可用工具".equals(toolsDesc)) {
            return basePrompt;
        }

        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n## 可用工具\n");
        sb.append("你可以使用以下工具来帮助回答问题:\n");
        sb.append(toolsDesc).append("\n");
        sb.append("\n## 工具调用格式\n");
        sb.append("当需要使用工具时，请使用以下格式:\n");
        sb.append("`[TOOL_CALL:{tool_name}:{parameters}]`\n");
        sb.append("- JSON格式（推荐）: `[TOOL_CALL:note:{\"action\":\"create\",\"title\":\"任务1\"}]`\n");
        sb.append("- key=value格式: `[TOOL_CALL:search:query=Python编程]`\n");
        sb.append("- 简单传值: `[TOOL_CALL:search:Python编程]`\n\n");
        sb.append("工具调用结果会自动插入到对话中，然后你可以基于结果继续回答。\n");

        return sb.toString();
    }

    // ========== 多轮工具调用 ==========

    private String runWithTools(List<Map<String, String>> messages, String inputText, int maxIterations) {
        int currentIteration = 0;
        String finalResponse = null;

        while (currentIteration < maxIterations) {
            String response = llm.think(messages);
            if (response == null) break;

            List<ToolCall> toolCalls = parseToolCalls(response);

            if (!toolCalls.isEmpty()) {
                System.out.println("🔧 检测到 " + toolCalls.size() + " 个工具调用");

                // 执行所有工具调用
                List<String> toolResults = new ArrayList<>();
                String cleanResponse = response;

                for (ToolCall call : toolCalls) {
                    String result = executeToolCall(call.toolName, call.parameters);
                    toolResults.add(result);
                    cleanResponse = cleanResponse.replace(call.original, "");
                }

                // 添加 assistant 响应（已清除工具调用标记）
                messages.add(Map.of("role", Message.ROLE_ASSISTANT, "content", cleanResponse));

                // 添加工具结果消息
                String toolResultsText = String.join("\n\n", toolResults);
                messages.add(Map.of("role", Message.ROLE_USER,
                        "content", "工具执行结果:\n" + toolResultsText + "\n\n请基于这些结果给出完整的回答。"));

                currentIteration++;
                continue;
            }

            // 没有工具调用，这是最终回答
            finalResponse = response;
            break;
        }

        // 超过最大迭代次数且无最终结果，获取最后一次回答
        if (currentIteration >= maxIterations && finalResponse == null) {
            finalResponse = llm.think(messages);
        }

        // 保存到历史记录
        addMessage(new Message(inputText, Message.ROLE_USER));
        if (finalResponse != null) {
            addMessage(new Message(finalResponse, Message.ROLE_ASSISTANT));
        }

        System.out.println("✅ " + name + " 响应完成");
        return finalResponse;
    }

    // ========== 工具调用解析 ==========

    /** 解析 LLM 回复中的 [TOOL_CALL:name:params] 标记，支持 JSON body 嵌套括号。 */
    private List<ToolCall> parseToolCalls(String text) {
        List<ToolCall> calls = new ArrayList<>();
        if (text == null) return calls;

        int searchFrom = 0;
        while (true) {
            int start = text.indexOf(TOOL_CALL_PREFIX_MY, searchFrom);
            if (start == -1) break;

            int bodyStart = start + TOOL_CALL_PREFIX_MY.length();
            int colon = text.indexOf(':', bodyStart);
            if (colon == -1) { searchFrom = start + 1; continue; }

            String toolName = text.substring(bodyStart, colon).trim();

            int bodyPos = colon + 1;
            int depth = 0;
            boolean inString = false;
            int bodyEnd = bodyPos;

            for (int i = bodyPos; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (c == '\\') { i++; continue; }
                    if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') { inString = true; continue; }
                if (c == '{' || c == '[') depth++;
                if (c == '}' || c == ']') {
                    depth--;
                    if (c == ']' && depth < 0) { bodyEnd = i; break; }
                }
            }

            String body = text.substring(bodyPos, bodyEnd).trim();
            String original = text.substring(start, bodyEnd + 1);
            calls.add(new ToolCall(toolName, body, original));
            searchFrom = bodyEnd + 1;
        }
        return calls;
    }

    @Override
    protected String executeToolCall(String toolName, String parameters) {
        try {
            String result = toolRegistry.executeTool(toolName, parameters);
            return "🔧 工具 " + toolName + " 执行结果:\n" + result;
        } catch (Exception e) {
            return "❌ 工具调用失败:" + e.getMessage();
        }
    }

    // ========== 智能参数解析 ==========

    @SuppressWarnings("unchecked")
    private Map<String, String> parseToolParameters(String toolName, String parameters) {
        Map<String, String> paramDict = new LinkedHashMap<>();

        String trimmed = parameters.trim();

        // 1. JSON 格式
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> parsed = new com.google.gson.Gson().fromJson(trimmed, Map.class);
                for (var entry : parsed.entrySet()) {
                    paramDict.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
                return paramDict;
            } catch (Exception e) { /* fall through */ }
        }

        // 2. key=value 格式
        if (trimmed.contains("=")) {
            if (trimmed.contains(",")) {
                String[] pairs = trimmed.split(",");
                for (String pair : pairs) {
                    if (pair.contains("=")) {
                        String[] kv = pair.split("=", 2);
                        paramDict.put(kv[0].trim(), kv[1].trim());
                    }
                }
            } else {
                String[] kv = trimmed.split("=", 2);
                paramDict.put(kv[0].trim(), kv[1].trim());
            }
            return paramDict;
        }

        // 3. 简单传值
        switch (toolName) {
            case "search":
                paramDict.put("query", trimmed);
                break;
            case "memory":
                paramDict.put("action", "search");
                paramDict.put("query", trimmed);
                break;
            default:
                paramDict.put("input", trimmed);
                break;
        }

        return paramDict;
    }

    // ========== 流式运行 ==========

    public java.util.stream.Stream<String> streamRun(String inputText) {
        System.out.println("🌊 " + name + " 开始流式处理: " + inputText);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", Message.ROLE_SYSTEM, "content", getEnhancedSystemPrompt()));

        for (Message msg : history) {
            messages.add(msg.toSimpleDict());
        }
        messages.add(Map.of("role", Message.ROLE_USER, "content", inputText));

        StringBuilder fullResponse = new StringBuilder();
        System.out.print("📝 实时响应: ");

        java.util.stream.Stream<String> chunkStream = llm.streamThink(messages);
        java.util.stream.Stream<String> wrapped = chunkStream.map(chunk -> {
            fullResponse.append(chunk);
            System.out.print(chunk);
            System.out.flush();
            return chunk;
        });

        // Stream 终止时保存历史
        return wrapped.onClose(() -> {
            System.out.println();
            addMessage(new Message(inputText, Message.ROLE_USER));
            addMessage(new Message(fullResponse.toString(), Message.ROLE_ASSISTANT));
            System.out.println("✅ " + name + " 流式响应完成");
        });
    }

    // ========== 工具管理 ==========

    public void addTool(String name, String description, java.lang.reflect.Method method) {
        if (toolRegistry == null) {
            // ToolRegistry 已通过构造传入，此处不做延迟初始化
            System.out.println("⚠️ 工具注册表不可用，无法添加工具");
            return;
        }
        toolRegistry.register(name, description, method);
        System.out.println("🔧 工具 '" + name + "' 已添加");
    }

    public boolean hasTools() {
        return enableToolCalling && toolRegistry != null;
    }

    public boolean removeTool(String toolName) {
        if (toolRegistry != null) {
            toolRegistry.unregister(toolName);
            return true;
        }
        return false;
    }

    public List<String> listTools() {
        if (toolRegistry != null) {
            return toolRegistry.listTools();
        }
        return new ArrayList<>();
    }
}
