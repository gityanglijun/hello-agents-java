package com.example.agent.tool;

import com.example.agent.llm.HelloAgentsLLM;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.*;

/**
 * Win Rate 评估器 — 通过两两对比评估生成内容的相对质量。
 *
 * 对应 Python hello_agents.evaluation.WinRateEvaluator。
 * 将生成题目与参考题目（如 AIME 真题）进行成对比较，
 * 由 LLM 评判谁更好，最终计算胜率。
 *
 * 理想胜率 ~50% 表示生成质量接近参考水平。
 */
public class WinRateEvaluator {

    private static final Gson GSON = new Gson();
    private static final Random RNG = new Random();

    private final HelloAgentsLLM llm;

    /** 单次对比结果。 */
    public record Comparison(
            String generatedId,
            String referenceId,
            String generatedProblem,
            String referenceProblem,
            String result,   // "generated", "reference", or "tie"
            String reason
    ) {}

    /** Win Rate 评估结果。 */
    public record WinRateResult(
            List<Comparison> comparisons,
            int totalComparisons,
            int wins,
            int ties,
            int losses,
            double winRate,
            double tieRate,
            double lossRate
    ) {}

    // 对比提示词
    private static final String COMPARE_PROMPT = """
            You are an expert judge comparing the quality of two mathematical problems \
            designed for the AIME (American Invitational Mathematics Examination).

            Compare the following two problems and determine which is better.

            Criteria:
            - Mathematical depth and elegance
            - Clarity of problem statement
            - Appropriate difficulty for AIME level
            - Originality and creativity

            Problem A:
            %s

            Problem B:
            %s

            Output your comparison as a JSON object:
            {
              "winner": "A" or "B" or "tie",
              "reason": "<brief explanation of your judgment>"
            }""";

    public WinRateEvaluator(HelloAgentsLLM llm) {
        this.llm = llm;
    }

    /**
     * 运行 Win Rate 评估。
     *
     * @param generatedProblems 生成的题目列表，每个含 problem_id/problem 键
     * @param referenceProblems 参考题目列表 (如 AIME 真题)
     * @param numComparisons    对比次数
     */
    public WinRateResult evaluate(
            List<Map<String, String>> generatedProblems,
            List<Map<String, String>> referenceProblems,
            int numComparisons) {

        if (generatedProblems.isEmpty() || referenceProblems.isEmpty()) {
            return new WinRateResult(List.of(), 0, 0, 0, 0, 0, 0, 0);
        }

        List<Comparison> comparisons = new ArrayList<>();
        int wins = 0, ties = 0, losses = 0;

        System.out.println("  开始 Win Rate 评估 (" + numComparisons + " 次对比)...");

        for (int i = 0; i < numComparisons; i++) {
            // 随机选择生成题目和参考题目
            var gen = generatedProblems.get(RNG.nextInt(generatedProblems.size()));
            var ref = referenceProblems.get(RNG.nextInt(referenceProblems.size()));

            // 随机交换 A/B 位置以避免位置偏差
            boolean swap = RNG.nextBoolean();
            String problemA = swap ? ref.get("problem") : gen.get("problem");
            String problemB = swap ? gen.get("problem") : ref.get("problem");

            try {
                String prompt = String.format(COMPARE_PROMPT, problemA, problemB);
                String response = llm.think(List.of(Map.of("role", "user", "content", prompt)));

                Comparison comparison = parseComparison(
                        gen.getOrDefault("problem_id", gen.getOrDefault("id", "gen-" + i)),
                        ref.getOrDefault("problem_id", ref.getOrDefault("id", "ref-" + i)),
                        gen.get("problem"),
                        ref.get("problem"),
                        swap, response);

                comparisons.add(comparison);

                switch (comparison.result()) {
                    case "generated" -> wins++;
                    case "tie" -> ties++;
                    case "reference" -> losses++;
                }

            } catch (Exception e) {
                comparisons.add(new Comparison(
                        gen.getOrDefault("problem_id", "unknown"),
                        ref.getOrDefault("problem_id", "unknown"),
                        gen.get("problem"), ref.get("problem"),
                        "error", e.getMessage()));
            }

            System.out.println("    对比 " + (i + 1) + "/" + numComparisons
                    + " [" + (i > 0 ? comparisons.get(i).result() : comparisons.get(comparisons.size()-1).result()) + "]");

            // 短暂延迟避免速率限制
            if (i < numComparisons - 1) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }

        double winRate = (double) wins / numComparisons;
        double tieRate = (double) ties / numComparisons;
        double lossRate = (double) losses / numComparisons;

        System.out.println("  ✅ 完成: Win=" + String.format("%.1f%%", winRate*100)
                + " Tie=" + String.format("%.1f%%", tieRate*100)
                + " Loss=" + String.format("%.1f%%", lossRate*100));

        return new WinRateResult(comparisons, numComparisons,
                wins, ties, losses, winRate, tieRate, lossRate);
    }

    @SuppressWarnings("unchecked")
    private static Comparison parseComparison(
            String genId, String refId,
            String genProblem, String refProblem,
            boolean swapped, String response) {

        try {
            String json = LLMJudge.extractJson(response);
            Map<String, Object> map = GSON.fromJson(json,
                    new TypeToken<Map<String, Object>>() {}.getType());

            String winner = String.valueOf(map.getOrDefault("winner", "")).trim().toLowerCase();
            String reason = String.valueOf(map.getOrDefault("reason", ""));

            String result;
            if (winner.contains("tie") || winner.contains("draw")) {
                result = "tie";
            } else if (winner.equals("a")) {
                result = swapped ? "reference" : "generated";
            } else if (winner.equals("b")) {
                result = swapped ? "generated" : "reference";
            } else {
                result = "tie";
            }

            return new Comparison(genId, refId, genProblem, refProblem, result, reason);

        } catch (Exception e) {
            return new Comparison(genId, refId, genProblem, refProblem,
                    "tie", "Parse error: " + e.getMessage());
        }
    }

    /** 评估结果质量等级。 */
    public static String qualityLevel(double winRate) {
        if (winRate >= 0.45 && winRate <= 0.55) return "优秀 - 生成质量接近真题水平";
        if (winRate >= 0.35) return "良好 - 生成质量可用，但略低于真题";
        if (winRate >= 0.25) return "一般 - 生成质量一般，需要改进";
        return "较差 - 生成质量差，需要大幅改进";
    }
}
