package com.example.agent.pattern;

import com.example.agent.Agent;
import com.example.agent.Config;
import com.example.agent.Message;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.ToolRegistry;

import java.util.*;

/**
 * 基于 OpenAI function calling 的 Agentic 智能体。
 *
 * 核心循环:
 * <pre>
 *   user input → 组装 messages + tool schemas
 *     └─ loop:
 *          ├─ LLM.thinkWithTools()
 *          ├─ 无 tool_calls → 文本回复，返回
 *          └─ 有 tool_calls → 执行工具 → 追加 tool 结果 → 继续
 * </pre>
 */
public class FunctionCallAgent extends Agent {

    private final ToolRegistry toolRegistry;
    private final int maxIterations;
    private final boolean enableToolCalling;

    // Per-run state
    private int totalToolCalls;
    private final Map<String, Integer> toolCallCounts;

    // Editable system prompt (updated by CodebaseMaintainer per mode)
    private String activeSystemPrompt;

    public FunctionCallAgent(String name, HelloAgentsLLM llm, String systemPrompt,
                             Config config, ToolRegistry toolRegistry,
                             boolean enableToolCalling, int maxIterations) {
        super(name, llm, systemPrompt, config);
        this.toolRegistry = toolRegistry;
        this.enableToolCalling = enableToolCalling;
        this.maxIterations = maxIterations;
        this.activeSystemPrompt = systemPrompt;
        this.toolCallCounts = new LinkedHashMap<>();
    }

    public FunctionCallAgent(String name, HelloAgentsLLM llm, String systemPrompt,
                             ToolRegistry toolRegistry) {
        this(name, llm, systemPrompt, null, toolRegistry, true, 5);
    }

    /** Update system prompt per invocation (used by CodebaseMaintainer for mode-specific hints). */
    public void setSystemPrompt(String prompt) {
        this.activeSystemPrompt = prompt;
    }

    public int getTotalToolCalls() { return totalToolCalls; }
    public Map<String, Integer> getToolCallCounts() { return new LinkedHashMap<>(toolCallCounts); }

    @Override
    public String run(String userInput) {
        totalToolCalls = 0;
        toolCallCounts.clear();

        // 1. Build initial messages
        List<Message> messages = new ArrayList<>();
        if (activeSystemPrompt != null && !activeSystemPrompt.isBlank()) {
            messages.add(new Message(activeSystemPrompt, Message.ROLE_SYSTEM));
        }
        // Add recent conversation history
        for (Message h : history) {
            if (h.hasToolCalls() || Message.ROLE_TOOL.equals(h.role())) {
                messages.add(h);  // preserve tool call history
            } else {
                messages.add(new Message(h.content(), h.role()));
            }
        }
        messages.add(new Message(userInput, Message.ROLE_USER));

        // 2. Get tool schemas
        List<Map<String, Object>> toolSchemas = null;
        if (enableToolCalling && toolRegistry != null) {
            toolSchemas = toolRegistry.getAllSchemas();
            if (toolSchemas.isEmpty()) toolSchemas = null;
        }

        // 3. If no tools, fall back to simple text call
        if (toolSchemas == null) {
            return llm.thinkMessages(messages);
        }

        // 4. Agentic loop
        String finalResponse = null;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            HelloAgentsLLM.ThinkWithToolsResult result =
                    llm.thinkWithTools(messages, toolSchemas, config.temperature());

            if (result == null) {
                System.err.println("[FunctionCallAgent] LLM 返回 null");
                break;
            }

            if (!result.hasToolCalls()) {
                // Plain text response → done
                finalResponse = result.content();
                if (finalResponse != null) {
                    addMessage(new Message(finalResponse, Message.ROLE_ASSISTANT));
                }
                break;
            }

            // Has tool_calls → execute and feed back
            totalToolCalls += result.toolCalls().size();

            // Build assistant message with tool_calls
            List<Map<String, Object>> toolCallsForMsg = new ArrayList<>();
            for (var tc : result.toolCalls()) {
                Map<String, Object> tcMap = new LinkedHashMap<>();
                tcMap.put("id", tc.id());
                tcMap.put("type", "function");
                Map<String, Object> funcMap = new LinkedHashMap<>();
                funcMap.put("name", tc.name());
                funcMap.put("arguments", toJson(tc.arguments()));
                tcMap.put("function", funcMap);
                toolCallsForMsg.add(tcMap);
            }
            Message assistantMsg = new Message(result.content(), Message.ROLE_ASSISTANT, toolCallsForMsg);
            if (result.reasoningContent() != null && !result.reasoningContent().isEmpty()) {
                assistantMsg.metadata().put("reasoning_content", result.reasoningContent());
            }
            messages.add(assistantMsg);

            // Execute each tool
            for (var tc : result.toolCalls()) {
                System.out.println("  🔧 执行工具: " + tc.name() + "(" + tc.arguments() + ")");
                String toolResult;
                try {
                    toolResult = toolRegistry.executeTool(tc.name(), tc.arguments());
                    toolCallCounts.merge(tc.name(), 1, Integer::sum);
                } catch (Exception e) {
                    toolResult = "❌ 工具执行异常: " + e.getMessage();
                }
                System.out.println("  📄 工具结果: " +
                        (toolResult != null ? toolResult.substring(0, Math.min(200, toolResult.length())) : "null"));
                // Feed back as role="tool" message
                messages.add(new Message(toolResult, Message.ROLE_TOOL, tc.id(), tc.name()));
            }
        }

        if (finalResponse == null) {
            // Max iterations reached without text response → summarize with accumulated tool results
            System.out.println("[FunctionCallAgent] 达到最大迭代次数，基于已有工具结果总结...");
            messages.add(new Message("请基于以上工具执行结果，用中文给出总结回答", Message.ROLE_USER));
            HelloAgentsLLM.ThinkWithToolsResult result = llm.thinkWithTools(messages, null);
            if (result != null && result.content() != null) {
                finalResponse = result.content();
                addMessage(new Message(finalResponse, Message.ROLE_ASSISTANT));
            }
        }

        // Store user message in history
        addMessage(new Message(userInput, Message.ROLE_USER));
        return finalResponse != null ? finalResponse : "未能获取有效回复";
    }

    // ==================== helpers ====================

    private static String paramsToString(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(", ");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\": ");
            Object val = entry.getValue();
            if (val instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else if (val instanceof Number || val instanceof Boolean) {
                sb.append(val);
            } else if (val instanceof List<?> list) {
                sb.append("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("\"").append(escapeJson(String.valueOf(list.get(i)))).append("\"");
                }
                sb.append("]");
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
