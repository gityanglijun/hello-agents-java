package com.example.agent.app;

import com.example.agent.tool.*;

import java.util.*;

/**
 * 协议工具冒烟测试 — 覆盖 MCPTool / A2ATool / ANPTool 的核心逻辑。
 *
 * 运行: mvn exec:java -Dexec.mainClass=com.example.agent.app.ProtocolToolsSmokeTest
 */
public class ProtocolToolsSmokeTest {

    private int passed, failed;

    public static void main(String[] args) {
        new ProtocolToolsSmokeTest().runAll();
    }

    void runAll() {
        System.out.println("=".repeat(60));
        System.out.println("协议工具冒烟测试");
        System.out.println("=".repeat(60));

        testMcpBuiltin();
        testMcpExpansion();
        testMcpErrors();
        testA2aParameters();
        testAnpServices();
        testAnpNodesAndRouting();
        testAnpStats();
        testToolRegistryIntegration();

        System.out.println("\n" + "=".repeat(60));
        System.out.printf("结果: ✅ %d 通过  ❌ %d 失败%n", passed, failed);
        System.out.println("=".repeat(60));
    }

    // ==================== MCPTool: 内置演示服务器 ====================

    void testMcpBuiltin() {
        section("MCPTool 内置演示服务器");

        MCPTool mcp = new MCPTool("calc");
        check("内置服务器已初始化", mcp.isInitialized());
        check("标记为 builtin", mcp.isBuiltin());
        check("有 6 个工具", mcp.getAvailableTools().size() == 6);

        // list_tools
        String listResult = mcp.run(Map.of("action", "list_tools"));
        check("list_tools 包含 add", listResult.contains("add"));
        check("list_tools 包含 greet", listResult.contains("greet"));
        System.out.println("   " + listResult.replace("\n", "\n   "));

        // call_tool: add
        String addResult = mcp.run(Map.of(
                "action", "call_tool",
                "tool_name", "add",
                "arguments", Map.of("a", 25, "b", 16)));
        check("add(25, 16) = 41.0", addResult.contains("41.0"));
        System.out.println("   add(25, 16) → " + addResult);

        // call_tool: subtract
        String subResult = mcp.run(Map.of(
                "tool_name", "subtract",  // 智能推断 action
                "arguments", Map.of("a", 10, "b", 3)));
        check("subtract(10, 3) = 7.0", subResult.contains("7.0"));

        // call_tool: multiply
        String mulResult = mcp.run(Map.of(
                "action", "call_tool",
                "tool_name", "multiply",
                "arguments", Map.of("a", 6, "b", 7)));
        check("multiply(6, 7) = 42.0", mulResult.contains("42.0"));

        // call_tool: divide
        String divResult = mcp.run(Map.of(
                "action", "call_tool",
                "tool_name", "divide",
                "arguments", Map.of("a", 10, "b", 3)));
        check("divide(10, 3) ≈ 3.333", divResult.contains("3.33"));

        String divZero = mcp.run(Map.of(
                "action", "call_tool",
                "tool_name", "divide",
                "arguments", Map.of("a", 5, "b", 0)));
        check("divide(5, 0) 报错", divZero.contains("除数不能为零"));
        System.out.println("   divide(5, 0) → " + divZero);

        // call_tool: greet
        String greetResult = mcp.run(Map.of(
                "action", "call_tool",
                "tool_name", "greet",
                "arguments", Map.of("name", "Claude")));
        check("greet(Claude) 含 Hello", greetResult.contains("Hello") && greetResult.contains("Claude"));
        System.out.println("   greet(Claude) → " + greetResult);

        // call_tool: greet 默认值
        String greetDefault = mcp.run(Map.of(
                "action", "call_tool",
                "tool_name", "greet",
                "arguments", Map.of()));
        check("greet() 默认 World", greetDefault.contains("World"));
        System.out.println("   greet() → " + greetDefault);

        // call_tool: get_system_info
        String infoResult = mcp.run(Map.of(
                "action", "call_tool",
                "tool_name", "get_system_info",
                "arguments", Map.of()));
        check("get_system_info 含 platform", infoResult.contains("platform"));
        check("get_system_info 含 java_version", infoResult.contains("java.version"));
        System.out.println("   get_system_info → " + infoResult);

        // call_tool: 字符串参数类型转换
        String strAdd = mcp.run(Map.of(
                "action", "call_tool",
                "tool_name", "add",
                "arguments", Map.of("a", "3.5", "b", "2.5")));
        check("add('3.5', '2.5') = 6.0 (字符串转数字)", strAdd.contains("6.0"));

        // 资源/提示词（内置服务器无）
        check("内置服务器无资源", mcp.run(Map.of("action", "list_resources")).contains("无 MCP 资源"));
        check("内置服务器无提示词", mcp.run(Map.of("action", "list_prompts")).contains("无 MCP 提示词"));

        mcp.close();
    }

