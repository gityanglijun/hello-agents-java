package com.example.agent.tool;

import java.util.*;

/**
 * 单个 MCP 服务器工具的包装器 — 将 MCP 工具暴露为独立的 Tool 对象。
 *
 * 每个 MCPWrappedTool 对应 MCP 服务器上的一个工具，
 * 将 MCP 工具的 JSON Schema 转换为 ToolParameter，
 * 执行时委托给父 MCPTool 的 callTool 方法。
 */
public class MCPWrappedTool extends Tool {

    private final MCPTool parent;
    private final String serverToolName;
    private final Map<String, Object> toolInfo;

    public MCPWrappedTool(MCPTool parent, Map<String, Object> toolInfo, String prefix) {
        super(prefix + toolInfo.get("name"),
              (String) toolInfo.getOrDefault("description", "MCP 工具: " + toolInfo.get("name")));
        this.parent = parent;
        this.serverToolName = (String) toolInfo.get("name");
        this.toolInfo = toolInfo;
    }

    @Override
    public String run(Map<String, Object> parameters) {
        try {
            return parent.callTool(serverToolName, parameters);
        } catch (Exception e) {
            return "❌ MCP 工具调用失败 [" + serverToolName + "]: " + e.getMessage();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ToolParameter> getParameters() {
        // 从 MCP 工具的 inputSchema 转换为 ToolParameter 列表
        Map<String, Object> schema = (Map<String, Object>) toolInfo.get("inputSchema");
        if (schema == null) return List.of();

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties == null) return List.of();

        List<String> required = (List<String>) schema.get("required");
        Set<String> requiredSet = required != null ? new HashSet<>(required) : Set.of();

        List<ToolParameter> params = new ArrayList<>();
        for (var entry : properties.entrySet()) {
            String paramName = entry.getKey();
            Map<String, Object> propDef = (Map<String, Object>) entry.getValue();
            String type = (String) propDef.getOrDefault("type", "string");
            String description = (String) propDef.getOrDefault("description", "参数 " + paramName);
            boolean isRequired = requiredSet.contains(paramName);

            params.add(new ToolParameter(paramName, type, description, isRequired, null));
        }
        return params;
    }

    /** 获取原始 MCP 工具信息（名称、描述、schema） */
    public Map<String, Object> getToolInfo() {
        return new LinkedHashMap<>(toolInfo);
    }
}
