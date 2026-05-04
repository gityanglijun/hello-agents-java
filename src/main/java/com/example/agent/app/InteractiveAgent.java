package com.example.agent.app;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;

/**
 * 交互式 PDF 学习助手（控制台版）。
 *
 * 基于 PDFLearningAssistant（RAG + Memory），提供交互式文档学习体验。
 *
 * 运行：
 *   mvn compile exec:java "-Dexec.mainClass=com.example.agent.app.InteractiveAgent" "-Dexec.classpathScope=compile" -q
 *
 * 命令：
 *   /load <path>    加载 PDF 文档
 *   /note <内容>     添加学习笔记
 *   /recall <关键词>  检索记忆
 *   /stats           学习统计
 *   /report          生成学习报告
 *   /consolidate     记忆整合
 *   /doc             查看当前文档
 *   /help            显示帮助
 *   /exit            退出
 *
 * 任何不以 / 开头的输入均视为对已加载文档的提问。
 */
public class InteractiveAgent {

    private final PDFLearningAssistant assistant;
    private final Scanner scanner;

    public InteractiveAgent() {
        System.out.print("正在初始化学习助手...");
        this.assistant = new PDFLearningAssistant();
        this.scanner = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println(" 完成");
    }

    public void start() {
        printWelcome();

        while (true) {
            System.out.print("\n📖 提问: ");
            System.out.flush();
            String input = scanner.nextLine();

            if (input == null || input.isBlank()) continue;

            // 命令处理
            if (input.startsWith("/")) {
                if (handleCommand(input.trim())) break;
                continue;
            }

            // 默认：向文档提问
            if (assistant.getCurrentDocument() == null) {
                System.out.println("⚠️ 请先加载文档: /load <文件路径>");
                continue;
            }

            System.out.println();
            String answer = assistant.ask(input, true);
            System.out.println("\n📝 " + answer);
        }

        System.out.println("👋 学习会话结束，再见！");
        scanner.close();
    }

    // ==================== 命令分发 ====================

    private boolean handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/exit":
            case "/quit":
            case "/q":
                return true;

            case "/load":
                if (arg.isEmpty()) {
                    System.out.println("用法: /load <PDF文件路径>");
                    break;
                }
                handleLoad(arg);
                break;
            case "/autoload":
                handleLoad("E:\\AIAgent\\hello-agents-java\\models\\Happy-LLM-0727.pdf");
                break;

            case "/note":
                if (arg.isEmpty()) {
                    System.out.println("用法: /note <笔记内容>");
                    break;
                }
                assistant.addNote(arg);
                System.out.println("✅ 笔记已保存");
                break;

            case "/recall":
                if (arg.isEmpty()) {
                    System.out.println("用法: /recall <检索关键词>");
                    break;
                }
                System.out.println(assistant.recall(arg, 5));
                break;

            case "/stats":
                printStats();
                break;

            case "/report":
                handleReport();
                break;

            case "/consolidate":
                Map<String, Object> result = assistant.consolidateMemories();
                System.out.println(result.getOrDefault("result", "完成"));
                break;

            case "/search":
                if (arg.isEmpty()) {
                    System.out.println("用法: /search <关键词> — 查看原始检索结果（含相关度和位置）");
                    break;
                }
                handleSearch(arg);
                break;

            case "/chunks":
                handleChunks();
                break;

            case "/doc":
                printDocInfo();
                break;

            case "/help":
                printHelp();
                break;

