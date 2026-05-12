package com.example.agent.tool;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP (Model Context Protocol) 工具 — 连接 MCP 服务器并调用其提供的工具和资源。
 *
 * 对应 Python 版 hello_agents 的 MCPTool，提供：
 *   - 列出服务器工具 (list_tools)
 *   - 调用服务器工具 (call_tool)
 *   - 列出/读取服务器资源 (list_resources / read_resource)
 *   - 获取提示词模板 (list_prompts / get_prompt)
 *   - 内置演示服务器（无配置时自动启用，含 6 个计算器/系统工具）
 *   - 工具自动展开（auto-expand：将服务器工具包装为独立 Tool）
 *
 * 使用示例：
 * <pre>
 *   // 零配置：使用内置演示服务器（加法/减法/乘法/除法/问候/系统信息）
 *   MCPTool tool = new MCPTool("calculator");
 *   tool.run(Map.of("action", "list_tools"));  // 列出 6 个工具
 *   for (MCPWrappedTool t : tool.getExpandedTools()) {
 *       registry.registerTool(t);  // 展开为独立工具
 *   }
 *
 *   // 连接外部 MCP 服务器
 *   MCPTool github = new MCPTool("github",
 *       List.of("npx", "-y", "@modelcontextprotocol/server-github"),
 *       Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", "ghp_xxx"));
 * </pre>
 */
public class MCPTool extends Tool {

    private static final Gson GSON = new Gson();

    // ==================== 状态 ====================

    private final List<String> serverCommand;
    private final List<String> serverArgs;
    private final Map<String, String> env;
    private final boolean autoExpand;
    private final boolean builtin;   // true = 内置演示服务器，不走 MCP 协议
    private final String prefix;

    private McpSyncClient client;
    private List<Map<String, Object>> availableTools;
    private List<Map<String, Object>> availableResources;
    private List<Map<String, Object>> availablePrompts;
    private boolean initialized;
    private String initError;

    // ==================== 构造 ====================

    /** 零配置构造：使用内置演示服务器（含 6 个计算器/系统工具）。 */
    public MCPTool() {
        this("mcp");
    }

    /** 使用内置演示服务器并指定名称。 */
    public MCPTool(String name) {
        this(name, List.of(), List.of(), Map.of(), true);
    }

    /** 连接外部 MCP 服务器。 */
    public MCPTool(String name, List<String> serverCommand, Map<String, String> env) {
        this(name, serverCommand, List.of(), env, true);
    }

    /**
     * @param name          工具名称
     * @param serverCommand MCP 服务器启动命令
     * @param serverArgs    服务器额外参数
     * @param env           环境变量
     * @param autoExpand    是否允许自动展开为独立工具（默认 true）
     */
    public MCPTool(String name, List<String> serverCommand, List<String> serverArgs,
                   Map<String, String> env, boolean autoExpand) {
        super(name, "MCP 工具服务器" +
              (serverCommand != null && !serverCommand.isEmpty()
                      ? " [" + String.join(" ", serverCommand) + "]"
                      : ""));
        this.serverCommand = serverCommand != null ? new ArrayList<>(serverCommand) : List.of();
        this.serverArgs = serverArgs != null ? new ArrayList<>(serverArgs) : List.of();
        this.env = env != null ? new LinkedHashMap<>(env) : new LinkedHashMap<>();
        this.autoExpand = autoExpand;
        this.builtin = this.serverCommand.isEmpty();
        this.prefix = autoExpand ? name + "_" : "";
        this.availableTools = List.of();
        this.availableResources = List.of();
        this.availablePrompts = List.of();

        initClient();
    }

    // ==================== 初始化 ====================

    private void initClient() {
        if (builtin) {
            initBuiltinTools();
            return;
        }

        try {
            String command = serverCommand.get(0);
            List<String> args = new ArrayList<>();
            if (serverCommand.size() > 1) {
                args.addAll(serverCommand.subList(1, serverCommand.size()));
            }
            args.addAll(serverArgs);

            ServerParameters.Builder builder = ServerParameters.builder(command)
                    .args(args.toArray(new String[0]));
            if (!env.isEmpty()) {
                builder.env(env);
            }

            StdioClientTransport transport = new StdioClientTransport(
                    builder.build(), McpJsonDefaults.getMapper());
            this.client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(60))
                    .build();
            this.client.initialize();
            discoverAll();
            this.initialized = true;
            System.out.println("[MCPTool] ✅ 已连接 MCP 服务器: " +
                    String.join(" ", serverCommand) +
                    "，发现 " + availableTools.size() + " 个工具");

        } catch (Exception e) {
            this.initError = "MCP 服务器初始化失败: " + e.getMessage();
            System.out.println("[MCPTool] ❌ " + initError);
            this.initialized = false;
        }
    }

    // ==================== 内置演示服务器 ====================

    /** 初始化内置演示工具（对应 Python 版 FastMCP("HelloAgents-BuiltinServer")）。 */
    private void initBuiltinTools() {
        this.availableTools = List.of(
            toolDef("add", "加法计算器，计算两个数的和",
                Map.of("a", propDef("number", "第一个加数"),
                       "b", propDef("number", "第二个加数")),
                List.of("a", "b")),
            toolDef("subtract", "减法计算器，计算 a - b",
                Map.of("a", propDef("number", "被减数"),
                       "b", propDef("number", "减数")),
                List.of("a", "b")),
            toolDef("multiply", "乘法计算器，计算两个数的积",
                Map.of("a", propDef("number", "第一个乘数"),
                       "b", propDef("number", "第二个乘数")),
                List.of("a", "b")),
            toolDef("divide", "除法计算器，计算 a / b（除数不能为零）",
                Map.of("a", propDef("number", "被除数"),
                       "b", propDef("number", "除数")),
                List.of("a", "b")),
            toolDef("greet", "友好问候",
                Map.of("name", propDef("string", "要问候的名字", "World")),
                List.of()),
            toolDef("get_system_info", "获取系统信息（平台、Java 版本等）",
                Map.of(), List.of())
        );

        this.availableResources = List.of();
        this.availablePrompts = List.of();
        this.initialized = true;
        System.out.println("[MCPTool] ✅ 内置演示服务器已就绪，提供 " +
                availableTools.size() + " 个工具");
    }

    /** 构建单个工具定义 Map。 */
    private static Map<String, Object> toolDef(String name, String description,
                                                Map<String, Object> properties,
                                                List<String> required) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", description);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        map.put("inputSchema", schema);
        return map;
    }

    /** 构建单个属性定义。 */
    private static Map<String, Object> propDef(String type, String description) {
        return propDef(type, description, null);
    }

    private static Map<String, Object> propDef(String type, String description, Object defaultValue) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", type);
        prop.put("description", description);
        if (defaultValue != null) prop.put("default", defaultValue);
        return prop;
    }

    // ==================== 外部 MCP 工具发现 ====================

    private void discoverAll() {
        try {
            var toolsResult = client.listTools();
            if (toolsResult != null && toolsResult.tools() != null) {
                this.availableTools = toolsResult.tools().stream()
                        .map(this::toolToMap)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            System.out.println("[MCPTool] 工具发现失败: " + e.getMessage());
            this.availableTools = List.of();
        }

        try {
            var resourcesResult = client.listResources();
            if (resourcesResult != null && resourcesResult.resources() != null) {
                this.availableResources = resourcesResult.resources().stream()
                        .map(r -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("uri", r.uri());
                            m.put("name", r.name());
                            m.put("description", r.description() != null ? r.description() : "");
                            m.put("mimeType", r.mimeType() != null ? r.mimeType() : "");
                            return m;
                        })
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            System.out.println("[MCPTool] 资源发现失败: " + e.getMessage());
            this.availableResources = List.of();
        }

        try {
            var promptsResult = client.listPrompts();
            if (promptsResult != null && promptsResult.prompts() != null) {
                this.availablePrompts = promptsResult.prompts().stream()
                        .map(p -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name", p.name());
                            m.put("description", p.description() != null ? p.description() : "");
                            return m;
                        })
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            System.out.println("[MCPTool] 提示词发现失败: " + e.getMessage());
            this.availablePrompts = List.of();
        }
    }

    /** 将 MCP 工具定义转换为 Map（含 name, description, inputSchema）。 */
    private Map<String, Object> toolToMap(McpSchema.Tool tool) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", tool.name());
        map.put("description", tool.description() != null ? tool.description() : "");

        McpSchema.JsonSchema schema = tool.inputSchema();
        Map<String, Object> schemaMap = new LinkedHashMap<>();
        schemaMap.put("type", schema.type() != null ? schema.type() : "object");
        schemaMap.put("properties", schema.properties() != null
                ? schema.properties() : Map.of());
        schemaMap.put("required", schema.required() != null
                ? schema.required() : List.of());
        map.put("inputSchema", schemaMap);
        return map;
    }

    // ==================== Tool 接口 ====================

    @Override
    public String run(Map<String, Object> parameters) {
        if (!initialized) {
            return "❌ MCP 工具未初始化: " + (initError != null ? initError : "未知错误");
        }

        String action = ((String) parameters.getOrDefault("action", "")).toLowerCase();
        if (action.isEmpty() && parameters.containsKey("tool_name")) {
            action = "call_tool";
        }

        return switch (action) {
            case "list_tools"      -> handleListTools();
            case "call_tool"       -> handleCallTool(parameters);
            case "list_resources"  -> handleListResources();
            case "read_resource"   -> handleReadResource(parameters);
            case "list_prompts"    -> handleListPrompts();
            case "get_prompt"      -> handleGetPrompt(parameters);
            default -> "❌ 不支持的操作: " + action
                     + "。可用: list_tools, call_tool, list_resources, read_resource, list_prompts, get_prompt";
        };
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("action", "string",
                "操作类型: list_tools, call_tool, list_resources, read_resource, list_prompts, get_prompt",
                true, null),
            new ToolParameter("tool_name", "string",
                "要调用的工具名称（call_tool 需要）", false, null),
            new ToolParameter("arguments", "object",
                "工具参数，JSON 对象格式（call_tool 需要）", false, null),
            new ToolParameter("uri", "string",
                "资源 URI（read_resource 需要）", false, null),
            new ToolParameter("prompt_name", "string",
                "提示词名称（get_prompt 需要）", false, null),
            new ToolParameter("prompt_arguments", "object",
                "提示词参数（get_prompt 可选）", false, null)
        );
    }

    // ==================== 操作处理 ====================

    private String handleListTools() {
        if (availableTools.isEmpty()) {
            return "未发现任何 MCP 工具";
        }
        StringBuilder sb = new StringBuilder(builtin ? "内置演示服务器提供了 " : "MCP 服务器提供了 ")
                .append(availableTools.size()).append(" 个工具:\n");
        for (var tool : availableTools) {
            sb.append("- ").append(tool.get("name"))
              .append(": ").append(tool.get("description")).append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String handleCallTool(Map<String, Object> params) {
        String toolName = (String) params.get("tool_name");
        if (toolName == null || toolName.isBlank()) {
            return "❌ call_tool 需要 tool_name 参数";
        }

        Map<String, Object> arguments;
        Object argsObj = params.get("arguments");
        if (argsObj instanceof Map) {
            arguments = (Map<String, Object>) argsObj;
        } else if (argsObj instanceof String s && !s.isBlank()) {
            arguments = GSON.fromJson(s, new TypeToken<Map<String, Object>>() {}.getType());
        } else {
            arguments = Map.of();
        }

        try {
            return callTool(toolName, arguments);
        } catch (Exception e) {
            return "❌ 工具调用失败 [" + toolName + "]: " + e.getMessage();
        }
    }

    /** 直接调用工具（供 MCPWrappedTool 使用），内置/外部统一入口。 */
    String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("MCP 客户端未初始化");
        }
        if (builtin) {
            return executeBuiltinTool(toolName, arguments);
        }

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(toolName, arguments));

        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "✅ 工具执行完成（无输出）";
        }

        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                if (tc.text() != null) sb.append(tc.text());
            } else if (content instanceof McpSchema.ImageContent ic) {
                sb.append("[图片: ").append(ic.mimeType() != null ? ic.mimeType() : "unknown")
                  .append(", data:").append(ic.data() != null ? ic.data().length() + " bytes" : "0 bytes")
                  .append("]");
            } else if (content instanceof McpSchema.AudioContent ac) {
                sb.append("[音频: ").append(ac.mimeType() != null ? ac.mimeType() : "unknown")
                  .append(", data:").append(ac.data() != null ? ac.data().length() + " bytes" : "0 bytes")
                  .append("]");
            } else if (content instanceof McpSchema.ResourceContent rc) {
                sb.append("[资源: ").append(rc.uri())
                  .append(" (").append(rc.mimeType() != null ? rc.mimeType() : "unknown").append(")");
                if (rc.description() != null && !rc.description().isEmpty()) {
                    sb.append(" ").append(rc.description());
                }
                sb.append("]");
            } else {
                sb.append("[").append(content.type()).append(" 内容]");
            }
            sb.append("\n");
        }

        if (result.isError() != null && result.isError()) {
            sb.insert(0, "⚠️ 工具返回错误:\n");
        }

        return !sb.isEmpty() ? sb.toString().stripTrailing() : "✅ 工具执行完成";
    }

    // ==================== 内置工具实现 ====================

    /** 执行内置演示工具（纯 Java 实现，不走 MCP 协议）。 */
    @SuppressWarnings("unchecked")
    private String executeBuiltinTool(String toolName, Map<String, Object> args) {
        double a = toDouble(args.get("a"));
        double b = toDouble(args.get("b"));

        return switch (toolName) {
            case "add"      -> String.valueOf(a + b);
            case "subtract" -> String.valueOf(a - b);
            case "multiply" -> String.valueOf(a * b);
            case "divide" -> {
                if (b == 0.0) yield "❌ 错误: 除数不能为零";
                yield String.valueOf(a / b);
            }
            case "greet" -> {
                String name = (String) args.getOrDefault("name", "World");
                yield "Hello, " + name + "! 欢迎使用 HelloAgents MCP 工具！";
            }
            case "get_system_info" -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("platform", System.getProperty("os.name", "Unknown"));
                info.put("java_version", System.getProperty("java.version", "Unknown"));
                info.put("server_name", "HelloAgents-BuiltinServer");
                info.put("tools_count", availableTools.size());
                yield GSON.toJson(info);
            }
            default -> "❌ 未知的内置工具: " + toolName;
        };
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }

    // ==================== 资源 / 提示词 ====================

    private String handleListResources() {
        if (builtin) return "内置演示服务器无 MCP 资源";
        if (availableResources.isEmpty()) return "未发现任何 MCP 资源";

        StringBuilder sb = new StringBuilder("MCP 服务器提供了 ")
                .append(availableResources.size()).append(" 个资源:\n");
        for (var r : availableResources) {
            sb.append("- ").append(r.get("uri"))
              .append(" (").append(r.get("name")).append(")");
            String desc = (String) r.get("description");
            if (desc != null && !desc.isEmpty()) sb.append(": ").append(desc);
            sb.append("\n");
        }
        return sb.toString();
    }

    private String handleReadResource(Map<String, Object> params) {
        if (builtin) return "内置演示服务器无 MCP 资源";

        String uri = (String) params.get("uri");
        if (uri == null || uri.isBlank()) return "❌ read_resource 需要 uri 参数";

        try {
            var result = client.readResource(new McpSchema.ReadResourceRequest(uri));
            if (result == null || result.contents() == null || result.contents().isEmpty()) {
                return "✅ 资源为空: " + uri;
            }
            StringBuilder sb = new StringBuilder("资源: ").append(uri).append("\n");
            for (var content : result.contents()) {
                if (content instanceof McpSchema.TextResourceContents trc) {
                    sb.append(trc.text() != null ? trc.text() : "");
                } else if (content instanceof McpSchema.BlobResourceContents brc) {
                    sb.append("[Blob: ").append(brc.mimeType() != null ? brc.mimeType() : "unknown")
                      .append(", ").append(brc.blob() != null ? brc.blob().length() + " bytes" : "0 bytes")
                      .append("]");
                } else {
                    sb.append(content.toString());
                }
                sb.append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "❌ 读取资源失败: " + e.getMessage();
        }
    }

    private String handleListPrompts() {
        if (builtin) return "内置演示服务器无 MCP 提示词";
        if (availablePrompts.isEmpty()) return "未发现任何 MCP 提示词";

        StringBuilder sb = new StringBuilder("MCP 服务器提供了 ")
                .append(availablePrompts.size()).append(" 个提示词:\n");
        for (var p : availablePrompts) {
            sb.append("- ").append(p.get("name"))
              .append(": ").append(p.get("description")).append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String handleGetPrompt(Map<String, Object> params) {
        if (builtin) return "内置演示服务器无 MCP 提示词";

        String promptName = (String) params.get("prompt_name");
        if (promptName == null || promptName.isBlank()) {
            return "❌ get_prompt 需要 prompt_name 参数";
        }

        Map<String, Object> promptArgs;
        Object argsObj = params.get("prompt_arguments");
        if (argsObj instanceof Map) {
            promptArgs = (Map<String, Object>) argsObj;
        } else if (argsObj instanceof String s && !s.isBlank()) {
            promptArgs = GSON.fromJson(s, new TypeToken<Map<String, Object>>() {}.getType());
        } else {
            promptArgs = Map.of();
        }

        try {
            var result = client.getPrompt(
                    new McpSchema.GetPromptRequest(promptName, promptArgs));
            if (result == null || result.messages() == null || result.messages().isEmpty()) {
                return "✅ 提示词为空: " + promptName;
            }
            StringBuilder sb = new StringBuilder("提示词: ").append(promptName).append("\n");
            for (var msg : result.messages()) {
                sb.append("[").append(msg.role()).append("] ");
                var pc = msg.content();
                if (pc instanceof McpSchema.TextContent tc) {
                    sb.append(tc.text() != null ? tc.text() : "");
                } else {
                    sb.append(pc != null ? pc.toString() : "");
                }
                sb.append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "❌ 获取提示词失败: " + e.getMessage();
        }
    }

    // ==================== 展开工具 ====================

    /**
     * 获取展开的工具列表。
     * 将 MCP 服务器（或内置演示服务器）的每个工具包装为独立的 Tool 对象，
     * 可直接注册到 ToolRegistry 供 LLM 调用。
     *
     * 用法：
     * <pre>
     *   MCPTool mcp = new MCPTool("calculator");
     *   for (MCPWrappedTool t : mcp.getExpandedTools()) {
     *       agent.getToolRegistry().registerTool(t);
     *   }
     *   // 现在 LLM 可以直接调用 calculator_add、calculator_subtract 等
     * </pre>
     */
    public List<MCPWrappedTool> getExpandedTools() {
        if (!autoExpand || availableTools.isEmpty()) return List.of();

        List<MCPWrappedTool> result = new ArrayList<>();
        for (var toolInfo : availableTools) {
            result.add(new MCPWrappedTool(this, toolInfo, prefix));
        }
        return result;
    }

    @Override
    public boolean expandable() { return autoExpand && !availableTools.isEmpty(); }

    // ==================== 访问器 ====================

    public boolean isInitialized() { return initialized; }
    public boolean isBuiltin() { return builtin; }
    public String getInitError() { return initError; }
    public List<Map<String, Object>> getAvailableTools() { return new ArrayList<>(availableTools); }
    public List<Map<String, Object>> getAvailableResources() { return new ArrayList<>(availableResources); }
    public List<Map<String, Object>> getAvailablePrompts() { return new ArrayList<>(availablePrompts); }
    public boolean isAutoExpand() { return autoExpand; }
    public String getPrefix() { return prefix; }

    // ==================== 生命周期 ====================

    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                System.out.println("[MCPTool] 关闭客户端时出错: " + e.getMessage());
            }
        }
    }

    /** 重新生成描述（反映已发现的工具列表）。 */
    public String generateDescription() {
        if (!initialized) {
            return "MCP 工具服务器 [未连接]: " + String.join(" ", serverCommand);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(builtin ? "内置演示服务器" : "MCP 工具服务器")
          .append("，提供 ").append(availableTools.size()).append(" 个工具");

        if (!autoExpand) {
            sb.append(":\n");
            for (var tool : availableTools) {
                sb.append("  - ").append(tool.get("name"))
                  .append(": ").append(tool.get("description")).append("\n");
            }
            sb.append("\n调用格式: {\"action\": \"call_tool\", \"tool_name\": \"工具名\", \"arguments\": {...}}");
        } else {
            sb.append("（已自动展开为独立工具）");
        }
        return sb.toString();
    }
}
