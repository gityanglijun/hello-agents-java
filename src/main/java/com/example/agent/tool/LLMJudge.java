package com.example.agent.tool;

import com.example.agent.llm.HelloAgentsLLM;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.*;

/**
 * LLM Judge — 使用 LLM 对生成内容进行多维度评分。
 *
 * 对应 Python hello_agents.evaluation.LLMJudge。
 * 从 4 个维度评估题目质量：
 *   1. 正确性 (Correctness): 题目和答案是否正确
 *   2. 清晰度 (Clarity): 题目表述是否清晰
 *   3. 难度匹配 (Difficulty Match): 难度是否符合目标水平
 *   4. 完整性 (Completeness): 题目是否完整
 *
 * 每维度 1-5 分，附带详细反馈。
 */
public class LLMJudge {

    private static final Gson GSON = new Gson();

    private final HelloAgentsLLM llm;

    /** 单道题目的评估结果。 */
    public record JudgeResult(
            String problemId,
            int correctness,
            int clarity,
            int difficultyMatch,
            int completeness,
            double averageScore,
            String feedback
    ) {}

    /** 聚合统计。 */
    public record JudgeStatistics(
            double avgCorrectness,
            double avgClarity,
            double avgDifficulty,
            double avgCompleteness,
            double avgOverall
    ) {}

    // 评估提示词模板
    private static final String JUDGE_PROMPT = """
            You are an expert judge evaluating the quality of mathematical problems \
            for the AIME (American Invitational Mathematics Examination) level.

            Evaluate the following problem on these 4 dimensions, each scored 1-5:

            1. Correctness (1-5): Is the problem statement mathematically correct? \
            Is the answer correct? Is the solution logically sound?
            2. Clarity (1-5): Is the problem clearly stated? Is the language precise \
            and unambiguous?
            3. Difficulty Match (1-5): Does the problem match AIME difficulty level? \
            AIME problems require deep mathematical reasoning and multiple steps.
            4. Completeness (1-5): Is the problem complete? Are all necessary \
            conditions and constraints stated?

            Output your evaluation as a JSON object:
            {
              "correctness": <1-5>,
              "clarity": <1-5>,
              "difficulty_match": <1-5>,
              "completeness": <1-5>,
              "feedback": "<detailed evaluation comments>"
            }

            Problem:
            %s

            Answer: %s
            Solution: %s""";

    public LLMJudge(HelloAgentsLLM llm) {
        this.llm = llm;
    }

    /**
     * 评估单道题目。
     * @param problem 含 problem/answer/solution 键的 Map
     */
    public JudgeResult evaluateSingle(Map<String, String> problem) {
        String id = problem.getOrDefault("problem_id",
                problem.getOrDefault("id", "unknown"));
        String prompt = String.format(JUDGE_PROMPT,
                problem.getOrDefault("problem", ""),
                problem.getOrDefault("answer", ""),
                problem.getOrDefault("solution", ""));

        try {
            String response = llm.think(List.of(Map.of("role", "user", "content", prompt)));
            return parseJudgeResponse(id, response);
        } catch (Exception e) {
            return new JudgeResult(id, 0, 0, 0, 0, 0,
                    "Evaluation failed: " + e.getMessage());
        }
    }

    /**
     * 批量评估题目。
     * @param problems 题目列表，每个含 problem/answer/solution 键
     */
    public List<JudgeResult> evaluateBatch(List<Map<String, String>> problems) {
        List<JudgeResult> results = new ArrayList<>();
        for (int i = 0; i < problems.size(); i++) {
            System.out.println("  评估 " + (i + 1) + "/" + problems.size() + ": "
                    + problems.get(i).getOrDefault("problem_id", "unknown"));
            results.add(evaluateSingle(problems.get(i)));
        }
        return results;
    }

    /** 从 LLM 回复中解析 JSON 评分。 */
    @SuppressWarnings("unchecked")
    private static JudgeResult parseJudgeResponse(String id, String response) {
        try {
            // 提取 JSON 块
            String json = extractJson(response);
            Map<String, Object> map = GSON.fromJson(json,
                    new TypeToken<Map<String, Object>>() {}.getType());

            int correctness = toIntSafe(map.get("correctness"));
            int clarity = toIntSafe(map.get("clarity"));
            int difficulty = toIntSafe(map.get("difficulty_match"));
            int completeness = toIntSafe(map.get("completeness"));
            String feedback = String.valueOf(map.getOrDefault("feedback", ""));

            double avg = (correctness + clarity + difficulty + completeness) / 4.0;

            return new JudgeResult(id, correctness, clarity, difficulty,
                    completeness, avg, feedback);

        } catch (Exception e) {
            return new JudgeResult(id, 0, 0, 0, 0, 0,
                    "Parse error: " + e.getMessage() + "\nRaw: " + response);
        }
    }

    /** 计算聚合统计。 */
    public JudgeStatistics computeStatistics(List<JudgeResult> results) {
        if (results.isEmpty()) return new JudgeStatistics(0, 0, 0, 0, 0);

        double corr = results.stream().mapToInt(JudgeResult::correctness).average().orElse(0);
        double clar = results.stream().mapToInt(JudgeResult::clarity).average().orElse(0);
        double diff = results.stream().mapToInt(JudgeResult::difficultyMatch).average().orElse(0);
        double comp = results.stream().mapToInt(JudgeResult::completeness).average().orElse(0);
        double overall = results.stream().mapToDouble(JudgeResult::averageScore).average().orElse(0);

        return new JudgeStatistics(corr, clar, diff, comp, overall);
    }

    /** 获取质量等级评价。 */
    public static String qualityLevel(double avgScore) {
        if (avgScore >= 4.0) return "优秀 - 题目质量很高，可以直接使用";
        if (avgScore >= 3.0) return "良好 - 题目质量可用，建议人工审核";
        if (avgScore >= 2.0) return "一般 - 题目质量一般，需要大幅改进";
        return "较差 - 题目质量差，需要重新生成";
    }

    // ==================== 辅助方法 ====================

    /** 从 LLM 回复中提取 JSON 块。 */
    public static String extractJson(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    private static int toIntSafe(Object val) {
        if (val instanceof Number n) {
            int v = n.intValue();
            return Math.max(1, Math.min(v, 5)); // clamp 1-5
        }
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { }
        }
        return 0;
    }
}
