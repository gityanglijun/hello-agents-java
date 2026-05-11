package com.example.agent.app;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.WinRateEvaluator;
import com.example.agent.tool.WinRateEvaluator.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Win Rate 评估 — 通过 LLM 对比生成题目与真题，计算胜率。
 *
 * 对应 Python 第12章示例9 (12.4.4):
 *   1. 准备生成题目 + 参考题目
 *   2. LLM 成对对比
 *   3. 计算 Win Rate
 *   4. 查看对比详情
 *
 * 理想胜率 ~50%，表示生成质量接近参考水平。
 *
 * 运行: mvn exec:java -Dexec.mainClass=com.example.agent.app.WinRateDemo
 */
public class WinRateDemo {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        // 1. 准备生成题目
        List<Map<String, String>> generatedProblems = buildGeneratedProblems();

        // 2. 准备参考题目 (模拟 AIME 真题)
        List<Map<String, String>> referenceProblems = buildReferenceProblems();

        // 3. 创建 LLM
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }
        System.out.println("✅ LLM: " + llm.model);

        // 4. Win Rate 评估
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Win Rate 评估");
        System.out.println("=".repeat(60));
        System.out.println("  生成题目数: " + generatedProblems.size());
        System.out.println("  参考题目数: " + referenceProblems.size());

        WinRateEvaluator evaluator = new WinRateEvaluator(llm);
        int numComparisons = Math.min(6,
                generatedProblems.size() * referenceProblems.size());

        WinRateResult results = evaluator.evaluate(
                generatedProblems, referenceProblems, numComparisons);

        // 5. 显示结果
        System.out.println("\n" + "=".repeat(60));
        System.out.println("评估结果");
        System.out.println("=".repeat(60));
        System.out.println("\nWin Rate: " + String.format("%.2f%%", results.winRate() * 100));
        System.out.println("Tie Rate: " + String.format("%.2f%%", results.tieRate() * 100));
        System.out.println("Loss Rate: " + String.format("%.2f%%", results.lossRate() * 100));
        System.out.println("\n详细统计:");
        System.out.println("  总对比数: " + results.totalComparisons());
        System.out.println("  生成题目胜: " + results.wins());
        System.out.println("  平局: " + results.ties());
        System.out.println("  真题胜: " + results.losses());

        // 6. 质量评估
        System.out.println("\n质量评估:");
        System.out.println("  " + WinRateEvaluator.qualityLevel(results.winRate()));

        // 7. 对比详情
        System.out.println("\n" + "=".repeat(60));
        System.out.println("对比详情 (前 3 个)");
        System.out.println("=".repeat(60));

        int display = Math.min(3, results.comparisons().size());
        for (int i = 0; i < display; i++) {
            Comparison c = results.comparisons().get(i);
            System.out.println("\n对比 " + (i + 1) + ":");
            System.out.println("  生成题目: " + truncate(c.generatedProblem(), 60));
            System.out.println("  真题: " + truncate(c.referenceProblem(), 60));
            System.out.println("  结果: " + c.result());
            if (c.reason() != null && !c.reason().isEmpty()) {
                System.out.println("  理由: " + truncate(c.reason(), 100));
            }
        }

        // 8. 保存结果
        try {
            Map<String, Object> saveData = new LinkedHashMap<>();
            saveData.put("win_rate", results.winRate());
            saveData.put("tie_rate", results.tieRate());
            saveData.put("loss_rate", results.lossRate());
            saveData.put("total_comparisons", results.totalComparisons());
            saveData.put("wins", results.wins());
            saveData.put("ties", results.ties());
            saveData.put("losses", results.losses());
            saveData.put("comparisons", results.comparisons());

            Path dir = Path.of("evaluation_results");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("win_rate_results.json"),
                    PRETTY.toJson(saveData));
            System.out.println("\n✅ 结果已保存: evaluation_results/win_rate_results.json");
        } catch (IOException e) {
            System.out.println("⚠️ 保存失败: " + e.getMessage());
        }

        System.out.println("\n✅ Win Rate 评估完成！");
    }

    private static List<Map<String, String>> buildGeneratedProblems() {
        List<Map<String, String>> list = new ArrayList<>();

        Map<String, String> p1 = new LinkedHashMap<>();
        p1.put("problem_id", "gen_001");
        p1.put("problem", "Find the number of positive integers n such that "
                + "n^2 + 19n + 92 is a perfect square.");
        p1.put("answer", "4");
        list.add(p1);

        Map<String, String> p2 = new LinkedHashMap<>();
        p2.put("problem_id", "gen_002");
        p2.put("problem", "In triangle ABC, AB = 13, BC = 14, and CA = 15. "
                + "Find the area of the triangle.");
        p2.put("answer", "84");
        list.add(p2);

        Map<String, String> p3 = new LinkedHashMap<>();
        p3.put("problem_id", "gen_003");
        p3.put("problem", "How many positive integers less than 1000 are "
                + "divisible by 7 but not by 11?");
        p3.put("answer", "129");
        list.add(p3);

        return list;
    }

    private static List<Map<String, String>> buildReferenceProblems() {
        List<Map<String, String>> list = new ArrayList<>();

        Map<String, String> r1 = new LinkedHashMap<>();
        r1.put("problem_id", "aime_ref_001");
        r1.put("problem", "Let N be the number of consecutive 0's at the "
                + "right end of the decimal representation of the product "
                + "1!2!3!4!...99!100!. Find N.");
        list.add(r1);

        Map<String, String> r2 = new LinkedHashMap<>();
        r2.put("problem_id", "aime_ref_002");
        r2.put("problem", "Find the number of ordered pairs (m,n) of positive "
                + "integers such that mn = 1000000.");
        list.add(r2);

        Map<String, String> r3 = new LinkedHashMap<>();
        r3.put("problem_id", "aime_ref_003");
        r3.put("problem", "The polynomial P(x) = x^3 + ax^2 + bx + c has the "
                + "property that the roots of P(x) are the side lengths of "
                + "a right triangle. Find a + b + c.");
        list.add(r3);

        Map<String, String> r4 = new LinkedHashMap<>();
        r4.put("problem_id", "aime_ref_004");
        r4.put("problem", "For how many positive integers n <= 1000 is "
                + "n^2 - 3n + 2 a prime number?");
        list.add(r4);

        return list;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
