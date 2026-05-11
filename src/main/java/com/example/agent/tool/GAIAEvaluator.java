package com.example.agent.tool;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GAIA 评估器 — 评估 Agent 的通用问题解答能力。
 *
 * GAIA (General AI Assistant) 是 Meta 提出的 Agent 基准测试，包含 466 个
 * 现实世界问题，分为 3 个难度级别。评估方式：
 *   1. 将问题发给 Agent
 *   2. Agent 自由使用工具进行推理
 *   3. 从 Agent 回复中提取 "FINAL ANSWER: ..." 部分
 *   4. 与 ground truth 做精确匹配和部分匹配
 *
 * 数据集: gaia-benchmark/GAIA (HuggingFace, 需申请访问权限 + HF_TOKEN)
 */
public class GAIAEvaluator {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String HF_DATASET = "gaia-benchmark/GAIA";

    // GAIA 答案提取正则
    private static final Pattern FINAL_ANSWER_PATTERN =
            Pattern.compile("FINAL\\s*ANSWER\\s*:\\s*(.+?)(?:\\n|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 单条 GAIA 样本。 */
    public record GaiaSample(
            String id,
            String question,
            String answer,       // ground truth
            int level,
            String fileName      // 附件（可为空）
    ) {}

    /** 单条评估结果。 */
    public record GaiaResult(
            String sampleId,
            String question,
            String predicted,     // 提取的 FINAL ANSWER
            String expected,      // ground truth
            boolean exactMatch,
            boolean partialMatch,
            String rawResponse    // Agent 的完整回复
    ) {}

    // ==================== 数据加载 ====================

    /**
     * 从 HuggingFace 加载 GAIA 数据集。
     * 需要 HF_TOKEN 环境变量（gated dataset）。
     *
     * @param level 难度级别 (1/2/3, 0=全部)
     * @param maxSamples 最大样本数 (0=全部)
     */
    public List<GaiaSample> loadFromHuggingFace(int level, int maxSamples) throws Exception {
        String config = level > 0 ? "2023_level" + level : "2023_all";
        System.out.println("[GAIA] 从 HuggingFace 加载 " + config + " ...");

        String hfToken = System.getenv("HF_TOKEN");
        if (hfToken == null || hfToken.isEmpty()) {
            throw new RuntimeException(
                    "GAIA 是受限数据集，需要设置 HF_TOKEN 环境变量。\n"
                    + "申请访问: https://huggingface.co/datasets/gaia-benchmark/GAIA");
        }

        List<GaiaSample> samples = new ArrayList<>();
        int offset = 0;
        int pageSize = 100;

        while (true) {
            String url = "https://datasets-server.huggingface.co/rows"
                    + "?dataset=" + HF_DATASET
                    + "&config=" + config
                    + "&split=validation"
                    + "&offset=" + offset
                    + "&length=" + pageSize;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + hfToken)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                throw new RuntimeException("HuggingFace API 返回 HTTP " + resp.statusCode()
                        + " (需要有效的 HF_TOKEN 且已申请 GAIA 访问权限)");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = GSON.fromJson(resp.body(),
                    new TypeToken<Map<String, Object>>() {}.getType());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
            if (rows == null || rows.isEmpty()) break;

            for (var r : rows) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) r.get("row");
                GaiaSample sample = parseGaiaSample(row, level);
                if (sample != null) samples.add(sample);
                if (maxSamples > 0 && samples.size() >= maxSamples) break;
            }
            if (maxSamples > 0 && samples.size() >= maxSamples) break;