    // ==================== MCPTool: 工具展开 ====================

    void testMcpExpansion() {
        section("MCPTool 工具展开 (MCPWrappedTool)");

        MCPTool mcp = new MCPTool("calc");
        List<MCPWrappedTool> expanded = mcp.getExpandedTools();
        check("展开后 6 个工具", expanded.size() == 6);

        Set<String> names = new HashSet<>();
        for (var t : expanded) {
            names.add(t.name());
        }
        check("含 calc_add", names.contains("calc_add"));
        check("含 calc_subtract", names.contains("calc_subtract"));
        check("含 calc_multiply", names.contains("calc_multiply"));
        check("含 calc_divide", names.contains("calc_divide"));
        check("含 calc_greet", names.contains("calc_greet"));
        check("含 calc_get_system_info", names.contains("calc_get_system_info"));

        // 展开工具的参数 schema
        MCPWrappedTool addTool = expanded.stream()
                .filter(t -> t.name().equals("calc_add")).findFirst().orElseThrow();
        List<ToolParameter> params = addTool.getParameters();
        check("calc_add 有 2 个参数", params.size() == 2);
        check("calc_add 参数a required", params.get(0).required());
        check("calc_add 参数b required", params.get(1).required());

        // 展开工具直接调用
        String result = addTool.run(Map.of("a", 100, "b", 200));
        check("calc_add(100, 200) = 300.0", result.contains("300.0"));
        System.out.println("   calc_add(100, 200) → " + result);

        // greet 工具只有 1 个非必填参数
        MCPWrappedTool greetTool = expanded.stream()
                .filter(t -> t.name().equals("calc_greet")).findFirst().orElseThrow();
        List<ToolParameter> greetParams = greetTool.getParameters();
        check("calc_greet 有 1 个参数", greetParams.size() == 1);
        check("calc_greet name 非必填", !greetParams.get(0).required());

        mcp.close();
    }

    // ==================== MCPTool: 错误处理 ====================

    void testMcpErrors() {
        section("MCPTool 错误处理");

        MCPTool mcp = new MCPTool("test");

        // 不存在的工具
        String badTool = mcp.run(Map.of(
                "action", "call_tool",
                "tool_name", "nonexistent",
                "arguments", Map.of()));
        check("不存在的工具报错", badTool.contains("未知的内置工具"));
        System.out.println("   不存在的工具 → " + badTool);

        // 不支持的操作
        String badAction = mcp.run(Map.of("action", "fly_to_moon"));
        check("不支持的操作报错", badAction.contains("不支持的操作"));
        System.out.println("   不支持的操作 → " + badAction);

        // 缺少 tool_name
        String noToolName = mcp.run(Map.of("action", "call_tool"));
        check("缺少 tool_name 报错", noToolName.contains("需要 tool_name"));

        // 空参构造
        MCPTool defaultMcp = new MCPTool();
        check("默认名称 mcp", defaultMcp.name().equals("mcp"));
        check("默认 builtin", defaultMcp.isBuiltin());
        check("默认 6 工具", defaultMcp.getAvailableTools().size() == 6);

        mcp.close();
        defaultMcp.close();
    }

