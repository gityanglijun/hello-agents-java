package com.example.agent.app;

import com.example.agent.LoadDotenvUtil;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.FunctionCallAgent;
import com.example.agent.tool.MCPTool;
import com.example.agent.tool.MCPWrappedTool;
import com.example.agent.tool.ToolRegistry;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 协作的智能文档助手 — Java 版。
 *
 * 对应 Python 版 hello_agents 多 Agent 协作示例：
 *   Agent 1: GitHub 搜索专家（MCP GitHub 服务器）
 *   Agent 2: 文档生成专家（MCP 文件系统服务器）
 *
 * 工作流:
 *   1. Agent 1 搜索 GitHub 仓库
 *   2. Agent 2 基于搜索结果生成 Markdown 报告
 *   3. Agent 2 将报告保存到文件
 *
 * 运行: mvn exec:java -Dexec.mainClass=com.example.agent.app.MultiAgentDocAssistant
 */
public class MultiAgentDocAssistant {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("多 Agent 协作的智能文档助手 (Java 版)");
        System.out.println("=".repeat(70));

        // --- 加载环境变量 ---
        Map<String, String> dotEnv = LoadDotenvUtil.loadEnvFile();
        String githubToken = dotEnv.getOrDefault("GITHUB_PERSONAL_ACCESS_TOKEN",
                System.getenv().getOrDefault("GITHUB_PERSONAL_ACCESS_TOKEN", "your-github-pat"));

        // Clash 代理配置（MCP 子进程需要显式传入，不会自动走系统代理）
        Map<String, String> proxyEnv = new java.util.LinkedHashMap<>();
        proxyEnv.put("HTTP_PROXY", "http://127.0.0.1:7890");
        proxyEnv.put("HTTPS_PROXY", "http://127.0.0.1:7890");
        proxyEnv.put("http_proxy", "http://127.0.0.1:7890");
        proxyEnv.put("https_proxy", "http://127.0.0.1:7890");

        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }

        try {
            // ============================================================
            // Agent 1: GitHub 搜索专家
            // ============================================================
            System.out.println("\n【步骤1】创建 GitHub 搜索专家...");

            ToolRegistry githubRegistry = new ToolRegistry();

            MCPTool githubTool;
            if (!githubToken.isEmpty()) {
                Map<String, String> ghEnv = new java.util.LinkedHashMap<>(proxyEnv);
                ghEnv.put("GITHUB_PERSONAL_ACCESS_TOKEN", githubToken);
                githubTool = new MCPTool("gh",
                        List.of("npx", "-y", "@modelcontextprotocol/server-github"),
                        ghEnv);
            } else {
                System.out.println("⚠️  未设置 GITHUB_PERSONAL_ACCESS_TOKEN，将使用内置演示工具");
                githubTool = new MCPTool("gh");
            }
            githubRegistry.registerTool(githubTool);

            // 自动展开 MCP 工具为独立工具
            for (MCPWrappedTool t : githubTool.getExpandedTools()) {
                githubRegistry.registerTool(t);
            }
            System.out.println("🔧 GitHub Agent 工具: " + githubRegistry.listTools());

            FunctionCallAgent githubSearcher = new FunctionCallAgent(
                    "GitHub搜索专家",
                    llm,
                    "你是一个GitHub搜索专家。\n" +
                    "你的任务是搜索GitHub仓库并返回结果。\n" +
                    "请返回清晰、结构化的搜索结果，包括：\n" +
                    "- 仓库名称\n" +
                    "- 简短描述\n\n" +
                    "保持简洁，不要添加额外的解释。",
                    githubRegistry);

            // ============================================================
            // Agent 2: 文档生成专家
            // ============================================================
            System.out.println("\n【步骤2】创建文档生成专家...");

            ToolRegistry fsRegistry = new ToolRegistry();
            MCPTool fsTool = new MCPTool("fs",
                    List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "."),
                    proxyEnv);
            fsRegistry.registerTool(fsTool);

            for (MCPWrappedTool t : fsTool.getExpandedTools()) {
                fsRegistry.registerTool(t);
            }
            System.out.println("🔧 文档生成 Agent 工具: " + fsRegistry.listTools());

            FunctionCallAgent documentWriter = new FunctionCallAgent(
                    "文档生成专家",
                    llm,
                    "你是一个文档生成专家。\n" +
                    "你的任务是根据提供的信息生成结构化的Markdown报告。\n\n" +
                    "报告应该包括：\n" +
                    "- 标题\n" +
                    "- 简介\n" +
                    "- 主要内容（分点列出，包括项目名称、描述等）\n" +
                    "- 总结\n\n" +
                    "你可以使用文件系统工具将报告保存到 report.md 文件。\n" +
                    "请直接输出完整的Markdown格式报告内容。",
                    fsRegistry);

            // ============================================================
            // 执行任务
            // ============================================================
            System.out.println("\n" + "=".repeat(70));
            System.out.println("开始执行任务...");
            System.out.println("=".repeat(70));

            // 步骤1：GitHub 搜索
            System.out.println("\n【步骤3】Agent1 搜索 GitHub...");
            String searchTask = "搜索关于'AI agent'的GitHub仓库，返回前5个最相关的结果";

            String searchResults = githubSearcher.run(searchTask);

            System.out.println("\n搜索结果:");
            System.out.println("-".repeat(70));
            System.out.println(searchResults);
            System.out.println("-".repeat(70));

            // 步骤2：生成报告
            System.out.println("\n【步骤4】Agent2 生成报告...");
            String reportTask = String.format("""
                    根据以下GitHub搜索结果，生成一份Markdown格式的研究报告：

                    %s

                    报告要求：
                    1. 标题：# AI Agent框架研究报告
                    2. 简介：说明这是关于AI Agent的GitHub项目调研
                    3. 主要发现：列出找到的项目及其特点（包括名称、描述等）
                    4. 总结：总结这些项目的共同特点

                    生成报告后，请使用文件系统工具将完整内容保存到 report.md 文件。
                    """, searchResults);

            String reportContent = documentWriter.run(reportTask);

            System.out.println("\n报告内容:");
            System.out.println("=".repeat(70));
            System.out.println(reportContent);
            System.out.println("=".repeat(70));

            // 步骤3：验证报告文件
            System.out.println("\n【步骤5】验证报告文件...");
            Path reportFile = Path.of("report.md");
            if (Files.exists(reportFile)) {
                long fileSize = Files.size(reportFile);
                System.out.println("✅ 报告已保存到 report.md");
                System.out.println("✅ 文件大小: " + fileSize + " 字节");
            } else {
                // 如果 Agent 没保存文件，我们手动保存
                System.out.println("⚠️  Agent 未自动保存文件，手动保存...");
                try (FileWriter fw = new FileWriter("report.md",
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    fw.write(reportContent);
                }
                System.out.println("✅ 报告已保存到 report.md");
            }

            System.out.println("\n" + "=".repeat(70));
            System.out.println("任务完成！");
            System.out.println("=".repeat(70));

            // --- 清理 ---
            githubTool.close();
            fsTool.close();

        } catch (Exception e) {
            System.out.println("\n❌ 错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
