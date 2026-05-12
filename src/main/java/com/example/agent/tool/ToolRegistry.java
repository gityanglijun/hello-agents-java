package com.example.agent.tool;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class ToolRegistry {

    // ========== 旧版 Method 工具（向后兼容） ==========

    public static class ToolEntry {
        public final Method method;
        public final String description;

        public ToolEntry(Method method, String description) {
            this.method = method;
            this.description = description;
        }
    }

    private final Map<String, ToolEntry> methodTools = new HashMap<>();

    // ========== 新版 Tool 对象注册 ==========

    private final Map<String, Tool> tools = new HashMap<>();

    // ========== 函数式工具 ==========

    public static class FunctionTool {
        public final String description;
        public final Function<String, String> func;

        public FunctionTool(String description, Function<String, String> func) {
            this.description = description;
            this.func = func;
        }
    }

    private final Map<String, FunctionTool> functions = new HashMap<>();

    // ========== 注册方法 ==========

    /** 注册 Tool 对象（默认自动展开可展开工具） */
    public void registerTool(Tool tool) {
        registerTool(tool, true);
    }

    /**
     * 注册 Tool 对象，可选自动展开。
     *
     * 如注册 MCPTool 并启用 auto_expand，则 MCP 服务器中的每个工具
     * 会被包装为独立的 MCPWrappedTool 分别注册。
     */
    public void registerTool(Tool tool, boolean autoExpand) {
        if (autoExpand && tool.expandable()) {
            List<? extends Tool> expanded = tool.getExpandedTools();
            if (!expanded.isEmpty()) {
                for (Tool subTool : expanded) {
                    if (tools.containsKey(subTool.name())) {
                        System.out.println("⚠️ 警告:工具 '" + subTool.name() + "' 已存在，将被覆盖。");
                    }
                    tools.put(subTool.name(), subTool);
                }
                System.out.println("✅ 工具 '" + tool.name() + "' 已展开为 " + expanded.size() + " 个独立工具");
                return;
            }
        }

        // 不展开或无法展开
        if (tools.containsKey(tool.name())) {
            System.out.println("⚠️ 警告:工具 '" + tool.name() + "' 已存在，将被覆盖。");
        }
        tools.put(tool.name(), tool);
        System.out.println("✅ 工具 '" + tool.name() + "' 已注册。");
    }

    /** 注册函数作为工具（简便方式） */
    public void registerFunction(String name, String description, Function<String, String> func) {
        if (functions.containsKey(name)) {
            System.out.println("⚠️ 警告:工具 '" + name + "' 已存在，将被覆盖。");
        }
        functions.put(name, new FunctionTool(description, func));
        System.out.println("✅ 工具 '" + name + "' 已注册。");
    }

    /** 注册旧版 Method 工具 */
    public void register(String name, String description, Method method) {
        methodTools.put(name, new ToolEntry(method, description));
    }

    // ========== 查询方法 ==========

    public boolean has(String name) {
        return tools.containsKey(name) || functions.containsKey(name) || methodTools.containsKey(name);
    }

    public void unregister(String name) {
        tools.remove(name);
        functions.remove(name);
        methodTools.remove(name);
    }

    public List<String> listTools() {
        List<String> names = new ArrayList<>();
        names.addAll(tools.keySet());
        names.addAll(functions.keySet());
        names.addAll(methodTools.keySet());
        return names;
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public FunctionTool getFunction(String name) {
        return functions.get(name);
    }

    public ToolEntry get(String name) {
        return methodTools.get(name);
    }

    public Map<String, ToolEntry> all() {
        return new HashMap<>(methodTools);
    }

    // ========== 工具描述 ==========

    public String describeTools() {
        List<String> descriptions = new ArrayList<>();

        for (Tool tool : tools.values()) {
            descriptions.add("- " + tool.name + ": " + tool.description);
        }
        for (Map.Entry<String, FunctionTool> entry : functions.entrySet()) {
            descriptions.add("- " + entry.getKey() + ": " + entry.getValue().description);
        }
        for (Map.Entry<String, ToolEntry> entry : methodTools.entrySet()) {
            descriptions.add("- " + entry.getKey() + ": " + entry.getValue().description);
        }

        return descriptions.isEmpty() ? "暂无可用工具" : String.join("\n", descriptions);
    }

    // ========== 工具执行 ==========

    /** Execute tool with already-parsed parameters (preferred path — preserves types). */
    public String executeTool(String name, Map<String, Object> params) throws Exception {
        // 优先执行 Tool 对象
        Tool tool = tools.get(name);
        if (tool != null) {
            return tool.run(params);
        }

        // 其次执行函数式工具（将 Map 序列化为参数字符串）
        FunctionTool funcTool = functions.get(name);
        if (funcTool != null) {
            StringBuilder sb = new StringBuilder();
            for (var entry : params.entrySet()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            return funcTool.func.apply(sb.toString());
        }

        // 最后执行旧版 Method 工具
        ToolEntry entry = methodTools.get(name);
        if (entry != null) {
            StringBuilder sb = new StringBuilder();
            for (var e : params.entrySet()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            Object result = entry.method.invoke(null, sb.toString());
            return result != null ? result.toString() : "无返回值";
        }

        return "❌ 错误:未找到工具 '" + name + "'";
    }

    /** @deprecated Use {@link #executeTool(String, Map)} to preserve parameter types. */
    @Deprecated
    public String executeTool(String name, String parameters) throws Exception {
        // 优先执行 Tool 对象
        Tool tool = tools.get(name);
        if (tool != null) {
            Map<String, Object> parsedParams = parseParameters(parameters);
            return tool.run(parsedParams);
        }

        // 其次执行函数式工具
        FunctionTool funcTool = functions.get(name);
        if (funcTool != null) {
            return funcTool.func.apply(parameters);
        }

        // 最后执行旧版 Method 工具
        ToolEntry entry = methodTools.get(name);
        if (entry != null) {
            Object result = entry.method.invoke(null, parameters);
            return result != null ? result.toString() : "无返回值";
        }

        return "❌ 错误:未找到工具 '" + name + "'";
    }

    private Map<String, Object> parseParameters(String parameters) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (parameters == null || parameters.isBlank()) return result;

        if (parameters.contains("=")) {
            if (parameters.contains(",")) {
                String[] pairs = parameters.split(",");
                for (String pair : pairs) {
                    if (pair.contains("=")) {
                        String[] kv = pair.split("=", 2);
                        result.put(kv[0].trim(), kv[1].trim());
                    }
                }
            } else {
                String[] kv = parameters.split("=", 2);
                result.put(kv[0].trim(), kv[1].trim());
            }
        } else {
            result.put("input", parameters);
        }
        return result;
    }

    // ========== OpenAI Function Calling Schema ==========

    /** 获取所有已注册 Tool 对象的 OpenAI function calling schema 列表。 */
    public List<Map<String, Object>> getAllSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (String name : tools.keySet()) {
            Map<String, Object> schema = toOpenaiSchema(name);
            if (schema != null) {
                schemas.add(schema);
            }
        }
        // 函数式工具也用简单 schema
        for (String name : functions.keySet()) {
            Map<String, Object> func = new LinkedHashMap<>();
            func.put("name", name);
            func.put("description", functions.get(name).description);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            params.put("properties", Map.of("input",
                    Map.of("type", "string", "description", "输入参数")));
            params.put("required", List.of("input"));
            func.put("parameters", params);

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "function");
            schema.put("function", func);
            schemas.add(schema);
        }
        return schemas;
    }

    /** 获取所有已注册的 Tool 对象（不含函数式工具）。 */
    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    public Map<String, Object> toOpenaiSchema(String name) {
        Tool tool = tools.get(name);
        if (tool == null) return null;

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ToolParameter param : tool.getParameters()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", param.type());
            prop.put("description", param.description());

            if (param.defaultValue() != null) {
                prop.put("description", param.description() + " (默认: " + param.defaultValue() + ")");
            }

            if ("array".equals(param.type())) {
                Map<String, Object> items = new LinkedHashMap<>();
                items.put("type", "string");
                prop.put("items", items);
            }

            properties.put(param.name(), prop);
            if (param.required()) {
                required.add(param.name());
            }
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name);
        function.put("description", tool.description);
        function.put("parameters", parameters);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");
        schema.put("function", function);

        return schema;
    }
}