    // ==================== A2ATool: 参数和 schema ====================

    void testA2aParameters() {
        section("A2ATool 参数 schema");

        A2ATool a2a = new A2ATool("http://localhost:9999/test");
        check("A2A URL 已保存", a2a.getAgentUrl().equals("http://localhost:9999/test"));

        // 自定义名称
        A2ATool named = new A2ATool("tech_expert", "http://localhost:5000");
        check("自定义名称", named.name().equals("tech_expert"));

        // 自定义描述
        A2ATool described = new A2ATool("helper", "http://localhost:5000", "技术专家");
        check("自定义描述", described.description().contains("技术专家"));

        // 参数定义
        List<ToolParameter> params = a2a.getParameters();
        check("A2A 有 2 个参数", params.size() == 2);
        check("action 必填", params.get(0).name().equals("action") && params.get(0).required());
        check("question 非必填", params.get(1).name().equals("question") && !params.get(1).required());

        // 缺少 action
        String noAction = a2a.run(Map.of());
        check("缺少 action 报错", noAction.contains("必须指定 action"));

        // 不支持的操作
        String badAction = a2a.run(Map.of("action", "dance"));
        check("不支持的操作报错", badAction.contains("不支持的操作"));

        // get_info 会尝试连接（预期失败，但不崩溃）
        String infoResult = a2a.run(Map.of("action", "get_info"));
        check("get_info 不崩溃", infoResult != null && !infoResult.isEmpty());
        System.out.println("   get_info → " + infoResult);

        // ask 缺少 question
        String noQuestion = a2a.run(Map.of("action", "ask"));
        check("缺少 question 报错", noQuestion.contains("需要 question 参数"));

        // URL 去尾斜杠
        A2ATool trailing = new A2ATool("http://localhost:5000/");
        check("去掉尾部斜杠", trailing.getAgentUrl().equals("http://localhost:5000"));

        System.out.println("   (A2A 完整功能需实际运行 A2A Agent 服务)");
    }

    // ==================== ANPTool: 服务管理 ====================

    void testAnpServices() {
        section("ANPTool 服务管理");

        ANPDiscovery discovery = new ANPDiscovery();
        ANPNetwork network = new ANPNetwork();
        ANPTool anp = new ANPTool("anp", discovery, network);

        // register_service
        String regResult = anp.run(Map.of(
                "action", "register_service",
                "service_id", "calc-1",
                "service_type", "calculator",
                "endpoint", "http://localhost:5001",
                "metadata", Map.of("version", "1.0")));
        check("注册 calc-1", regResult.contains("已注册服务") && regResult.contains("calc-1"));
        System.out.println("   " + regResult);

        anp.run(Map.of(
                "action", "register_service",
                "service_id", "calc-2",
                "service_type", "calculator",
                "endpoint", "http://localhost:5002"));
        anp.run(Map.of(
                "action", "register_service",
                "service_id", "weather-1",
                "service_type", "weather",
                "endpoint", "http://localhost:5003"));

        // discover_services（全部）
        String allSvcs = anp.run(Map.of("action", "discover_services"));
        check("发现 3 个服务", allSvcs.contains("3 个服务"));
        System.out.println("   " + allSvcs.replace("\n", "\n   "));

        // discover_services（按类型）
        String calcSvcs = anp.run(Map.of(
                "action", "discover_services",
                "service_type", "calculator"));
        check("发现 2 个 calculator", calcSvcs.contains("2 个服务") && calcSvcs.contains("calc-1") && calcSvcs.contains("calc-2"));

        String weatherSvcs = anp.run(Map.of(
                "action", "discover_services",
                "service_type", "weather"));
        check("发现 1 个 weather", weatherSvcs.contains("weather-1"));

        String noMatch = anp.run(Map.of(
                "action", "discover_services",
                "service_type", "translator"));
        check("translator 无匹配", noMatch.contains("没有找到服务"));

        // unregister_service
        String unregOk = anp.run(Map.of(
                "action", "unregister_service",
                "service_id", "calc-2"));
        check("注销 calc-2", unregOk.contains("已注销"));

        String unregFail = anp.run(Map.of(
                "action", "unregister_service",
                "service_id", "nonexistent"));
        check("注销不存在服务报错", unregFail.contains("不存在"));

        // 注销后只剩 2 个
        String afterUnreg = anp.run(Map.of("action", "discover_services"));
        check("注销后剩 2 个", afterUnreg.contains("2 个服务"));

        // 缺少参数
        String missingParams = anp.run(Map.of("action", "register_service"));
        check("注册缺参数报错", missingParams.contains("需要 service_id"));

        // accessor
        check("getDiscovery 一致", anp.getDiscovery() == discovery);
        check("getNetwork 一致", anp.getNetwork() == network);
    }

