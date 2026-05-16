package com.cybertown.util;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 对话日志系统 — 对应 Python logger.py。
 * 双输出：控制台 + 文件，按日期分文件。
 */
public class Logger {

    private static final Path LOGS_DIR = Paths.get("cyber-town", "logs");
    private static PrintWriter fileWriter;

    static {
        try {
            Files.createDirectories(LOGS_DIR);
            String today = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path logFile = LOGS_DIR.resolve("dialogue_" + today + ".log");
            fileWriter = new PrintWriter(new FileWriter(logFile.toFile(), true), true);
            System.out.println("\n📝 对话日志文件: " + logFile.toAbsolutePath());
            System.out.println("📂 日志目录: " + LOGS_DIR.toAbsolutePath() + "\n");
        } catch (IOException e) {
            System.err.println("❌ 日志文件创建失败: " + e.getMessage());
            fileWriter = null;
        }
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static void log(String msg) {
        String line = now() + " - " + msg;
        System.out.println(msg);
        if (fileWriter != null) {
            fileWriter.println(line);
            fileWriter.flush();
        }
    }

    // ==================== 对话流程日志 ====================

    public static void dialogueStart(String npcName, String playerMessage) {
        log("=".repeat(60));
        log("💬 对话开始: " + npcName + " <-> 玩家");
        log("=".repeat(60));
        log("📝 玩家消息: " + playerMessage);
    }

    public static void affinity(String npcName, double affinity, String level) {
        log("💖 当前好感度: " + String.format("%.1f", affinity) + "/100 (" + level + ")");
    }

    public static void memoryRetrieval(String npcName, int count, List<?> memories) {
        log("🧠 检索到" + count + "条相关记忆");
        if (memories != null && !memories.isEmpty()) {
            log("  📚 相关记忆:");
            int n = Math.min(memories.size(), 3);
            for (int i = 0; i < n; i++) {
                String content = memories.get(i).toString();
                if (content.length() > 50) content = content.substring(0, 50) + "...";
                log("    " + (i + 1) + ". " + content);
            }
        }
    }

    public static void generatingResponse() {
        log("🤖 正在生成回复...");
    }

    public static void npcResponse(String npcName, String response) {
        log("💬 " + npcName + "回复: " + response);
    }

    public static void analyzingAffinity() {
        log("📊 正在分析好感度变化...");
    }

    public static void affinityChange(java.util.Map<String, Object> result) {
        boolean changed = (boolean) result.getOrDefault("changed", false);
        if (changed) {
            double changeAmount = ((Number) result.get("change_amount")).doubleValue();
            String symbol = changeAmount > 0 ? "📈" : "📉";
            log(symbol + " 好感度变化: "
                    + String.format("%.1f", ((Number) result.get("old_affinity")).doubleValue())
                    + " -> " + String.format("%.1f", ((Number) result.get("new_affinity")).doubleValue())
                    + " (" + (changeAmount > 0 ? "+" : "") + String.format("%.1f", changeAmount) + ")");
            log("  原因: " + result.get("reason"));
            log("  情感: " + result.get("sentiment"));

            if (!result.get("old_level").equals(result.get("new_level"))) {
                log("  🎉 关系等级变化: " + result.get("old_level") + " -> " + result.get("new_level"));
            }
        } else {
            log("  ➡️ 好感度未变化 (当前: "
                    + String.format("%.1f", ((Number) result.getOrDefault("affinity", 50.0)).doubleValue()) + ")");
            log("  原因: " + result.getOrDefault("reason", "无"));
        }
    }

    public static void memorySaved(String npcName) {
        log("  💾 对话已保存到" + npcName + "的记忆中");
    }

    public static void dialogueEnd() {
        log("=".repeat(60));
        log("✅ 对话完成\n");
    }

    public static void info(String message) {
        log(message);
    }

    public static void error(String message) {
        log("❌ " + message);
    }
}
