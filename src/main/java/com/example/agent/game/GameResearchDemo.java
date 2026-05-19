package com.example.agent.game;

import com.example.agent.LoadDotenvUtil;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.FunctionCallAgent;
import com.example.agent.tool.MCPTool;
import com.example.agent.tool.MCPWrappedTool;
import com.example.agent.tool.SearchTool;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolParameter;
import com.example.agent.tool.ToolRegistry;
import com.google.gson.Gson;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * 游戏研究智能体 — 支持三种模式:
 *
 *   demo [游戏名]    本地报告模式（不连 MCP）
 *   mcp [游戏名]     MCP 连接 + 数据回传模式
 *   (无参数)         交互模式，自动检测 MCP 可用性
 *
 * 运行:
 *   mvn exec:java -Dexec.mainClass=com.example.agent.game.GameResearchDemo -Dexec.args="demo 'Elden Ring'"
 *   mvn exec:java -Dexec.mainClass=com.example.agent.game.GameResearchDemo -Dexec.args="mcp"
 */
public class GameResearchDemo {

    public static void main(String[] args) {
        String mode = (args.length > 0) ? args[0].toLowerCase() : "interactive";
        String gameName = (args.length > 1) ? args[1] : null;

        System.out.println("=".repeat(60));
        System.out.println("Game Research Agent (游戏研究智能体)");
        System.out.println("=".repeat(60));

        // --- 1. 创建 LLM ---
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("LLM 初始化失败: " + e.getMessage());
            return;
        }

        // --- 2. 初始化工具 ---
        ToolRegistry registry = new ToolRegistry();
        registry.registerTool(new SearchTool());
        registry.registerTool(new GameImageSearchTool());

        // --- 3. 尝试连接 MCP Server ---
        boolean mcpConnected = false;
        if (!"demo".equals(mode)) {
            mcpConnected = setupMcpServer(registry);
        } else {
            System.out.println("Demo 模式 — 跳过 MCP 连接");
        }

        System.out.println("已注册工具: " + registry.listTools());

        // --- 4. 创建 Agent ---
        String systemPrompt = mcpConnected ? buildMcpSystemPrompt() : buildLocalSystemPrompt();
        FunctionCallAgent agent = new FunctionCallAgent(
                "GameResearchAgent", llm, systemPrompt, registry);

