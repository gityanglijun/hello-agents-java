package com.example.agent.app;

import java.util.Map;

/**
 * CodebaseMaintainer 演示入口 — 对应 Python main() 的 4 步结构。
 *
 * <pre>
 * 工作流:
 *   1. maintainer.explore()       → 探索代码库
 *   2. maintainer.analyze()       → 分析代码质量
 *   3. maintainer.planNextSteps() → 规划下一步
 *   4. maintainer.generateReport() → 生成会话报告
 * </pre>
 *
 * 运行: mvn exec:java -Dexec.mainClass=com.example.agent.app.CodebaseMaintainerDemo
 */
public class CodebaseMaintainerDemo {

    public static void main(String[] args) {
        String codebasePath = args.length > 0 ? args[0] : "./Miku_Miku_Rig-main/Miku_Miku_Rig-main";

        System.out.println("=".repeat(60));
        System.out.println("CodebaseMaintainer 演示");
        System.out.println("=".repeat(60) + "\n");

        // 初始化助手
        CodebaseMaintainer maintainer = new CodebaseMaintainer("Miku_Miku_Rig", codebasePath);

        // -------------------------------------------------------
        // Step 1: 探索代码库
        // -------------------------------------------------------
        System.out.println("\n### Step 1: 探索代码库 ###");
        String response = maintainer.explore(".");
        System.out.println(response);

        // -------------------------------------------------------
        // Step 2: 分析代码质量
        // -------------------------------------------------------
        System.out.println("\n### Step 2: 分析代码质量 ###");
        response = maintainer.analyze("");
        System.out.println(response);

        // -------------------------------------------------------
        // Step 3: 规划下一步
        // -------------------------------------------------------
        System.out.println("\n### Step 3: 规划下一步任务 ###");
        response = maintainer.planNextSteps();
        System.out.println(response);

        // -------------------------------------------------------
        // Step 4: 生成报告
        // -------------------------------------------------------
        System.out.println("\n### Step 4: 生成会话报告 ###");
        Map<String, Object> report = maintainer.generateReport(true);
        System.out.println("  会话ID:   " + ((Map<?, ?>) report.get("session_info")).get("session_id"));
        System.out.println("  命令执行: " + ((Map<?, ?>) report.get("activity")).get("commands_executed"));
        System.out.println("  笔记创建: " + ((Map<?, ?>) report.get("activity")).get("notes_created"));
        System.out.println("  问题发现: " + ((Map<?, ?>) report.get("activity")).get("issues_found"));

        System.out.println("\n" + "=".repeat(60));
        System.out.println("演示完成");
        System.out.println("=".repeat(60));
    }
}
