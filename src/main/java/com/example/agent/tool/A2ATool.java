package com.example.agent.tool;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * A2A (Agent-to-Agent Protocol) 工具 — 连接到 A2A Agent 并进行通信。
 *
 * 对应 Python 版 hello_agents 的 A2ATool，提供：
 *   - ask: 向远程 Agent 提问并获取回答
 *   - get_info: 获取 Agent 的能力卡片信息
 *
 * 基于 A2A 开放协议（google/A2A），通过 HTTP + JSON 与远程 Agent 通信。
 *
 * 使用示例：
 * <pre>
 *   // 连接 A2A Agent
 *   A2ATool tool = new A2ATool("http://localhost:5000");
 *   tool.run(Map.of("action", "ask", "question", "计算 2+2"));
 *   tool.run(Map.of("action", "get_info"));
 *
 *   // 注册到 ToolRegistry 供 LLM 调用
 *   registry.registerTool(tool);
 * </pre>
 *
 * @see <a href="https://github.com/google/A2A">A2A Protocol</a>
 */
public class A2ATool extends Tool {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String agentUrl;

    // ==================== 构造 ====================

    /** 连接到 A2A Agent（默认名称 "a2a"）。 */
    public A2ATool(String agentUrl) {
        this("a2a", agentUrl);
    }

    /**
     * @param name     工具名称（建议自定义，如 "tech_expert"）
     * @param agentUrl Agent URL（如 "http://localhost:5000"）
     */
    public A2ATool(String name, String agentUrl) {
        this(name, agentUrl, "连接到 A2A Agent [" + agentUrl + "]，支持提问和获取 Agent 信息。");
    }

    /**
     * @param name        工具名称
     * @param agentUrl    Agent URL
     * @param description 自定义描述
     */
    public A2ATool(String name, String agentUrl, String description) {
        super(name, description);
        this.agentUrl = agentUrl.endsWith("/")
                ? agentUrl.substring(0, agentUrl.length() - 1)
                : agentUrl;
    }

    // ==================== Tool 接口 ====================

