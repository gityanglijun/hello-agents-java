package com.example.agent.app;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.LLMJudge;
import com.example.agent.tool.LLMJudge.*;
import com.example.agent.tool.WinRateEvaluator;
import com.example.agent.tool.WinRateEvaluator.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 数据生成完整评估流程 — AIME 题目生成 + LLM Judge + Win Rate。
 *
 * 对应 Python 第12章示例7 (12.4.6):
 *   1. 生成 AIME 题目 (模拟生成流程)
 *   2. LLM Judge 评估 (4 维度评分)
 *   3. Win Rate 评估 (与真题对比)
 *   4. 生成综合报告
 *
 * 运行:
 *   mvn exec:java -Dexec.mainClass=com.example.agent.app.DataGenCompleteFlow
 *   mvn exec:java -Dexec.mainClass=com.example.agent.app.DataGenCompleteFlow \
 *     -Dexec.args="30 3.0"
 */
public class DataGenCompleteFlow {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        int numProblems = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        double delaySeconds = args.length > 1 ? Double.parseDouble(args[1]) : 1.0;

        System.out.println("=".repeat(80));
        System.out.println("AIME 数据生成与评估完整流程");
        System.out.println("=".repeat(80));
        System.out.println("\n配置:");
        System.out.println("  题目数量: " + numProblems);
        System.out.println("  延迟: " + delaySeconds + "秒/题");