    // ==================== ANPTool: 节点和路由 ====================

    void testAnpNodesAndRouting() {
        section("ANPTool 节点和路由");

        ANPDiscovery discovery = new ANPDiscovery();
        ANPNetwork network = new ANPNetwork();
        ANPTool anp = new ANPTool("anp", discovery, network);

        // 添加节点（全互联拓扑）
        anp.run(Map.of("action", "add_node",
                "node_id", "agent-1", "endpoint", "http://host1:5000"));
        anp.run(Map.of("action", "add_node",
                "node_id", "agent-2", "endpoint", "http://host2:5000"));
        anp.run(Map.of("action", "add_node",
                "node_id", "agent-3", "endpoint", "http://host3:5000"));

        // 路由消息
        String route = anp.run(Map.of(
                "action", "route_message",
                "from_node", "agent-1",
                "to_node", "agent-3",
                "message", Map.of("text", "Hello")));
        check("路由 agent-1→agent-3 含箭头", route.contains("→"));
        check("路由含 agent-1", route.contains("agent-1"));
        check("路由含 agent-3", route.contains("agent-3"));
        System.out.println("   " + route);

        // 不存在的节点
        String badRoute = anp.run(Map.of(
                "action", "route_message",
                "from_node", "agent-1",
                "to_node", "agent-99",
                "message", Map.of()));
        check("路由到不存在节点失败", badRoute.contains("无法找到"));

        // 缺少参数
        String missingNode = anp.run(Map.of("action", "add_node"));
        check("添加节点缺参数报错", missingNode.contains("需要 node_id"));

        String missingRoute = anp.run(Map.of("action", "route_message"));
        check("路由缺参数报错", missingRoute.contains("需要 from_node"));
    }

    // ==================== ANPTool: 统计 ====================

    void testAnpStats() {
        section("ANPTool 统计");

        ANPDiscovery discovery = new ANPDiscovery();
        ANPNetwork network = new ANPNetwork();
        ANPTool anp = new ANPTool("anp", discovery, network);

        // 初始状态
        String emptyStats = anp.run(Map.of("action", "get_stats"));
        check("空网络 node_count=0", emptyStats.contains("node_count: 0"));
        check("空网络已注册服务=0", emptyStats.contains("已注册服务: 0"));
        System.out.println("   " + emptyStats.replace("\n", "\n   "));

        // 添加节点和服务后
        anp.run(Map.of("action", "add_node",
                "node_id", "n1", "endpoint", "http://a:1"));
        anp.run(Map.of("action", "add_node",
                "node_id", "n2", "endpoint", "http://b:1"));
        anp.run(Map.of("action", "register_service",
                "service_id", "s1", "service_type", "calc",
                "endpoint", "http://c:1"));

        String fullStats = anp.run(Map.of("action", "get_stats"));
        check("有节点后 node_count=2", fullStats.contains("node_count: 2"));
        check("全互联 edge_count=1", fullStats.contains("edge_count: 1"));
        check("有服务后 已注册服务: 1", fullStats.contains("已注册服务: 1"));
        System.out.println("   " + fullStats.replace("\n", "\n   "));

        // ANPNetwork 子 API
        check("network nodeCount=2", network.nodeCount() == 2);
        check("discovery count=1", discovery.count() == 1);
        check("discovery 含 calculator 类型", discovery.serviceTypes().contains("calculator"));
    }