    @Override
    public String run(Map<String, Object> parameters) {
        String action = ((String) parameters.getOrDefault("action", "")).toLowerCase();
        if (action.isEmpty()) {
            return "❌ 必须指定 action 参数 (ask / get_info)";
        }

        return switch (action) {
            case "ask"      -> handleAsk(parameters);
            case "get_info" -> handleGetInfo();
            default -> "❌ 不支持的操作: " + action + "。可用: ask, get_info";
        };
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("action", "string",
                "操作类型: ask(提问), get_info(获取Agent信息)", true, null),
            new ToolParameter("question", "string",
                "问题文本（ask 操作需要）", false, null)
        );
    }

    // ==================== 操作处理 ====================

    private String handleAsk(Map<String, Object> params) {
        String question = (String) params.get("question");
        if (question == null || question.isBlank()) {
            return "❌ ask 操作需要 question 参数";
        }

        try {
            // 尝试 A2A 标准 JSON-RPC 格式
            Map<String, Object> requestBody = buildA2ARequest(question);
            String json = GSON.toJson(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(agentUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 202) {
                return extractTextFromResponse(response.body());
            }
            if (response.statusCode() == 404) {
                // 回退：尝试简单的 /tasks 端点
                return askViaTasksEndpoint(question);
            }
            return "❌ Agent 返回错误 (HTTP " + response.statusCode() + "): "
                    + response.body().substring(0, Math.min(300, response.body().length()));

        } catch (Exception e) {
            return "❌ 与 A2A Agent 通信失败: " + e.getMessage();
        }
    }

    private String handleGetInfo() {
        try {
            // 尝试获取 Agent Card
            String cardUrl = agentUrl + "/.well-known/agent-card.json";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cardUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return formatAgentCard(response.body());
            }

            // 回退：尝试 /agent-card
            String altCardUrl = agentUrl + "/agent-card";
            request = HttpRequest.newBuilder()
                    .uri(URI.create(altCardUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return formatAgentCard(response.body());
            }

            return "❌ 无法获取 Agent 信息 (HTTP " + response.statusCode() + ")";

        } catch (Exception e) {
            return "❌ 获取 Agent 信息失败: " + e.getMessage();
        }
    }

    // ==================== A2A 协议辅助 ====================

    /** 构建 A2A 标准 JSON-RPC 请求体。 */
    private static Map<String, Object> buildA2ARequest(String question) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", question);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("parts", List.of(textPart));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", "a2a-tool-" + UUID.randomUUID().toString().substring(0, 8));
        request.put("method", "tasks/send");
        request.put("params", params);

        return request;
    }

    /** 回退：POST 到 /tasks 端点（简化 REST 格式）。 */
    private String askViaTasksEndpoint(String question) throws Exception {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", question);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("parts", List.of(textPart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(agentUrl + "/tasks"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200 || response.statusCode() == 202) {
            return extractTextFromResponse(response.body());
        }
        return "❌ Agent 返回错误 (HTTP " + response.statusCode() + "): "
                + response.body().substring(0, Math.min(300, response.body().length()));
    }

    /** 从 A2A 响应中提取文本内容。 */
    @SuppressWarnings("unchecked")
    private static String extractTextFromResponse(String responseBody) {
        try {
            Map<String, Object> root = GSON.fromJson(responseBody,
                    new TypeToken<Map<String, Object>>() {}.getType());

            // JSON-RPC 格式: {"result": {"task": {"messages": [...]}}}
            if (root.containsKey("result")) {
                Map<String, Object> result = (Map<String, Object>) root.get("result");
                // result 可能是 task 或直接是 messages
                if (result.containsKey("task")) {
                    result = (Map<String, Object>) result.get("task");
                }
                return extractMessages(result);
            }

            // 直接格式: {"messages": [...]} 或 {"task": {"messages": [...]}}
            if (root.containsKey("task")) {
                return extractMessages((Map<String, Object>) root.get("task"));
            }
            if (root.containsKey("messages")) {
                return extractMessages(root);
            }

            // 纯文本兜底
            if (root.containsKey("text")) {
                return (String) root.get("text");
            }
            if (root.containsKey("content")) {
                return String.valueOf(root.get("content"));
            }

            // 返回原始 JSON（截断）
            return responseBody.length() > 500
                    ? responseBody.substring(0, 500) + "..."
                    : responseBody;

        } catch (Exception e) {
            // 不是 JSON，直接返回文本
            return responseBody.length() > 500
                    ? responseBody.substring(0, 500) + "..."
                    : responseBody;
        }
    }

    /** 从 messages 列表中提取 TextPart。 */
    @SuppressWarnings("unchecked")
    private static String extractMessages(Map<String, Object> container) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) container.get("messages");
        if (messages == null || messages.isEmpty()) return "（Agent 无回复）";

        StringBuilder sb = new StringBuilder();
        for (var msg : messages) {
            String role = (String) msg.getOrDefault("role", "agent");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) msg.get("parts");
            if (parts == null) continue;

            for (var part : parts) {
                String text = (String) part.get("text");
                if (text != null && !text.isBlank()) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append("[").append(role).append("] ").append(text);
                }
            }
        }
        return !sb.isEmpty() ? sb.toString() : "（Agent 无文本回复）";
    }

    /** 格式化 Agent Card 为可读文本。 */
    @SuppressWarnings("unchecked")
    private static String formatAgentCard(String cardJson) {
        try {
            Map<String, Object> card = GSON.fromJson(cardJson,
                    new TypeToken<Map<String, Object>>() {}.getType());

            StringBuilder sb = new StringBuilder("A2A Agent 信息:\n");
            sb.append("- 名称: ").append(card.getOrDefault("name", "未知")).append("\n");
            sb.append("- 描述: ").append(card.getOrDefault("description", "无描述")).append("\n");
            sb.append("- URL: ").append(card.getOrDefault("url", "未知")).append("\n");

            String version = (String) card.getOrDefault("version", "");
            if (!version.isBlank()) sb.append("- 版本: ").append(version).append("\n");

            Map<String, Object> provider = (Map<String, Object>) card.get("provider");
            if (provider != null) {
                sb.append("- 提供商: ").append(provider.getOrDefault("organization", "未知")).append("\n");
            }

            List<Map<String, Object>> skills = (List<Map<String, Object>>) card.get("skills");
            if (skills != null && !skills.isEmpty()) {
                sb.append("- 技能 (").append(skills.size()).append(" 项):\n");
                for (var skill : skills) {
                    sb.append("    • ").append(skill.getOrDefault("name", "?"))
                      .append(": ").append(skill.getOrDefault("description", "")).append("\n");
                }
            }

            Map<String, Object> capabilities = (Map<String, Object>) card.get("capabilities");
            if (capabilities != null && !capabilities.isEmpty()) {
                sb.append("- 能力: ");
                sb.append(String.join(", ", capabilities.keySet()));
                sb.append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            return "Agent Card:\n" + cardJson;
        }
    }

    // ==================== 访问器 ====================

    public String getAgentUrl() { return agentUrl; }
}
