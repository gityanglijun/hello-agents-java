package com.example.agent.trip.service;

import com.google.gson.Gson;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 高德地图 MCP 服务器 — 标准 MCP stdio 协议。
 *
 * 通过标准输入/输出（stdio）与 MCP 客户端通信，协议为 JSON-RPC 2.0。
 *
 * MCP 协议核心流程：
 *   1. 客户端通过 stdio 启动此进程
 *   2. 客户端发送 initialize 请求 → 服务器返回能力声明
 *   3. 客户端发送 tools/list → 服务器返回工具列表
 *   4. 客户端发送 tools/call → 服务器执行工具并返回结果
 *   5. 客户端断开连接 → stdin 关闭 → 服务器退出
 *
 * 启动方式（由 MCP 客户端自动启动）：
 *   java -cp ... com.example.agent.trip.service.AmapMcpServer
 *
 * 环境变量：
 *   AMAP_API_KEY — 高德地图 API Key（必需）
 */
public class AmapMcpServer {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("AMAP_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("[AmapMcpServer] 错误: 未设置 AMAP_API_KEY 环境变量");
            System.exit(1);
        }

        System.err.println("[AmapMcpServer] MCP 服务器启动中...");

        AmapService amap = new AmapService(apiKey);

        // 测试高德 API 连接
        Map<String, Object> test = amap.textSearch("景点", "北京");
        if ("1".equals(String.valueOf(test.get("status")))) {
            System.err.println("[AmapMcpServer] 高德 API 连接正常");
        } else {
            System.err.println("[AmapMcpServer] 高德 API 测试失败: " + test.get("info"));
        }

        // 构建 MCP 服务器（stdio 传输）
        McpSyncServer server = McpServer.sync(
                new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
                .serverInfo("amap-mcp-server", "1.0.0")
                .instructions("高德地图 MCP 服务器 — 提供 POI 搜索、天气查询、路线规划等工具")
                .toolCall(textSearchToolDef(), (ex, req) -> executeTool(req,
                        p -> amap.textSearchRaw(
                                str(p, "keywords", "景点"),
                                str(p, "city", "北京"))))
                .toolCall(weatherToolDef(), (ex, req) -> executeTool(req,
                        p -> amap.weatherRaw(
                                str(p, "city", "北京"))))
                .toolCall(drivingToolDef(), (ex, req) -> executeTool(req,
                        p -> GSON.toJson(
                                amap.drivingDirection(
                                        str(p, "origin", ""),
                                        str(p, "destination", "")))))
                .toolCall(walkingToolDef(), (ex, req) -> executeTool(req,
                        p -> GSON.toJson(
                                amap.walkingDirection(
                                        str(p, "origin", ""),
                                        str(p, "destination", "")))))
                .toolCall(transitToolDef(), (ex, req) -> executeTool(req,
                        p -> GSON.toJson(
                                amap.transitDirection(
                                        str(p, "origin", ""),
                                        str(p, "destination", ""),
                                        str(p, "city", "北京")))))
                .build();

        System.err.println("[AmapMcpServer] 服务器就绪，提供 5 个工具，等待客户端连接...");

        // 保持进程运行直到 stdin 关闭（客户端断开连接）
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[AmapMcpServer] 正在关闭...");
            server.closeGracefully();
            latch.countDown();
        }));
        latch.await();
    }

    // ==================== Tool Definitions ====================

    private static McpSchema.Tool textSearchToolDef() {
        return McpSchema.Tool.builder()
                .name("text_search")
                .description("Amap POI search. Search for places (attractions, hotels, restaurants) by keyword and city.")
                .inputSchema(new McpSchema.JsonSchema(
                        "object",
                        Map.of(
                                "keywords", prop("string", "Search keyword, e.g. 'attraction', 'hotel', 'restaurant'"),
                                "city", prop("string", "City name, e.g. 'Beijing', 'Shanghai'")),
                        List.of("keywords"),
                        null, null, null))
                .build();
    }

    private static McpSchema.Tool weatherToolDef() {
        return McpSchema.Tool.builder()
                .name("weather")
                .description("Amap weather query. Get weather forecast for a specified city.")
                .inputSchema(new McpSchema.JsonSchema(
                        "object",
                        Map.of("city", prop("string", "City name, e.g. 'Beijing', 'Shanghai'")),
                        List.of("city"),
                        null, null, null))
                .build();
    }

    private static McpSchema.Tool drivingToolDef() {
        return McpSchema.Tool.builder()
                .name("driving")
                .description("Amap driving directions. Calculate driving route, distance and estimated time.")
                .inputSchema(new McpSchema.JsonSchema(
                        "object",
                        Map.of(
                                "origin", prop("string", "Starting address"),
                                "destination", prop("string", "Destination address")),
                        List.of("origin", "destination"),
                        null, null, null))
                .build();
    }

    private static McpSchema.Tool walkingToolDef() {
        return McpSchema.Tool.builder()
                .name("walking")
                .description("Amap walking directions. Calculate walking route, distance and estimated time.")
                .inputSchema(new McpSchema.JsonSchema(
                        "object",
                        Map.of(
                                "origin", prop("string", "Starting address"),
                                "destination", prop("string", "Destination address")),
                        List.of("origin", "destination"),
                        null, null, null))
                .build();
    }

    private static McpSchema.Tool transitToolDef() {
        return McpSchema.Tool.builder()
                .name("transit")
                .description("Amap transit directions. Calculate bus/subway transfer routes.")
                .inputSchema(new McpSchema.JsonSchema(
                        "object",
                        Map.of(
                                "origin", prop("string", "Starting address"),
                                "destination", prop("string", "Destination address"),
                                "city", prop("string", "City name")),
                        List.of("origin", "destination", "city"),
                        null, null, null))
                .build();
    }

    // ==================== 工具执行 ====================

    @FunctionalInterface
    private interface ToolExecutor {
        String execute(Map<String, Object> args);
    }

    private static McpSchema.CallToolResult executeTool(
            McpSchema.CallToolRequest request, ToolExecutor executor) {
        try {
            Map<String, Object> args = request.arguments();
            if (args == null) args = Map.of();
            String result = executor.execute(args);
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(result)))
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(
                            "工具执行失败: " + e.getMessage())))
                    .isError(true)
                    .build();
        }
    }

    // ==================== 辅助方法 ====================

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? String.valueOf(v) : def;
    }
}
