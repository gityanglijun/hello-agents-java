package com.example.agent.app;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.LLMJudge;
import com.example.agent.tool.LLMJudge.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Judge 评估 — 使用 LLM 对生成题目进行 4 维度质量评分。
 *
 * 对应 Python 第12章示例8 (12.4.3):
 *   1. 准备题目数据
 *   2. LLM Judge 评分 (正确性/清晰度/难度匹配/完整性)
 *   3. 计算聚合统计
 *   4. 质量评估
 *
 * 运行: mvn exec:java -Dexec.mainClass=com.example.agent.app.LLMJudgeDemo
 */
public class LLMJudgeDemo {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) {
        // 1. 准备生成的题目数据
        List<Map<String, String>> generatedProblems = buildSampleProblems();

        // 2. 创建 LLM
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }
        System.out.println("✅ LLM: " + llm.model);

        // 3. 创建 LLM Judge
        LLMJudge judge = new LLMJudge(llm);

        // 4. 评估每道题目
        System.out.println("\n" + "=".repeat(60));
        System.out.println("LLM Judge 评估");
        System.out.println("=".repeat(60));

        List<JudgeResult> allScores = new ArrayList<>();
        for (int i = 0; i < generatedProblems.size(); i++) {
            var problem = generatedProblems.get(i);
            System.out.println("\n评估题目 " + (i + 1) + "/" + generatedProblems.size());
            System.out.println("题目ID: " + problem.get("problem_id"));

            JudgeResult result = judge.evaluateSingle(problem);

            System.out.println("评估结果:");
            System.out.println("  正确性: " + result.correctness() + "/5");
            System.out.println("  清晰度: " + result.clarity() + "/5");
            System.out.println("  难度匹配: " + result.difficultyMatch() + "/5");
            System.out.println("  完整性: " + result.completeness() + "/5");
            System.out.println("  平均分: " + String.format("%.2f/5", result.averageScore()));
            System.out.println("评语: " + result.feedback().substring(0,
                    Math.min(120, result.feedback().length())) + "...");

            allScores.add(result);
        }

        // 5. 计算总体统计
        JudgeStatistics stats = judge.computeStatistics(allScores);
        System.out.println("\n" + "=".repeat(60));
        System.out.println("总体统计");
        System.out.println("=".repeat(60));
        System.out.println("\n平均分:");
        System.out.println("  正确性: " + String.format("%.2f/5", stats.avgCorrectness()));
        System.out.println("  清晰度: " + String.format("%.2f/5", stats.avgClarity()));
        System.out.println("  难度匹配: " + String.format("%.2f/5", stats.avgDifficulty()));
        System.out.println("  完整性: " + String.format("%.2f/5", stats.avgCompleteness()));
        System.out.println("  总体平均: " + String.format("%.2f/5", stats.avgOverall()));

        // 6. 质量评估
        System.out.println("\n质量评估:");
        System.out.println("  " + LLMJudge.qualityLevel(stats.avgOverall()));

        // 7. 保存结果
        try {
            Map<String, Object> saveData = new LinkedHashMap<>();
            saveData.put("problems", generatedProblems);
            saveData.put("scores", allScores);
            saveData.put("statistics", stats);

            Path dir = Path.of("evaluation_results");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("llm_judge_results.json"),
                    PRETTY.toJson(saveData));
            System.out.println("\n✅ 结果已保存: evaluation_results/llm_judge_results.json");
        } catch (IOException e) {
            System.out.println("⚠️ 保存失败: " + e.getMessage());
        }

        System.out.println("\n✅ LLM Judge 评估完成！");
    }

    /** 构建示例题目。 */
    private static List<Map<String, String>> buildSampleProblems() {
        List<Map<String, String>> problems = new ArrayList<>();

        Map<String, String> p1 = new LinkedHashMap<>();
        p1.put("problem_id", "generated_001");
        p1.put("problem", "Find the number of positive integers n such that "
                + "n^2 + 19n + 92 is a perfect square.");
        p1.put("answer", "4");
        p1.put("solution", "Let n^2 + 19n + 92 = m^2 for some positive integer m...");
        problems.add(p1);

        Map<String, String> p2 = new LinkedHashMap<>();
        p2.put("problem_id", "generated_002");
        p2.put("problem", "In triangle ABC, AB = 13, BC = 14, and CA = 15. "
                + "Find the area of the triangle.");
        p2.put("answer", "84");
        p2.put("solution", "Using Heron's formula, s = (13+14+15)/2 = 21...");
        problems.add(p2);

        return problems;
    }
}