    // ==================== ToolRegistry 集成 ====================

    void testToolRegistryIntegration() {
        section("ToolRegistry 集成");

        ToolRegistry registry = new ToolRegistry();

        // 注册 MCPTool（内置）
        MCPTool mcp = new MCPTool("mcp1");
        registry.registerTool(mcp);
        check("registry 含 mcp1", registry.has("mcp1"));

        // 注册展开工具
        for (MCPWrappedTool t : mcp.getExpandedTools()) {
            registry.registerTool(t);
        }
        check("registry 含 mcp1_add", registry.has("mcp1_add"));
        check("registry 含 mcp1_greet", registry.has("mcp1_greet"));

        // 注册 A2ATool
        A2ATool a2a = new A2ATool("http://localhost:9999");
        registry.registerTool(a2a);
        check("registry 含 a2a", registry.has("a2a"));

        // 注册 ANPTool
        ANPTool anp = new ANPTool("net");
        registry.registerTool(anp);
        check("registry 含 net", registry.has("net"));

        // listTools
        List<String> tools = registry.listTools();
        check("registry 至少有 9 个工具", tools.size() >= 9);
        System.out.println("   工具列表: " + tools);

        // getAllSchemas（用于 OpenAI function calling）
        List<Map<String, Object>> schemas = registry.getAllSchemas();
        check("schemas 数量匹配", schemas.size() == tools.size());

        // 第一个 schema 结构检查
        Map<String, Object> schema = schemas.get(0);
        check("schema 含 type: function",
                "function".equals(schema.get("type")));
        @SuppressWarnings("unchecked")
        Map<String, Object> func = (Map<String, Object>) schema.get("function");
        check("schema 含 function.name", func.containsKey("name"));
        check("schema 含 function.parameters", func.containsKey("parameters"));

        // executeTool 走 Tool 对象
        try {
            String result = registry.executeTool("mcp1_add", Map.of("a", 1, "b", 2));
            check("executeTool mcp1_add(1,2)=3.0", result.contains("3.0"));
        } catch (Exception e) {
            check("executeTool 不抛异常", false);
            System.out.println("   " + e.getMessage());
        }

        // executeTool 不存在的工具（返回错误字符串，不抛异常）
        try {
            String notFoundResult = registry.executeTool("no_such_tool", Map.of());
            check("executeTool 不存在工具返回错误", notFoundResult.contains("未找到工具"));
        } catch (Exception e) {
            check("executeTool 不存在工具应不抛异常", false);
        }

        // ANPTool 通过 registry 执行（net 工具自身 action 分发）
        try {
            String anpRegResult = registry.executeTool("net", Map.of(
                    "action", "register_service",
                    "service_id", "s1",
                    "service_type", "test",
                    "endpoint", "http://x:1"));
            check("registry 执行 ANP register", anpRegResult.contains("已注册"));
        } catch (Exception e) {
            check("registry 执行 ANP 抛异常: " + e.getMessage(), false);
        }

        // 取消注册
        registry.unregister("mcp1");
        check("取消注册 mcp1 后不存在", !registry.has("mcp1"));
        // 展开的子工具仍然存在（独立注册的）
        check("展开工具仍然存在", registry.has("mcp1_add"));

        mcp.close();
    }

    // ==================== 辅助 ====================

    void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✅ " + label);
        } else {
            failed++;
            System.out.println("  ❌ " + label);
        }
    }
}
