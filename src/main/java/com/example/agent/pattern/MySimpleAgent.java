package com.example.agent.pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.agent.Config;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.Message;
import com.example.agent.tool.ToolRegistry;

public class MySimpleAgent extends SimpleAgent {

    private static final Pattern TOOL_CALL_PATTERN =
            Pattern.compile("\\[TOOL_CALL:([^:]+):([^\\]]+)\\]");

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
            messages.add(msg.toDict());
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
    protected String getEnhancedSystemPrompt() {
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
        sb.append("例如:`[TOOL_CALL:search:Python编程]` 或 `[TOOL_CALL:memory:recall=用户信息]`\n\n");
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

    private List<ToolCall> parseToolCalls(String text) {
        List<ToolCall> calls = new ArrayList<>();
        Matcher matcher = TOOL_CALL_PATTERN.matcher(text);

        while (matcher.find()) {
            calls.add(new ToolCall(
                    matcher.group(1).trim(),
                    matcher.group(2).trim(),
                    matcher.group(0) // 原始匹配文本
            ));
        }

        return calls;
    }

    private String executeToolCall(String toolName, String parameters) {
        try {
            String result = toolRegistry.executeTool(toolName, parameters);
            return "🔧 工具 " + toolName + " 执行结果:\n" + result;
        } catch (Exception e) {
            return "❌ 工具调用失败:" + e.getMessage();
        }
    }

    // ========== 智能参数解析 ==========

    private Map<String, String> parseToolParameters(String toolName, String parameters) {
        Map<String, String> paramDict = new LinkedHashMap<>();

        if (parameters.contains("=")) {
            if (parameters.contains(",")) {
                // 多个参数: action=search,query=Python,limit=3
                String[] pairs = parameters.split(",");
                for (String pair : pairs) {
                    if (pair.contains("=")) {
                        String[] kv = pair.split("=", 2);
                        paramDict.put(kv[0].trim(), kv[1].trim());
                    }
                }
            } else {
                // 单个参数: key=value
                String[] kv = parameters.split("=", 2);
                paramDict.put(kv[0].trim(), kv[1].trim());
            }
        } else {
            // 无 key=value 格式，根据工具类型推断
            switch (toolName) {
                case "search":
                    paramDict.put("query", parameters);
                    break;
                case "memory":
                    paramDict.put("action", "search");
                    paramDict.put("query", parameters);
                    break;
                default:
                    paramDict.put("input", parameters);
                    break;
            }
        }

        return paramDict;
    }

    // ========== 流式运行 ==========

    public java.util.stream.Stream<String> streamRun(String inputText) {
        System.out.println("🌊 " + name + " 开始流式处理: " + inputText);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", Message.ROLE_SYSTEM, "content", getEnhancedSystemPrompt()));

        for (Message msg : history) {
            messages.add(msg.toDict());
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