            double numTotal = ((Number) body.getOrDefault("num_rows_total", 0)).doubleValue();
            offset += rows.size();
            System.out.println("  已加载 " + offset + " / " + (int) numTotal + " 条");
            if (offset >= numTotal) break;
        }

        System.out.println("[GAIA] ✅ 加载完成: " + samples.size() + " 条");
        return samples;
    }

    /** 从本地 JSON 文件加载 GAIA 数据（JSON 数组或 JSONL）。 */
    @SuppressWarnings("unchecked")
    public List<GaiaSample> loadFromJson(String jsonContent) {
        List<Map<String, Object>> raw;
        String trimmed = jsonContent.trim();
        if (trimmed.startsWith("[")) {
            raw = GSON.fromJson(trimmed,
                    new TypeToken<List<Map<String, Object>>>() {}.getType());
        } else {
            raw = new ArrayList<>();
            for (String line : trimmed.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String, Object> obj = GSON.fromJson(line,
                        new TypeToken<Map<String, Object>>() {}.getType());
                raw.add(obj);
            }
            System.out.println("[GAIA] 检测到 JSONL 格式，已解析 " + raw.size() + " 条");
        }
        List<GaiaSample> samples = new ArrayList<>();
        for (var r : raw) {
            GaiaSample s = parseGaiaSample(r, 0);
            if (s != null) samples.add(s);
        }
        return samples;
    }

    @SuppressWarnings("unchecked")
    private static GaiaSample parseGaiaSample(Map<String, Object> row, int defaultLevel) {
        try {
            String id = String.valueOf(row.getOrDefault("id",
                    row.getOrDefault("task_id", "unknown")));
            String question = String.valueOf(row.getOrDefault("question",
                    row.getOrDefault("Question", "")));
            String answer = String.valueOf(row.getOrDefault("answer",
                    row.getOrDefault("Answer", "")));
            int level;
            Object lvlObj = row.get("level");
            if (lvlObj instanceof Number n) {
                level = n.intValue();
            } else if (lvlObj instanceof String s) {
                level = Integer.parseInt(s);
            } else {
                level = defaultLevel;
            }

            String fileName = null;
            Object fnObj = row.get("file_name");
            if (fnObj != null && !"null".equals(String.valueOf(fnObj))) {
                fileName = String.valueOf(fnObj);
            }

            return new GaiaSample(id, question, answer, level, fileName);
        } catch (Exception e) {
            System.err.println("[GAIA] 解析样本失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== 评估逻辑 ====================

    /**
     * 评估 Agent 的 GAIA 表现。
     *
     * @param agentRunner 接收问题文本，返回 Agent 的完整回复（应包含 FINAL ANSWER: ...）
     * @param samples     待评估样本
     * @return 评估结果列表
     */
    public List<GaiaResult> evaluate(AgentRunner agentRunner, List<GaiaSample> samples) {
        List<GaiaResult> results = new ArrayList<>();

        for (int i = 0; i < samples.size(); i++) {
            GaiaSample sample = samples.get(i);
            System.out.println("  评估 " + (i + 1) + "/" + samples.size()
                    + " (Level " + sample.level() + "): " + sample.id());

            try {
                String rawResponse = agentRunner.run(sample.question());
                String predicted = extractFinalAnswer(rawResponse);
                if (predicted == null) predicted = "";

                boolean exactMatch = exactMatch(predicted, sample.answer());
                boolean partialMatch = partialMatch(predicted, sample.answer());

                String status = exactMatch ? "✅" : partialMatch ? "⚠️" : "❌";
                System.out.println("    " + status
                        + " 预测: " + (predicted.length() > 60 ? predicted.substring(0, 60) + "..." : predicted)
                        + " | 期望: " + (sample.answer().length() > 60 ? sample.answer().substring(0, 60) + "..." : sample.answer()));

                results.add(new GaiaResult(
                        sample.id(), sample.question(),
                        predicted, sample.answer(),
                        exactMatch, partialMatch, rawResponse));

            } catch (Exception e) {
                System.out.println("    ❌ 异常: " + e.getMessage());
                results.add(new GaiaResult(
                        sample.id(), sample.question(),
                        "", sample.answer(),
                        false, false, e.getMessage()));
            }
        }

        long exactCorrect = results.stream().filter(GaiaResult::exactMatch).count();
        long partialCorrect = results.stream().filter(GaiaResult::partialMatch).count();
        System.out.println("\n  精确匹配: " + exactCorrect + "/" + samples.size()
                + " (" + String.format("%.2f%%", 100.0 * exactCorrect / samples.size()) + ")");
        System.out.println("  部分匹配: " + partialCorrect + "/" + samples.size()
                + " (" + String.format("%.2f%%", 100.0 * partialCorrect / samples.size()) + ")");

        return results;
    }

    // ==================== 答案提取 ====================

    /**
     * 从 Agent 回复中提取 "FINAL ANSWER: ..." 部分。
     * 按 GAIA 规范：提取 FINAL ANSWER 之后到行尾的内容。
     */
    static String extractFinalAnswer(String response) {
        if (response == null) return null;
        Matcher m = FINAL_ANSWER_PATTERN.matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        // fallback: 如果没找到 FINAL ANSWER 标记，尝试取最后一行
        String[] lines = response.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) return line;
        }
        return response.trim();
    }

    // ==================== 评分 ====================

    /** 精确匹配：规范化后完全相等。 */
    static boolean exactMatch(String predicted, String expected) {
        if (predicted == null || expected == null) return false;
        String np = normalize(predicted);
        String ne = normalize(expected);
        return np.equals(ne);
    }

    /**
     * 部分匹配：预测包含期望 OR 期望包含预测。
     * 对于列表型答案，取交集比率 >= 0.8。
     */
    static boolean partialMatch(String predicted, String expected) {
        if (predicted == null || expected == null) return false;
        if (exactMatch(predicted, expected)) return true;

        String np = normalize(predicted);
        String ne = normalize(expected);

        // 包含关系
        if (np.contains(ne) || ne.contains(np)) return true;

        // 列表匹配：逗号分隔后比较
        if (np.contains(",") || ne.contains(",")) {
            Set<String> predSet = splitList(np);
            Set<String> expectSet = splitList(ne);
            if (predSet.isEmpty() || expectSet.isEmpty()) return false;
            Set<String> intersection = new HashSet<>(predSet);
            intersection.retainAll(expectSet);
            double ratio = (double) intersection.size() / Math.max(predSet.size(), expectSet.size());
            return ratio >= 0.8;
        }

        return false;
    }

    /** 答案规范化。 */
    private static String normalize(String text) {
        if (text == null) return "";
        String s = text.toLowerCase()
                .strip()
                .replaceAll("\\s+", " ")
                // 去掉千位分隔逗号（仅当逗号两边是数字时）
                .replaceAll("(\\d),(\\d)", "$1$2")
                // 去掉末尾句号
                .replaceAll("\\.$", "")
                // 去掉引号
                .replace("\"", "")
                .replace("'", "")
                // 去掉常见的冠词前缀
                .replaceFirst("^(a|an|the)\\s+", "");
        return s;
    }

    private static Set<String> splitList(String text) {
        Set<String> items = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            String item = part.strip()
                    .replaceAll("^\"|\"$", "")
                    .replaceAll("^'|'$", "");
            if (!item.isEmpty()) items.add(item);
        }
        return items;
    }

    // ==================== AgentRunner 接口 ====================

    /** Agent 调用接口：传入问题，返回 Agent 的完整回复。 */
    @FunctionalInterface
    public interface AgentRunner {
        String run(String question) throws Exception;
    }

    // ==================== 结果导出 ====================

    /** 导出为 GAIA 官方提交格式 JSON。 */
    public String exportToGaiaFormat(List<GaiaResult> results, String modelName) {
        List<Map<String, Object>> output = new ArrayList<>();
        for (var r : results) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("task_id", r.sampleId());
            entry.put("model", modelName);
            entry.put("answer", r.predicted());
            entry.put("ground_truth", r.expected());
            entry.put("exact_match", r.exactMatch());
            entry.put("partial_match", r.partialMatch());
            output.add(entry);
        }

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("model", modelName);
        wrapper.put("results", output);
        wrapper.put("total", output.size());
        wrapper.put("exact_correct", output.stream().filter(e -> (Boolean) e.get("exact_match")).count());
        wrapper.put("partial_correct", output.stream().filter(e -> (Boolean) e.get("partial_match")).count());

        return GSON.toJson(wrapper);
    }
}
