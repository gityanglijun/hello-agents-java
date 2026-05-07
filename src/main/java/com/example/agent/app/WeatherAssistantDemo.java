package com.example.agent.app;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.FunctionCallAgent;
import com.example.agent.tool.MCPTool;
import com.example.agent.tool.MCPWrappedTool;
import com.example.agent.tool.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * 天气助手演示 — 对应 Python 天气 MCP 服务器教程的 Java 版。
 *
 * 架构：
 *   - 优先连接 Python 天气 MCP 服务器（演示跨语言 MCP）
 *   - 若 Python 不可用，降级到 Java 原生 WeatherTool（纯 Java，调 wttr.in API）
 *
 * 工作流:
 *   1. 创建 HelloAgentsLLM
 *   2. 初始化天气工具（MCPTool → 连接 Python 服务器，或 WeatherTool 降级）
 *   3. 创建 FunctionCallAgent（天气助手）
 *   4. demo 模式或 interactive 交互模式
 *
 * 运行:
 *   mvn exec:java -Dexec.mainClass=com.example.agent.app.WeatherAssistantDemo
 *   mvn exec:java -Dexec.mainClass=com.example.agent.app.WeatherAssistantDemo -Dexec.args=demo
 */
public class WeatherAssistantDemo {

    public static void main(String[] args) {
        boolean demoOnly = args.length > 0 && "demo".equalsIgnoreCase(args[0]);

        System.out.println("=".repeat(60));
        System.out.println("天气助手 (Java 版)");
        System.out.println("=".repeat(60));

        // --- 创建 LLM ---
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }

        // --- 初始化天气工具 ---
        ToolRegistry registry = new ToolRegistry();
        boolean usingMcp = setupMcpWeatherServer(registry);
        if (!usingMcp) {
            // 降级：Java 原生天气工具
            System.out.println("🔄 降级到 Java 原生 WeatherTool (wttr.in API)");
            registry.registerTool(new WeatherTool());
        }
        System.out.println("🔧 已注册工具: " + registry.listTools());

        // --- 创建天气助手 Agent ---
        FunctionCallAgent assistant = new FunctionCallAgent(
                "天气助手",
                llm,
                "你是天气助手，可以查询城市天气。\n" +
                "使用天气工具查询指定城市的天气。\n" +
                "支持中文城市名：北京、上海、广州、深圳、杭州、成都、重庆、武汉、西安、南京、天津、苏州。\n" +
                "请用中文回复，包含温度、湿度、天气状况、风速等关键信息，" +
                "并根据天气情况给出合理的出行建议。",
                registry);

        if (demoOnly) {
            runDemo(assistant);
        } else {
            runInteractive(assistant);
        }
    }

    // ==================== MCP 天气服务器 ====================

    /**
     * 尝试连接 Python 天气 MCP 服务器。
     * @return true 如果连接成功
     */
    private static boolean setupMcpWeatherServer(ToolRegistry registry) {
        String scriptPath = findWeatherServerScript();
        if (scriptPath == null) {
            System.out.println("⚠️  未找到 weather_mcp_server.py，跳过 MCP 连接");
            return false;
        }

        // 检测 Python 是否可用
        String python = findPython();
        if (python == null) {
            System.out.println("⚠️  Python 不可用，跳过 MCP 连接");
            return false;
        }

        try {
            System.out.println("🔗 连接到 MCP 服务器 (" + python + " " + scriptPath + ")...");
            MCPTool weatherTool = new MCPTool("weather",
                    List.of(python, scriptPath),
                    List.of(), java.util.Map.of(), true);

            if (!weatherTool.isInitialized()) {
                System.out.println("⚠️  MCP 服务器初始化失败: " + weatherTool.getInitError());
                return false;
            }

            registry.registerTool(weatherTool);
            System.out.println("✅ 连接成功！MCP 服务器提供 " +
                    weatherTool.getAvailableTools().size() + " 个工具");

            // 展开工具
            for (MCPWrappedTool t : weatherTool.getExpandedTools()) {
                registry.registerTool(t);
            }
            return true;

        } catch (Exception e) {
            System.out.println("⚠️  MCP 连接失败: " + e.getMessage());
            return false;
        }
    }

    /** 在项目中查找 weather_mcp_server.py。 */
    private static String findWeatherServerScript() {
        String[] candidates = {
                "weather_mcp_server.py",
                "./weather_mcp_server.py",
                "../weather_mcp_server.py",
        };
        for (String path : candidates) {
            if (Files.exists(Path.of(path))) {
                return Path.of(path).toAbsolutePath().toString();
            }
        }
        // 在当前工作目录递归查找
        try {
            var found = Files.walk(Path.of("."), 2)
                    .filter(p -> p.getFileName().toString().equals("weather_mcp_server.py"))
                    .findFirst();
            if (found.isPresent()) {
                return found.get().toAbsolutePath().toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 检测系统中的 Python 解释器。 */
    private static String findPython() {
        for (String cmd : new String[]{"python3", "python", "py"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true)
                        .start();
                if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return cmd;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ==================== 运行模式 ====================

    private static void runDemo(FunctionCallAgent assistant) {
        System.out.println("\n查询北京天气：");
        String response = assistant.run("北京今天天气怎么样？");
        System.out.println("回答: " + response);
        System.out.println();
    }

    private static void runInteractive(FunctionCallAgent assistant) {
        System.out.println("\n交互模式（输入 quit 退出）\n");

        // 先跑一次 demo 展示效果
        runDemo(assistant);

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\n你: ");
                String input = scanner.nextLine().strip();
                if (input.isEmpty()) continue;
                if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) break;

                String response = assistant.run(input);
                System.out.println("助手: " + response);
            }
        }
    }
}
