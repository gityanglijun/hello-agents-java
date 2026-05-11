package com.example.agent.tool;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * BFCL 评估器 — 加载 BFCL 数据集并评估 LLM 的函数调用能力。
 *
 * 评估流程:
 *   1. 从 HuggingFace 加载 BFCL v4 数据集（或本地 JSON）
 *   2. 对每条样本: 将 question + function definitions 发送给 LLM
 *   3. 将 LLM 返回的 function call 与 ground_truth 做 AST 式对比
 *   4. 汇总准确率
 *
 * 评分规则（对齐 BFCL 官方）:
 *   - 函数名必须完全匹配
 *   - 参数名和值必须匹配（int→float 自动兼容）
 *   - 多函数调用: 每个调用独立评分，取平均
 */
public class BFCLEvaluator {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // HuggingFace dataset: gorilla-llm/Berkeley-Function-Calling-Leaderboard
    private static final String HF_DATASET = "gorilla-llm/Berkeley-Function-Calling-Leaderboard";

    /** 单条 BFCL 样本。 */
    public record BfclSample(
            String id,
            List<List<Map<String, String>>> question,   // messages
            List<Map<String, Object>> functions,          // available tool definitions
            Map<String, Object> groundTruth               // expected {name, arguments}
    ) {}

    /** 单条评估结果。 */
    public record EvalResult(
            String sampleId,
            String question,
            Map<String, Object> predicted,
            Map<String, Object> expected,
            boolean success,
            String error
    ) {}

    // ==================== 数据加载 ====================

    /**
     * 从 HuggingFace API 加载 BFCL 数据集。
     * @param category 如 "simple_python", "multiple", "parallel", "irrelevance"
     * @param maxSamples 最大样本数 (0 = 全部)
     */
    public List<BfclSample> loadFromHuggingFace(String category, int maxSamples)
            throws Exception {
        // BFCL v4 在 HuggingFace 上的 config 名
        String config = mapCategoryToConfig(category);
        System.out.println("[BFCL] 从 HuggingFace 加载 " + config + " (" + category + ") ...");

        List<BfclSample> samples = new ArrayList<>();
        int offset = 0;
        int pageSize = 100;

        while (true) {
            String url = "https://datasets-server.huggingface.co/rows"
                    + "?dataset=" + HF_DATASET
                    + "&config=" + config
                    + "&split=train"
                    + "&offset=" + offset
                    + "&length=" + pageSize;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                throw new RuntimeException("HuggingFace API 返回 HTTP " + resp.statusCode()
                        + " (config: " + config + ")");
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
                BfclSample sample = parseSample(row);
                if (sample != null) samples.add(sample);

                if (maxSamples > 0 && samples.size() >= maxSamples) break;
            }

            if (maxSamples > 0 && samples.size() >= maxSamples) break;

            double numTotal = ((Number) body.getOrDefault("num_rows_total", 0)).doubleValue();
            offset += rows.size();
            System.out.println("  已加载 " + offset + " / " + (int) numTotal + " 条");
            if (offset >= numTotal) break;
        }

