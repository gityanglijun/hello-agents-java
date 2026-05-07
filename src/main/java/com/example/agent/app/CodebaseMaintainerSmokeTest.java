package com.example.agent.app;

import com.example.agent.tool.NoteTool;
import java.nio.file.*;
import java.util.*;

/**
 * CodebaseMaintainer 冒烟测试 — 以 Miku_Miku_Rig-main 代码库为目标。
 */
public class CodebaseMaintainerSmokeTest {

    private int passed = 0, failed = 0;
    private final String codebaseDir;

    public CodebaseMaintainerSmokeTest(String codebaseDir) {
        this.codebaseDir = codebaseDir;
    }

    public static void main(String[] args) {
        // 目标代码库路径
        String dir = args.length > 0 ? args[0] : "./Miku_Miku_Rig-main/Miku_Miku_Rig-main";
        new CodebaseMaintainerSmokeTest(dir).runAll();
    }

    void runAll() {
        Path root = Path.of(codebaseDir).toAbsolutePath().normalize();
        System.out.println("🧪 CodebaseMaintainer 冒烟测试");
        System.out.println("📁 目标代码库: " + root);
        System.out.println("   目录存在: " + Files.exists(root));

        if (!Files.exists(root)) {
            System.out.println("❌ 代码库目录不存在，跳过测试");
            System.exit(1);
        }

        CodebaseMaintainer cm = new CodebaseMaintainer("Miku_Miku_Rig", root.toString());

        // ===== 1. 组件完整性 =====
        section("1. 组件完整性");
        ok(cm.getMemoryTool() != null, "memoryTool");
        ok(cm.getNoteTool() != null, "noteTool");
        ok(cm.getTerminalTool() != null, "terminalTool");
        ok(cm.getContextBuilder() != null, "contextBuilder");

        // ===== 2. TerminalTool 安全机制 =====
        section("2. TerminalTool 安全机制");
        String r = cm.executeCommand("echo Hello MikuMikuRig");
        ok(r.contains("Hello"), "echo 执行: " + r.trim());
        r = cm.executeCommand("rm -rf /");
        ok(r.contains("❌ 不允许的命令"), "危险命令拦截");
        r = cm.executeCommand("cd /etc");
        ok(r.contains("❌"), "绝对路径逃逸拦截");

        // ===== 3. 探索代码库结构 =====
        section("3. 探索代码库结构");
        // 看看根目录下有什么
        r = cm.executeCommand("ls -la");
        ok(r != null && !r.contains("❌"), "ls 根目录: " + (r != null ? r.trim().length() + " 字节输出" : "null"));

        // 找所有 Python 文件
        r = cm.executeCommand("find . -name '*.py' -type f");
        ok(r.contains(".py"), "查找 Python 文件: " + countLines(r) + " 行输出");

        // 找所有配置文件
        r = cm.executeCommand("find . -name '*.json' -o -name '*.yaml' -o -name '*.toml' -o -name '*.cfg' | head -n 10");
        ok(r != null && !r.contains("❌"), "查找配置文件: " + (r != null ? countLines(r) + " 行" : "null"));

        // ===== 4. 内容搜索 =====
        section("4. 内容搜索");
        r = cm.executeCommand("grep -r 'class ' --include='*.py' . | head -n 15");
        ok(r != null && !r.contains("❌"), "搜索 class 定义: " + (r != null ? countLines(r) + " 个类" : "0"));
        System.out.println("   输出预览: " + (r != null ? r.substring(0, Math.min(200, r.length())).replace("\n", " | ") : "null"));

        r = cm.executeCommand("grep -r 'import ' --include='*.py' . | head -n 20");
        ok(r.contains("import"), "搜索 import 语句");

        // ===== 5. cd 导航 =====
        section("5. cd 导航");
        r = cm.executeCommand("pwd");
        System.out.println("   当前目录: " + (r != null ? r.trim() : "null"));
        ok(r != null && !r.contains("❌"), "pwd 正常");

        // 尝试 cd 到第一个子目录
        String firstDir = cm.executeCommand(
            isWindows()
                ? "powershell -Command \"(Get-ChildItem -Directory | Select-Object -First 1).Name\""
                : "ls -d */ 2>/dev/null | head -n 1 | tr -d '/'");
        if (firstDir != null && !firstDir.isBlank() && !firstDir.contains("❌")) {
            firstDir = firstDir.trim();
            r = cm.executeCommand("cd " + firstDir);
            ok(r.contains("✅ 切换到"), "cd 到子目录: " + firstDir);
        } else {
            System.out.println("   ⏭️  无子目录，跳过 cd 测试");
        }
        cm.executeCommand("cd ~");

        // ===== 6. 笔记创建和检索 =====
        section("6. 笔记管理");
        String noteId = cm.createNote(
                "代码库探索: Miku_Miku_Rig 初印象",
                "## 探索发现\n初步浏览了 Miku_Miku_Rig 代码库。\n\n"
              + "## 文件结构\n通过 TerminalTool 列出了根目录和 Python 文件分布。\n\n"
              + "## 后续计划\n需要进一步分析核心模块的依赖关系。",
                "conclusion",
                List.of("Miku_Miku_Rig", "exploration", "initial"));
        ok(noteId != null && !noteId.isBlank(), "创建笔记: " + noteId);

        cm.createNote("发现: 需要检查依赖版本",
                "## 问题\n在探索过程中发现可能存在过时的依赖。\n\n## 建议\n运行 pip list --outdated 检查。",
                "blocker",
                List.of("Miku_Miku_Rig", "dependencies", "auto_detected"));
        ok(cm.getNoteTool().count() >= 2, "blocker + conclusion 共 " + cm.getNoteTool().count() + " 条笔记");

        // searchNotes 搜索标题+正文，不搜索 tags（tags 在 YAML frontmatter 中）
        List<NoteTool.Note> notes = cm.getNoteTool().searchNotes("初步浏览");
        ok(notes.size() >= 1, "关键词搜索找到 " + notes.size() + " 条笔记（搜索标题+正文）");

        Set<String> types = cm.getNoteTool().listTypes();
        ok(types.contains("blocker") && types.contains("conclusion"),
           "笔记类型包含 blocker + conclusion: " + types);

        // ===== 7. 统计信息 =====
        section("7. 统计信息");
        Map<String, Object> stats = cm.getStats();
        ok(((Number) stats.get("commands_executed")).intValue() > 0,
           "commands_executed = " + stats.get("commands_executed"));
        ok(((Number) stats.get("notes_created")).intValue() > 0,
           "notes_created = " + stats.get("notes_created"));
        System.out.println("   完整统计: " + stats);

        // ===== 8. 报告生成 =====
        section("8. 报告生成");
        Map<String, Object> report = cm.generateReport(true);
        ok(report.containsKey("session_info"), "报告含 session_info");
        ok(report.containsKey("activity"), "报告含 activity");
        ok(report.containsKey("report_file"), "JSON 报告已生成: " + report.get("report_file"));

        // ===== 9. 笔记目录检查 =====
        section("9. 笔记文件检查");
        Path notesDir = Path.of("./Miku_Miku_Rig_notes");
        ok(Files.exists(notesDir), "笔记目录存在: " + notesDir.toAbsolutePath());
        try {
            long mdCount = Files.list(notesDir).filter(p -> p.toString().endsWith(".md")).count();
            ok(mdCount >= 2, "生成 " + mdCount + " 个 .md 笔记文件");
        } catch (Exception e) {
            System.out.println("   ⚠️ 笔记目录遍历失败: " + e.getMessage());
        }

        // ===== 清理 =====
        section("清理");
        for (String id : cm.getNoteTool().listNoteIds()) {
            cm.getNoteTool().deleteNote(id);
        }
        System.out.println("   ✅ 测试笔记已清理 (" + cm.getNoteTool().count() + " 条残留)");
        // 清理报告文件
        Path reportFile = Path.of((String) report.getOrDefault("report_file", ""));
        try { Files.deleteIfExists(reportFile); } catch (Exception ignored) {}

        // ===== 结果 =====
        System.out.println("\n" + "=".repeat(50));
        int total = passed + failed;
        System.out.println("通过: " + passed + "/" + total);
        if (failed > 0) {
            System.out.println("失败: " + failed);
            System.exit(1);
        } else {
            System.out.println("全部通过 ✅");
        }
    }

    // ---- 断言 ----

    private void ok(boolean cond, String label) {
        if (cond) { System.out.println("  ✅ " + label); passed++; }
        else      { System.out.println("  ❌ " + label); failed++; }
    }

    private void section(String title) {
        System.out.println("\n=== " + title + " ===");
    }

    private int countLines(String s) {
        return s == null ? 0 : s.split("\n").length;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
