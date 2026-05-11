package com.example.agent.app;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.SimpleAgent;
import com.example.agent.tool.GAIAEvaluationTool;

import java.util.Map;

/**
 * GAIA 评估最佳实践 — 展示分级评估、小样本快速测试和结果解读。
 *
 * 对应 Python 第12章示例6 (12.3.9):
 *   实践1: 分级评估 — 从 Level 1 到 Level 3 逐步推进
 *   实践2: 小样本快速测试 — 每级 2-3 样本快速摸底
 *   实践3: 结果解读 — 按级别分析表现并给出建议
 *
 * 运行: mvn exec:java -Dexec.mainClass=com.example.agent.app.GaiaBestPractices
 */
public class GaiaBestPractices {

    public static void main(String[] args) {
        boolean enableTools = args.length > 0 && "--tools".equals(args[0]);

        // 创建 LLM + Agent
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }

        SimpleAgent agent = new SimpleAgent(
                "GAIA_Agent",
                llm,
                GAIAEvaluationTool.GAIA_SYSTEM_PROMPT);

        if (enableTools) {
            agent.addTool(GaiaQuickStart.createCalculatorTool());
            System.out.println("🔧 工具链已启用: calculator");
        } else {
            System.out.println("⚠️ 工具链未启用（纯 LLM 模式）。用 --tools 开启。");
        }

        GAIAEvaluationTool gaiaTool = new GAIAEvaluationTool(agent);
        String dataSource = "evaluation_data/GAIA_test.json";

        // ============================================================
        // 最佳实践1：分级评估
        // ============================================================
        System.out.println("=".repeat(60));
        System.out.println("最佳实践1：分级评估");
        System.out.println("=".repeat(60));

        double rateL1 = 0, rateL2 = 0, rateL3 = 0;

        // 第一步：评估 Level 1
        System.out.println("\n第一步：评估 Level 1（简单任务）");
        String r1 = gaiaTool.run(Map.of(
                "action", "run_eval", "level", 1,
                "max_samples", 4, "data_source", dataSource,
                "export_results", false, "generate_report", false));
        rateL1 = extractRate(r1);

        // 第二步：如果 Level 1 良好，评估 Level 2
        if (rateL1 > 0.6) {
            System.out.println("\n第二步：评估 Level 2（中等任务）");
            String r2 = gaiaTool.run(Map.of(
                    "action", "run_eval", "level", 2,
                    "max_samples", 3, "data_source", dataSource,
                    "export_results", false, "generate_report", false));
            rateL2 = extractRate(r2);

            // 第三步：如果 Level 2 良好，评估 Level 3
            if (rateL2 > 0.4) {
                System.out.println("\n第三步：评估 Level 3（困难任务）");
                String r3 = gaiaTool.run(Map.of(
                        "action", "run_eval", "level", 3,
                        "max_samples", 3, "data_source", dataSource,
                        "export_results", false, "generate_report", false));
                rateL3 = extractRate(r3);
            } else {
                System.out.println("\n⚠️ Level 2 表现不佳，建议先优化后再评估 Level 3");
            }
        } else {
            System.out.println("\n⚠️ Level 1 表现不佳，建议先优化后再评估更高级别");
        }

        // ============================================================
        // 最佳实践2：小样本快速测试
        // ============================================================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("最佳实践2：小样本快速测试");
        System.out.println("=".repeat(60));

        for (int level = 1; level <= 3; level++) {
            System.out.println("\n快速测试 Level " + level + ":");
            String r = gaiaTool.run(Map.of(
                    "action", "run_eval", "level", level,
                    "max_samples", 1, "data_source", dataSource,
                    "export_results", false, "generate_report", false));
            double rate = extractRate(r);
            System.out.println("  精确匹配率: " + String.format("%.2f%%", rate * 100));
        }

        // ============================================================
        // 最佳实践3：结果解读
        // ============================================================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("最佳实践3：结果解读");
        System.out.println("=".repeat(60));

        interpretResults(1, rateL1);
        if (rateL2 > 0) interpretResults(2, rateL2);
        if (rateL3 > 0) interpretResults(3, rateL3);

        // ============================================================
        // 难度递进分析
        // ============================================================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("难度递进分析");
        System.out.println("=".repeat(60));

        if (rateL1 > 0 && rateL2 > 0) {
            if (rateL1 > rateL2) {
                System.out.println("✅ 正常递进：Level 1 > Level 2");
            } else {
                System.out.println("⚠️ 异常情况：Level 2 >= Level 1（可能是数据集偏差）");
            }
        }
        if (rateL2 > 0 && rateL3 > 0) {
            if (rateL2 > rateL3) {
                System.out.println("✅ 正常递进：Level 2 > Level 3");
            } else {
                System.out.println("⚠️ 异常情况：Level 3 >= Level 2（可能是数据集偏差）");
            }
        }

        System.out.println("\n✅ 最佳实践演示完成！");
    }

    /** 从 JSON 结果中提取精确匹配率。 */
    private static double extractRate(String json) {
        try {
            var gson = new com.google.gson.Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = gson.fromJson(json, Map.class);
            String rateStr = (String) map.get("exact_match_rate");
            if (rateStr != null) {
                return Double.parseDouble(rateStr.replace("%", "")) / 100.0;
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    /** 解读评估结果。 */
    private static void interpretResults(int level, double exactMatchRate) {
        System.out.println("\nLevel " + level + " 结果解读:");
        System.out.println("精确匹配率: " + String.format("%.2f%%", exactMatchRate * 100));

        switch (level) {
            case 1 -> {
                if (exactMatchRate >= 0.6) {
                    System.out.println("✅ 优秀 - 基础能力扎实");
                } else if (exactMatchRate >= 0.4) {
                    System.out.println("⚠️ 良好 - 基础能力可用");
                } else {
                    System.out.println("❌ 较差 - 需要改进");
                    System.out.println("建议:");
                    System.out.println("  - 检查系统提示词是否包含 GAIA 官方格式要求");
                    System.out.println("  - 检查答案提取逻辑是否正确");
                    System.out.println("  - 检查 LLM 模型是否足够强大");
                }
            }
            case 2 -> {
                if (exactMatchRate >= 0.4) {
                    System.out.println("✅ 优秀 - 中等任务能力强");
                } else if (exactMatchRate >= 0.2) {
                    System.out.println("⚠️ 良好 - 中等任务能力可用");
                } else {
                    System.out.println("❌ 较差 - 需要改进");
                    System.out.println("建议:");
                    System.out.println("  - 增强多步推理能力");
                    System.out.println("  - 增加工具使用能力");
                    System.out.println("  - 优化推理链的构建");
                }
            }
            case 3 -> {
                if (exactMatchRate >= 0.2) {
                    System.out.println("✅ 优秀 - 复杂任务能力强");
                } else if (exactMatchRate >= 0.1) {
                    System.out.println("⚠️ 良好 - 复杂任务能力可用");
                } else {
                    System.out.println("❌ 较差 - 需要改进");
                    System.out.println("建议:");
                    System.out.println("  - 增强复杂推理能力");
                    System.out.println("  - 增加长上下文处理能力");
                    System.out.println("  - 优化工具链的组合使用");
                }
            }
        }
    }
}