        // 1. 创建 LLM
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }
        System.out.println("  LLM: " + llm.model);

        // ====== 阶段1: 生成题目 ======
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段1: 生成 AIME 题目");
        System.out.println("=".repeat(60));

        List<Map<String, String>> generatedProblems = generateProblems(llm, numProblems, delaySeconds);

        System.out.println("\n✅ 生成完成: " + generatedProblems.size() + "/" + numProblems);

        // 保存生成的题目
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path genFile = Path.of("evaluation_results", "aime_problems_" + ts + ".json");
        try {
            Files.createDirectories(genFile.getParent());
            Files.writeString(genFile, PRETTY.toJson(generatedProblems));
            System.out.println("  保存位置: " + genFile);
        } catch (IOException e) {
            System.out.println("  ⚠️ 保存失败: " + e.getMessage());
        }

        // ====== 阶段2: LLM Judge 评估 ======
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段2: LLM Judge 评估");
        System.out.println("=".repeat(60));
        System.out.println("  评估模型: " + llm.model);
        System.out.println("  样本数: " + generatedProblems.size());

        LLMJudge judge = new LLMJudge(llm);
        List<JudgeResult> judgeResults = judge.evaluateBatch(generatedProblems);
        JudgeStatistics stats = judge.computeStatistics(judgeResults);

        System.out.println("\n✅ LLM Judge 评估完成");
        System.out.println("  平均分: " + String.format("%.2f/5.0", stats.avgOverall()));
        System.out.println("  评估维度:");
        System.out.println("    - 正确性: " + String.format("%.2f/5.0", stats.avgCorrectness()));
        System.out.println("    - 清晰度: " + String.format("%.2f/5.0", stats.avgClarity()));
        System.out.println("    - 难度匹配: " + String.format("%.2f/5.0", stats.avgDifficulty()));
        System.out.println("    - 完整性: " + String.format("%.2f/5.0", stats.avgCompleteness()));

        // ====== 阶段3: Win Rate 评估 ======
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段3: Win Rate 评估");
        System.out.println("=".repeat(60));

        List<Map<String, String>> referenceProblems = getReferenceProblems();
        System.out.println("  对比数量: " + Math.min(10, generatedProblems.size() * 2));
        System.out.println("  参考数据集: AIME 模拟真题 (" + referenceProblems.size() + " 道)");

        WinRateEvaluator winEval = new WinRateEvaluator(llm);
        int comparisons = Math.min(10, generatedProblems.size()
                * Math.min(referenceProblems.size(), 3));
        WinRateResult wrResult = winEval.evaluate(
                generatedProblems, referenceProblems, Math.max(comparisons, 3));

        System.out.println("\n✅ Win Rate 评估完成");
        System.out.println("  Win Rate: " + String.format("%.1f%%", wrResult.winRate() * 100));
        System.out.println("  Tie Rate: " + String.format("%.1f%%", wrResult.tieRate() * 100));
        System.out.println("  Loss Rate: " + String.format("%.1f%%", wrResult.lossRate() * 100));

        // ====== 阶段4: 综合报告 ======
        System.out.println("\n" + "=".repeat(60));
        System.out.println("阶段4: 综合报告");
        System.out.println("=".repeat(60));

        generateSummaryReport(generatedProblems, stats, wrResult, ts);
        System.out.println("\n✅ 完整评估流程完成！");
        System.out.println("\n📊 评估总结:");
        System.out.println("  生成数量: " + generatedProblems.size() + " 道题目");
        System.out.println("  LLM Judge 平均分: " + String.format("%.2f/5.0", stats.avgOverall()));
        System.out.println("  Win Rate: " + String.format("%.1f%%", wrResult.winRate() * 100));
        System.out.println("  建议: " + LLMJudge.qualityLevel(stats.avgOverall()));
    }

    // ==================== 题目生成 (模拟) ====================

    private static final String GEN_PROMPT = """
            Generate a challenging AIME-level mathematics problem. \

            Requirements:
            - Must require multi-step reasoning
            - Answer should be an integer between 0 and 999
            - Include a complete solution

            Output as JSON:
            {
              "problem": "<problem statement>",
              "answer": "<integer>",
              "solution": "<step-by-step solution>"
            }""";

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> generateProblems(
            HelloAgentsLLM llm, int count, double delaySeconds) {

        List<Map<String, String>> problems = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            System.out.println("  生成 " + (i + 1) + "/" + count + " ...");

            try {
                String response = llm.think(List.of(Map.of("role", "user", "content", GEN_PROMPT)));

                String json = LLMJudge.extractJson(response);
                Map<String, Object> map = new Gson().fromJson(json,
                        new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());

                Map<String, String> problem = new LinkedHashMap<>();
                problem.put("problem_id", "gen_" + String.format("%03d", i + 1));
                problem.put("problem", String.valueOf(map.getOrDefault("problem", "")));
                problem.put("answer", String.valueOf(map.getOrDefault("answer", "")));
                problem.put("solution", String.valueOf(map.getOrDefault("solution", "")));
                problems.add(problem);

                System.out.println("    ✅ " + truncate(problem.get("problem"), 60));

            } catch (Exception e) {
                System.out.println("    ❌ 生成失败: " + e.getMessage());
            }

            // 延迟
            if (i < count - 1 && delaySeconds > 0) {
                try { Thread.sleep((long) (delaySeconds * 1000)); }
                catch (InterruptedException ignored) {}
            }
        }

        return problems;
    }

    // ==================== 参考题目 ====================

    private static List<Map<String, String>> getReferenceProblems() {
        List<Map<String, String>> refs = new ArrayList<>();

        String[][] data = {
            {"aime_001", "Find the number of ordered pairs (m,n) of positive integers such that mn = 1000000."},
            {"aime_002", "Let N be the number of consecutive 0's at the right end of the decimal representation of the product 1!2!3!...99!100!. Find N."},
            {"aime_003", "The polynomial P(x) = x^3 + ax^2 + bx + c has the property that the roots of P(x) are the side lengths of a right triangle. Find a + b + c."},
            {"aime_004", "For how many positive integers n ≤ 1000 is n^2 - 3n + 2 a prime number?"},
            {"aime_005", "Find the sum of all positive integers n such that n^2 - n + 11 is a perfect square."},
        };

        for (String[] d : data) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("problem_id", d[0]);
            m.put("problem", d[1]);
            refs.add(m);
        }
        return refs;
    }

    // ==================== 报告 ====================

    private static void generateSummaryReport(
            List<Map<String, String>> problems,
            JudgeStatistics stats,
            WinRateResult wrResult,
            String timestamp) {

        StringBuilder sb = new StringBuilder();
        sb.append("# AIME 数据生成评估报告\n\n");
        sb.append("**生成时间**: ").append(timestamp).append("\n\n");

        sb.append("## 生成概览\n\n");
        sb.append("- 生成数量: ").append(problems.size()).append(" 道\n");
        sb.append("- 成功率: ").append(problems.size()).append("/").append(problems.size()).append("\n\n");

        sb.append("## LLM Judge 评分\n\n");
        sb.append("| 维度 | 得分 |\n");
        sb.append("|------|------|\n");
        sb.append("| 正确性 | ").append(String.format("%.2f/5", stats.avgCorrectness())).append(" |\n");
        sb.append("| 清晰度 | ").append(String.format("%.2f/5", stats.avgClarity())).append(" |\n");
        sb.append("| 难度匹配 | ").append(String.format("%.2f/5", stats.avgDifficulty())).append(" |\n");
        sb.append("| 完整性 | ").append(String.format("%.2f/5", stats.avgCompleteness())).append(" |\n");
        sb.append("| **总体** | **").append(String.format("%.2f/5", stats.avgOverall())).append("** |\n\n");

        sb.append("## Win Rate\n\n");
        sb.append("- Win: ").append(String.format("%.1f%%", wrResult.winRate() * 100)).append("\n");
        sb.append("- Tie: ").append(String.format("%.1f%%", wrResult.tieRate() * 100)).append("\n");
        sb.append("- Loss: ").append(String.format("%.1f%%", wrResult.lossRate() * 100)).append("\n\n");

        sb.append("## 生成题目列表\n\n");
        for (var p : problems) {
            sb.append("- **").append(p.get("problem_id")).append("**: ")
              .append(truncate(p.get("problem"), 100)).append("\n");
        }

        try {
            Path dir = Path.of("evaluation_reports");
            Files.createDirectories(dir);
            Path file = dir.resolve("data_gen_report_" + timestamp + ".md");
            Files.writeString(file, sb.toString());
            System.out.println("📄 报告已保存: " + file);
        } catch (IOException e) {
            System.out.println("⚠️ 报告保存失败: " + e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