        System.out.println("[BFCL] ✅ 加载完成: " + samples.size() + " 条");
        return samples;
    }

    /**
     * 从本地 JSON 加载 BFCL 数据。支持两种格式：
     *   1. JSON 数组: [{id, question, function, ground_truth}, ...] (v4 格式)
     *   2. JSONL: 每行一个 JSON 对象 (v3 格式，无 ground_truth)
     *
     * v3 数据不含 ground_truth，因此无法做 AST 评分，仅适用于执行式评估。
     */
    @SuppressWarnings("unchecked")
    public List<BfclSample> loadFromJson(String jsonContent) {
        List<Map<String, Object>> raw;
        String trimmed = jsonContent.trim();
        if (trimmed.startsWith("[")) {
            // JSON 数组格式 (v4)
            raw = GSON.fromJson(trimmed,
                    new TypeToken<List<Map<String, Object>>>() {}.getType());
        } else {
            // JSONL 格式 (v3): 每行一个独立 JSON 对象
            raw = new ArrayList<>();
            for (String line : trimmed.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String, Object> obj = GSON.fromJson(line,
                        new TypeToken<Map<String, Object>>() {}.getType());
                raw.add(obj);
            }
            System.out.println("[BFCL] 检测到 JSONL 格式 (v3 数据)，已解析 " + raw.size() + " 条");
        }
        List<BfclSample> samples = new ArrayList<>();
        for (var r : raw) {
            BfclSample s = parseSample(r);
            if (s != null) samples.add(s);
        }
        return samples;
    }

    /** 从 HF API 返回的 row Map 解析为 BfclSample。 */
    @SuppressWarnings("unchecked")
    private static BfclSample parseSample(Map<String, Object> row) {
        try {
            String id = String.valueOf(row.getOrDefault("id", "unknown"));

            // question: [[{role, content}, ...], ...]  (可能是嵌套列表)
            List<List<Map<String, String>>> question = new ArrayList<>();
            Object qObj = row.get("question");
            if (qObj instanceof List<?> outer) {
                for (var inner : outer) {
                    if (inner instanceof List<?> innerList) {
                        List<Map<String, String>> turn = new ArrayList<>();
                        for (var msgObj : innerList) {
                            if (msgObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> m = (Map<String, Object>) msgObj;
                                Map<String, String> msg = new LinkedHashMap<>();
                                msg.put("role", String.valueOf(m.getOrDefault("role", "user")));
                                msg.put("content", String.valueOf(m.getOrDefault("content", "")));
                                turn.add(msg);
                            }
                        }
                        question.add(turn);
                    }
                }
            }

            // functions: [{name, description, parameters: {properties, required}}, ...]
            List<Map<String, Object>> functions = new ArrayList<>();
            Object fObj = row.get("function");
            if (fObj instanceof List<?> flist) {
                for (var f : flist) {
                    if (f instanceof Map<?, ?> fm) {
                        functions.add(new LinkedHashMap<>((Map<String, Object>) fm));
                    }
                }
            }

            // ground_truth: {name, arguments}
            Map<String, Object> groundTruth = new LinkedHashMap<>();
            Object gObj = row.get("ground_truth");
            if (gObj instanceof Map<?, ?> gm) {
                groundTruth = new LinkedHashMap<>((Map<String, Object>) gm);
            } else if (gObj instanceof String gs) {
                groundTruth = GSON.fromJson(gs,
                        new TypeToken<Map<String, Object>>() {}.getType());
            }

            return new BfclSample(id, question, functions, groundTruth);

        } catch (Exception e) {
            System.err.println("[BFCL] 解析样本失败: " + e.getMessage());
            return null;
        }
    }

    /** category → HF config 映射。 */
    private static String mapCategoryToConfig(String category) {
        return switch (category) {
            case "simple_python"     -> "BFCL_v4_simple_python";
            case "simple_java"       -> "BFCL_v4_simple_java";
            case "simple_javascript" -> "BFCL_v4_simple_javascript";
            case "multiple"          -> "BFCL_v4_multiple";
            case "parallel"          -> "BFCL_v4_parallel";
            case "parallel_multiple" -> "BFCL_v4_parallel_multiple";
            case "irrelevance"       -> "BFCL_v4_irrelevance";
            default -> category;  // 直接当 config 名用
        };
    }

    // ==================== 评估逻辑 ====================

    /**
     * 评估 LLM 的函数调用能力。
     *
     * @param llmCaller 函数式接口: 传入 (messages, toolSchemas)，返回 LLM 的 tool call 结果
     * @param samples   待评估样本
     * @return 评估结果列表
     */
    public List<EvalResult> evaluate(LlmCaller llmCaller, List<BfclSample> samples) {
        List<EvalResult> results = new ArrayList<>();
        int correct = 0;

        for (int i = 0; i < samples.size(); i++) {
            BfclSample sample = samples.get(i);
            System.out.println("  评估 " + (i + 1) + "/" + samples.size()
                    + ": " + sample.id());

            try {
                // 1. 构建 messages: system (可选) + question turns
                List<Map<String, String>> messages = new ArrayList<>();
                for (var turn : sample.question()) {
                    messages.addAll(turn);
                }

                // 2. 将 functions 转为 OpenAI tool schema 格式
                List<Map<String, Object>> toolSchemas = buildToolSchemas(sample.functions());

                // 3. 调用 LLM
                LlmCaller.ToolCallResult tcResult = llmCaller.call(messages, toolSchemas);

                // 4. 评分: 匹配 ground_truth
                Map<String, Object> predicted = new LinkedHashMap<>();
                if (tcResult.toolCalls() != null && !tcResult.toolCalls().isEmpty()) {
                    var firstCall = tcResult.toolCalls().get(0);
                    predicted.put("name", firstCall.name());
                    predicted.put("arguments", firstCall.arguments());
                }

                boolean success = scoreMatch(predicted, sample.groundTruth());

                if (success) correct++;

                results.add(new EvalResult(
                        sample.id(),
                        extractQuestionText(sample.question()),
                        predicted,
                        sample.groundTruth(),
                        success,
                        tcResult.error()));

                System.out.println("    " + (success ? "✅" : "❌")
                        + " 预测: " + predicted + " | 期望: " + sample.groundTruth());

            } catch (Exception e) {
                results.add(new EvalResult(
                        sample.id(),
                        extractQuestionText(sample.question()),
                        Map.of(), sample.groundTruth(),
                        false, e.getMessage()));
                System.out.println("    ❌ 异常: " + e.getMessage());
            }
        }

        double accuracy = samples.isEmpty() ? 0.0 : (double) correct / samples.size();
        System.out.println("\n  最终: " + correct + "/" + samples.size()
                + " (" + String.format("%.2f%%", accuracy * 100) + ")");

        return results;
    }

    /** 将 BFCL function 转为 OpenAI tool schema。兼容 v3 ("dict") 和 v4 ("object")。 */
    private static List<Map<String, Object>> buildToolSchemas(
            List<Map<String, Object>> functions) {
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (var func : functions) {
            Map<String, Object> funcDef = new LinkedHashMap<>();
            String name = String.valueOf(func.get("name"));
            funcDef.put("name", sanitizeToolName(name));
            funcDef.put("description", func.getOrDefault("description", ""));

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) func.get("parameters");
            if (params == null) {
                params = Map.of("type", "object", "properties", Map.of());
            } else if ("dict".equals(params.get("type"))) {
                Map<String, Object> fixed = new LinkedHashMap<>(params);
                fixed.put("type", "object");
                params = fixed;
            }
            funcDef.put("parameters", params);

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "function");
            schema.put("function", funcDef);
            schemas.add(schema);
        }
        return schemas;
    }

    // ==================== 评分 ====================

    /**
     * AST 式匹配评分。
     * - 函数名必须完全相同
     * - 参数: 每个 ground_truth 的 key 在 predicted 中存在且值匹配
     * - int ↔ float 自动兼容（如 ground_truth 期望 42.0，预测 42 也通过）
     */
    @SuppressWarnings("unchecked")
    static boolean scoreMatch(Map<String, Object> predicted, Map<String, Object> expected) {
        // 函数名匹配（点/下划线视为等价，兼容 OpenAI API 名称清洗）
        String predName = sanitizeToolName(String.valueOf(predicted.getOrDefault("name", "")));
        String expName = sanitizeToolName(String.valueOf(expected.getOrDefault("name", "")));
        if (predName.isEmpty() || !predName.equals(expName)) return false;

        // 参数匹配
        Map<String, Object> predArgs = (Map<String, Object>) predicted.get("arguments");
        Map<String, Object> expArgs = (Map<String, Object>) expected.get("arguments");

        if (expArgs == null || expArgs.isEmpty()) return true;
        if (predArgs == null) return false;

        for (var entry : expArgs.entrySet()) {
            String key = entry.getKey();
            Object expVal = entry.getValue();
            if (!predArgs.containsKey(key)) return false;

            Object predVal = predArgs.get(key);
            if (!valuesMatch(predVal, expVal)) return false;
        }

        return true;
    }

    /** 值匹配，int→float 兼容。 */
    static boolean valuesMatch(Object pred, Object exp) {
        if (Objects.equals(pred, exp)) return true;

        // null / 空字符串
        if (pred == null || exp == null) return false;

        // 数值兼容: int ↔ float
        if (pred instanceof Number pn && exp instanceof Number en) {
            return Math.abs(pn.doubleValue() - en.doubleValue()) < 1e-9;
        }

        // 字符串化后比较
        String ps = String.valueOf(pred).trim().replace("\"", "");
        String es = String.valueOf(exp).trim().replace("\"", "");
        return ps.equals(es);
    }

    /**
     * 清理工具名以符合 OpenAI API 要求 (^[a-zA-Z0-9_-]+$)。
     * v3 数据使用 Python 限定名如 math.factorial，需将点替换为下划线。
     */
    private static String sanitizeToolName(String name) {
        if (name == null) return "unknown";
        return name.replace('.', '_').replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** 从 question 中提取可读文本。 */
    private static String extractQuestionText(List<List<Map<String, String>>> question) {
        for (var turn : question) {
            for (var msg : turn) {
                if ("user".equals(msg.get("role"))) {
                    String content = msg.get("content");
                    return content.length() > 80 ? content.substring(0, 80) + "..." : content;
                }
            }
        }
        return "";
    }

    // ==================== LlmCaller 接口 ====================

    /** LLM 调用接口: 传入 messages + tool schemas，返回 tool call 列表。 */
    public interface LlmCaller {
        record ToolCallResult(List<ToolCall> toolCalls, String error) {}

        /** 单次 LLM tool call 结果。 */
        record ToolCall(String name, Map<String, Object> arguments) {}

        ToolCallResult call(List<Map<String, String>> messages,
                            List<Map<String, Object>> toolSchemas);
    }

    // ==================== 结果导出 ====================

    /** 导出为 BFCL 官方格式 JSON。 */
    public String exportToBfclFormat(List<EvalResult> results, String modelName) {
        List<Map<String, Object>> output = new ArrayList<>();
        for (var r : results) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", r.sampleId());
            entry.put("model_name", modelName);

            // BFCL 格式: predicted → {name, arguments} 或 error
            if (r.predicted() != null && !r.predicted().isEmpty()) {
                entry.put("predicted", r.predicted());
            } else {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", r.error() != null ? r.error() : "no tool call");
                entry.put("predicted", err);
            }
            entry.put("expected", r.expected());
            entry.put("success", r.success());
            output.add(entry);
        }

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("model", modelName);
        wrapper.put("results", output);
        wrapper.put("total", output.size());
        wrapper.put("correct", output.stream().filter(e -> (Boolean) e.get("success")).count());

        return GSON.toJson(wrapper);
    }
}