            default:
                System.out.println("❓ 未知命令: " + cmd);
                System.out.println("   输入 /help 查看可用命令");
                break;
        }

        return false;
    }

    // ==================== 命令实现 ====================

    private void handleLoad(String path) {
        File file = new File(path);
        if (!file.exists()) {
            System.out.println("❌ 文件不存在: " + path);
            return;
        }

        System.out.println("正在加载文档，请稍候...");
        Map<String, Object> result = assistant.loadDocument(path);

        if (Boolean.TRUE.equals(result.get("success"))) {
            System.out.println("✅ " + result.get("message"));
        } else {
            System.out.println("❌ " + result.get("message"));
        }
    }

    private void handleReport() {
        System.out.println("正在生成学习报告...");
        Map<String, Object> report = assistant.generateReport(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) report.get("session_info");
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) report.get("learning_metrics");

        System.out.println("\n📊 学习报告");
        System.out.println("═══════════════════════════════");
        if (session != null) {
            System.out.println("  会话ID: " + session.get("session_id"));
            System.out.println("  开始时间: " + session.get("start_time"));
            System.out.println("  持续时长: " + session.get("duration_seconds") + " 秒");
        }
        if (metrics != null) {
            System.out.println("  加载文档: " + metrics.get("documents_loaded"));
            System.out.println("  提问次数: " + metrics.get("questions_asked"));
            System.out.println("  学习笔记: " + metrics.get("concepts_learned"));
        }
        System.out.println("  报告文件: " + report.getOrDefault("report_file", "N/A"));
        System.out.println("═══════════════════════════════");
    }

    private void printStats() {
        Map<String, Object> stats = assistant.getStats();
        System.out.println("\n📊 学习统计");
        System.out.println("─────────────");
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
        System.out.println("─────────────");
    }

    /** /search — 原始检索诊断：显示每个命中分块的相关度、章节位置 */
    private void handleSearch(String query) {
        if (assistant.getCurrentDocument() == null) {
            System.out.println("⚠️ 请先加载文档");
            return;
        }
        // 用 RAGTool 的 search action 获取详细命中信息（top_k=15，不用 LLM）
        String result = assistant.getRagTool().run(Map.of(
                "action", "search",
                "query", query,
                "top_k", 15
        ));
        System.out.println(result);
    }

    /** /chunks — 显示分块分布概览 */
    private void handleChunks() {
        if (assistant.getCurrentDocument() == null) {
            System.out.println("⚠️ 请先加载文档");
            return;
        }
        var rag = assistant.getRagTool();
        var doc = rag.getDocument(assistant.getCurrentDocument());
        if (doc == null) {
            System.out.println("❌ 未找到文档");
            return;
        }
        var chunks = rag.getChunks(doc.id);
        System.out.println("\n📊 分块分布 (" + chunks.size() + " 个):");
        System.out.println("──────────────────────────────────────");
        // 按文档位置百分比显示分布
        int docLen = doc.content.length();
        int[] buckets = new int[10]; // 10% 粒度
        for (var c : chunks) {
            int pct = docLen > 0 ? (int) (c.startChar * 100L / docLen) : 0;
            int bucket = Math.min(pct / 10, 9);
            buckets[bucket]++;
        }
        for (int i = 0; i < 10; i++) {
            String bar = "█".repeat(Math.max(1, buckets[i]));
            System.out.printf("  %3d%%~%3d%%: %s (%d)%n",
                    i * 10, (i + 1) * 10, bar, buckets[i]);
        }
        System.out.println("──────────────────────────────────────");
        // 显示首尾各2个分块的内容预览
        System.out.println("\n📄 头部 2 个分块:");
        for (int i = 0; i < Math.min(2, chunks.size()); i++) {
            var c = chunks.get(i);
            String preview = c.content.length() > 100 ? c.content.substring(0, 100) + "..." : c.content;
            System.out.printf("  [%d] (位置 %d/%d) %s: %s%n",
                    i, c.startChar, docLen,
                    c.headingPath != null ? c.headingPath : "(无章节)",
                    preview.replace("\n", " "));
        }
        System.out.println("\n📄 尾部 2 个分块:");
        for (int i = Math.max(0, chunks.size() - 2); i < chunks.size(); i++) {
            var c = chunks.get(i);
            String preview = c.content.length() > 100 ? c.content.substring(0, 100) + "..." : c.content;
            System.out.printf("  [%d] (位置 %d/%d) %s: %s%n",
                    i, c.startChar, docLen,
                    c.headingPath != null ? c.headingPath : "(无章节)",
                    preview.replace("\n", " "));
        }
    }

    private void printDocInfo() {
        String doc = assistant.getCurrentDocument();
        if (doc == null) {
            System.out.println("📄 当前未加载文档");
        } else {
            System.out.println("📄 当前文档: " + doc);
            System.out.println("   RAG 统计: " + assistant.getRagTool().run(
                    Map.of("action", "stats")));
        }
    }

    // ==================== 界面 ====================

    private void printWelcome() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║    HelloAgents PDF 交互式学习助手       ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  基于 RAG + Memory 的智能文档学习       ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  快速开始:                              ║");
        System.out.println("║    /load <PDF路径>  加载文档            ║");
        System.out.println("║    直接输入问题      向文档提问         ║");
        System.out.println("║    /help             查看所有命令       ║");
        System.out.println("╚══════════════════════════════════════════╝");
    }

    private void printHelp() {
        System.out.println();
        System.out.println("┌──────────────────────────────────────┐");
        System.out.println("│  可用命令                             │");
        System.out.println("├──────────────────────────────────────┤");
        System.out.println("│  /load <路径>     加载 PDF 文档       │");
        System.out.println("│  <任何文本>        向文档提问         │");
        System.out.println("│  /search <关键词>  检索诊断(无LLM)     │");
        System.out.println("│  /chunks           分块分布概览       │");
        System.out.println("│  /note <内容>      添加学习笔记       │");
        System.out.println("│  /recall <关键词>   检索记忆          │");
        System.out.println("│  /stats            学习统计           │");
        System.out.println("│  /report           生成学习报告       │");
        System.out.println("│  /consolidate      记忆整合           │");
        System.out.println("│  /doc              当前文档信息       │");
        System.out.println("│  /help             显示此帮助         │");
        System.out.println("│  /exit             退出               │");
        System.out.println("└──────────────────────────────────────┘");
    }

    // ==================== 入口 ====================

    public static void main(String[] args) {
        // Windows 控制台默认 GBK，强制 UTF-8 避免中文乱码
        try {
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {}

        try {
            new InteractiveAgent().start();
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