        // --- 5. 运行 ---
        switch (mode) {
            case "demo" -> runDemo(agent, gameName);
            case "mcp" -> runMcpMode(agent, gameName);
            default -> runInteractive(agent, mcpConnected);
        }
    }

    // ==================== System Prompts ====================

    private static String buildLocalSystemPrompt() {
        return """
            你是一个专业的游戏研究助手。当用户给出一个游戏名称时，请执行以下研究流程：

            1. 使用 search 工具搜索该游戏的基本信息（游戏类型、开发商、发行商、发布日期、平台、核心玩法）。
               建议搜索关键词："游戏名 游戏介绍 开发商 类型"

            2. 使用 search 工具搜索该游戏的攻略、技巧或评测。
               建议搜索关键词："游戏名 攻略 新手指南"

            3. 使用 search_game_images 工具搜索该游戏的截图。

            4. 将收集到的信息整理成一份完整的中文研究报告，包括：基本信息、游戏简介、攻略要点、截图链接。

            请用中文回复。每一步都实际调用工具获取信息，不要编造内容。""";
    }

    private static String buildMcpSystemPrompt() {
        return """
            你是一个专业的游戏研究助手，连接到游戏数据后端系统。你的工作流程：

            ## 查询阶段
            - 使用 list_games_needing_enrichment 查看哪些游戏需要补充信息
            - 使用 search_games 按关键词搜索特定游戏
            - 使用 get_game_detail 获取单个游戏的当前信息

            ## 研究阶段（针对目标游戏）
            1. 使用 search 工具搜索游戏基本信息：类型(genre)、开发商(developer)、发行商(publisher)、
               发布日期(releaseDate)、支持平台(platforms)、游戏介绍(description)
            2. 使用 search 工具搜索游戏攻略、评测、新手指南
            3. 使用 search_game_images 工具搜索游戏截图

            ## 回传阶段
            4. 使用 save_game_info 将收集到的元数据保存到后端（需要 gameId + 所有字段）
            5. 使用 save_game_guide 保存攻略内容（可多次调用保存不同类型攻略）
            6. 使用 save_game_screenshots 保存截图（传入 gameId + 图片URL的JSON数组）

            ## 重要规则
            - 用户指定游戏名时，先用 search_games 查找对应的 gameId，再开始研究
            - save_game_info 的 genre/developer/publisher/releaseDate/platforms 参数都要尽量填全
            - 攻略分多次保存：walkthrough(流程攻略)、tips(技巧心得)、review(评测)
            - 最后汇报哪些数据已成功保存到后端

            请用中文回复。""";
    }

    // ==================== MCP 连接 ====================

    private static McpSyncClient sseClient; // SSE 连接复用

    private static boolean setupMcpServer(ToolRegistry registry) {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();

        // 优先 SSE 模式（生产环境，Agent 和 Spring Boot 部署在不同服务器）
        String mcpUrl = env.getOrDefault("GAME_MCP_URL",
                System.getenv("GAME_MCP_URL") != null ? System.getenv("GAME_MCP_URL") : "");
        if (!mcpUrl.isBlank()) {
            return connectViaSse(registry, mcpUrl);
        }

        // 回退 stdio 模式（本地开发）
        String jarPath = env.getOrDefault("GAME_MCP_JAR_PATH",
                System.getenv("GAME_MCP_JAR_PATH") != null ? System.getenv("GAME_MCP_JAR_PATH") : "");
        if (!jarPath.isBlank()) {
            return connectViaStdio(registry, jarPath, env);
        }

        System.out.println("GAME_MCP_URL / GAME_MCP_JAR_PATH 均未配置，Agent 以本地报告模式运行");
        return false;
    }

    /** SSE 模式 — Agent 通过网络连接远端 Spring Boot 的 MCP SSE 端点 */
    private static boolean connectViaSse(ToolRegistry registry, String baseUrl) {
        try {
            System.out.println("连接 Game MCP Server (SSE): " + baseUrl);

            HttpClientSseClientTransport transport = HttpClientSseClientTransport
                    .builder(baseUrl)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            sseClient = McpClient.sync(transport).build();
            sseClient.initialize();

            var toolsResult = sseClient.listTools();
            if (toolsResult == null || toolsResult.tools().isEmpty()) {
                System.out.println("远端 MCP Server 未提供任何工具");
                return false;
            }

            for (McpSchema.Tool tool : toolsResult.tools()) {
                registry.registerTool(new SseProxyTool(tool.name(), tool.description(), tool));
            }

            System.out.println("SSE 连接成功！远端提供 " + toolsResult.tools().size() + " 个工具");
            return true;

        } catch (Exception e) {
            System.out.println("SSE 连接失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /** Stdio 模式 — 本地开发，启动 JAR 作为子进程 */
    private static boolean connectViaStdio(ToolRegistry registry, String jarPath, Map<String, String> env) {
        String javaHome = env.getOrDefault("GAME_MCP_JAVA_HOME",
                System.getenv("GAME_MCP_JAVA_HOME") != null ? System.getenv("GAME_MCP_JAVA_HOME") : "");
        String javaCmd = javaHome.isBlank() ? "java" : javaHome + "/bin/java";

        try {
            System.out.println("连接 Game MCP Server (stdio): " + jarPath);
            MCPTool mcpTool = new MCPTool("game",
                    List.of(javaCmd, "-Dspring.main.web-application-type=none", "-jar", jarPath),
                    List.of(), Map.of(), true);

            if (!mcpTool.isInitialized()) {
                System.out.println("MCP Server 初始化失败: " + mcpTool.getInitError());
                return false;
            }

            registry.registerTool(mcpTool);
            System.out.println("MCP 连接成功！服务器提供 " +
                    mcpTool.getAvailableTools().size() + " 个工具");

            for (MCPWrappedTool t : mcpTool.getExpandedTools()) {
                registry.registerTool(t);
            }
            return true;

        } catch (Exception e) {
            System.out.println("MCP 连接失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * SSE 代理工具 — 将远端 MCP 工具包装为本地 Tool。
     * 调用时通过 McpSyncClient.callTool() 转发到远端执行。
     */
    private static class SseProxyTool extends Tool {
        private static final Gson GSON = new Gson();
        private final List<ToolParameter> params;

        SseProxyTool(String name, String description, McpSchema.Tool remoteTool) {
            super(name, description != null ? description : "");
            this.params = parseInputSchema(remoteTool.inputSchema());
        }

        /** 将远端 JSON Schema 参数转为本地 ToolParameter 列表 */
        @SuppressWarnings("unchecked")
        private static List<ToolParameter> parseInputSchema(McpSchema.JsonSchema schema) {
            if (schema == null || schema.properties() == null) return List.of();
            List<ToolParameter> result = new ArrayList<>();
            List<String> required = schema.required() != null ? schema.required() : List.of();

            for (Map.Entry<String, Object> prop : schema.properties().entrySet()) {
                String name = prop.getKey();
                Map<String, Object> def = (Map<String, Object>) prop.getValue();
                String type = def.getOrDefault("type", "string").toString();
                String desc = def.getOrDefault("description", "").toString();
                boolean req = required.contains(name);

                Object defaultVal = def.get("default");
                if (defaultVal == null && !req) defaultVal = "";

                result.add(new ToolParameter(name, type, desc, req, defaultVal));
            }
            return result;
        }

        @Override
        public List<ToolParameter> getParameters() {
            return params;
        }

        @Override
        public String run(Map<String, Object> parameters) {
            if (sseClient == null) {
                return "{\"error\": \"MCP SSE 连接未建立\"}";
            }
            try {
                McpSchema.CallToolResult result = sseClient.callTool(
                        new McpSchema.CallToolRequest(name(), parameters));

                if (result == null || result.content() == null) {
                    return "{\"result\": \"(empty)\"}";
                }
                StringBuilder sb = new StringBuilder();
                for (Object c : result.content()) {
                    if (c instanceof McpSchema.TextContent tc) {
                        sb.append(tc.text());
                    }
                }
                return sb.toString();
            } catch (Exception e) {
                return "{\"error\": \"" + e.getMessage() + "\"}";
            }
        }
    }

    // ==================== 运行模式 ====================

    /** Demo 模式 — 本地搜索，不推数据 */
    private static void runDemo(FunctionCallAgent agent, String gameName) {
        if (gameName == null) gameName = "Elden Ring（艾尔登法环）";
        System.out.println("\n--- 本地报告模式: 《" + gameName + "》 ---\n");
        String response = agent.run("请帮我研究游戏：" + gameName);
        System.out.println("\n" + response);
    }

    /** MCP 模式 — 从后端拉取游戏 → 研究 → 回传数据 */
    private static void runMcpMode(FunctionCallAgent agent, String gameName) {
        if (gameName != null) {
            // 指定了游戏名：先搜索 gameId，再研究并回传
            System.out.println("\n--- MCP 模式: 研究并回传《" + gameName + "》 ---\n");
            String prompt = String.format(
                "请用 search_games 查找游戏 '%s'，然后对该游戏进行全面研究（基本信息、攻略、截图），" +
                "最后用 save_game_info、save_game_guide、save_game_screenshots 回传所有数据到后端。",
                gameName);
            String response = agent.run(prompt);
            System.out.println("\n" + response);
        } else {
            // 未指定游戏：先列出待处理游戏
            System.out.println("\n--- MCP 模式: 查看待处理游戏 ---\n");
            String response = agent.run(
                "请用 list_games_needing_enrichment 查看有哪些游戏需要补充信息，" +
                "列出前 5 个需要处理的游戏，并推荐先处理哪一个。");
            System.out.println("\n" + response);

            // 交互式处理
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("\n输入游戏ID或名称开始研究（quit 退出）: ");
                    String input = scanner.nextLine().strip();
                    if (input.isEmpty()) continue;
                    if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) break;

                    String prompt;
                    if (input.matches("\\d+")) {
                        prompt = String.format(
                            "请用 get_game_detail 获取游戏ID=%s 的详情，然后对该游戏进行全面研究（基本信息、攻略、截图），" +
                            "最后用 save_game_info、save_game_guide、save_game_screenshots 回传所有数据到后端。",
                            input);
                    } else {
                        prompt = String.format(
                            "请用 search_games 查找游戏 '%s'，然后对该游戏进行全面研究（基本信息、攻略、截图），" +
                            "最后用 save_game_info、save_game_guide、save_game_screenshots 回传所有数据到后端。",
                            input);
                    }
                    String result = agent.run(prompt);
                    System.out.println("\n" + result);
                }
            }
        }
    }

    /** 交互模式 — 手动输入游戏名，支持 MCP 回传 */
    private static void runInteractive(FunctionCallAgent agent, boolean mcpConnected) {
        System.out.println("\n交互模式（输入 quit 退出）\n");
        System.out.println(mcpConnected
            ? "MCP 已连接 — 研究结果将自动回传到后端"
            : "MCP 未连接 — Agent 以本地报告模式运行");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\n请输入游戏名称: ");
                String input = scanner.nextLine().strip();
                if (input.isEmpty()) continue;
                if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) break;

                String prompt;
                if (mcpConnected) {
                    prompt = String.format(
                        "请用 search_games 查找游戏 '%s'，然后全面研究（信息、攻略、截图），" +
                        "并用 save_game_info、save_game_guide、save_game_screenshots 回传数据。", input);
                } else {
                    prompt = "请帮我全面研究游戏：" + input;
                }
                String response = agent.run(prompt);
                System.out.println("\n" + response);
            }
        }
    }
}
