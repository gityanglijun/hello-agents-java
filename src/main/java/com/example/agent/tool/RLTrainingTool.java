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
 * 强化学习训练工具 — RL 训练全流程工具链。
 *
 * 对应 Python 版 hello_agents 的 RLTrainingTool，提供 4 个 action:
 *   - load_dataset: 从 HuggingFace 加载并格式化 GSM8K 数据集
 *   - create_reward: 创建奖励函数（accuracy / length_penalty / step）
 *   - train: 训练配置与启动（SFT / GRPO，实际训练需 Python + TRL）
 *   - evaluate: 模型评估（计算准确率等指标）
 *
 * 数据源: HuggingFace datasets server API
 *
 * 使用示例：
 * <pre>
 *   RLTrainingTool tool = new RLTrainingTool();
 *
 *   // 加载数据集
 *   tool.run(Map.of("action", "load_dataset", "format", "sft", "max_samples", 100));
 *
 *   // 创建奖励函数
 *   tool.run(Map.of("action", "create_reward", "reward_type", "accuracy"));
 *
 *   // 评估
 *   tool.run(Map.of("action", "evaluate", "model_path", "./output", "max_samples", 50));
 * </pre>
 */
public class RLTrainingTool extends Tool {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // HuggingFace datasets server API
    private static final String HF_API = "https://datasets-server.huggingface.co/rows";
    private static final int HF_PAGE_SIZE = 100;

    // 提取 GSM8K 最终答案: "#### 42"
    private static final Pattern FINAL_ANSWER = Pattern.compile("####\\s*(-?[\\d,.]+)");

    private static final Map<String, Integer> TOTAL_SAMPLES = Map.of(
            "train", 7473, "test", 1319
    );

    // 缓存已加载的数据集 split
    private final Map<String, List<Map<String, String>>> cache = new LinkedHashMap<>();

    // 注册的自定义数据集和奖励函数（供外部注入）
    private final Map<String, List<Map<String, Object>>> customDatasets = new LinkedHashMap<>();
    private final Map<String, RewardFn> customRewardFunctions = new LinkedHashMap<>();

    /** 奖励函数接口（供外部实现并注册）。 */
    @FunctionalInterface
    public interface RewardFn {
        List<Double> compute(List<String> completions, List<String> groundTruths);
    }

    // ==================== 构造 ====================

    public RLTrainingTool() {
        super("rl_training",
              "强化学习训练工具。支持 SFT、GRPO 等算法，用于训练和优化语言模型的推理能力。" +
              "也支持数据集加载、奖励函数创建、模型评估等功能。" +
              "操作: train, load_dataset, create_reward, evaluate");
    }

    /** 注册自定义数据集。 */
    public void registerDataset(String name, List<Map<String, Object>> dataset) {
        customDatasets.put(name, dataset);
        System.out.println("✅ 已注册自定义数据集: " + name);
    }

    /** 注册自定义奖励函数。 */
    public void registerRewardFunction(String name, RewardFn fn) {
        customRewardFunctions.put(name, fn);
        System.out.println("✅ 已注册自定义奖励函数: " + name);
    }

    // ==================== Tool 接口 ====================

    @Override
    public String run(Map<String, Object> parameters) {
        String action = ((String) parameters.getOrDefault("action", "train")).toLowerCase();

        try {
            return switch (action) {
                case "train"          -> handleTrain(parameters);
                case "load_dataset"   -> handleLoadDataset(parameters);
                case "create_reward"  -> handleCreateReward(parameters);
                case "evaluate"       -> handleEvaluate(parameters);
                default -> err("不支持的操作: " + action +
                              "。可用: train, load_dataset, create_reward, evaluate");
            };
        } catch (Exception e) {
            return err("操作失败: " + e.getMessage());
        }
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("action", "string",
                "操作类型: train, load_dataset, create_reward, evaluate",
                false, "train"),
            // --- 训练 ---
            new ToolParameter("algorithm", "string",
                "训练算法: sft (监督微调), grpo (群体相对策略优化)", false, "sft"),
            new ToolParameter("model_name", "string",
                "模型名称，如 Qwen/Qwen2-0.5B-Instruct", false, "Qwen/Qwen2-0.5B-Instruct"),
            new ToolParameter("dataset", "string",
                "数据集名称（默认 gsm8k）", false, "gsm8k"),
            new ToolParameter("num_epochs", "integer",
                "训练轮数", false, 3),
            new ToolParameter("output_dir", "string",
                "输出目录", false, "./output"),
            new ToolParameter("use_lora", "boolean",
                "是否使用 LoRA 参数高效微调", false, true),
            new ToolParameter("batch_size", "integer",
                "批次大小", false, 4),
            // --- 数据集加载 ---
            new ToolParameter("format", "string",
                "数据格式: sft (监督微调) 或 rl (强化学习)", false, "sft"),
            new ToolParameter("split", "string",
                "数据分片: train 或 test", false, "train"),
            new ToolParameter("max_samples", "integer",
                "最大加载样本数（0=全部）", false, 100),
            new ToolParameter("offset", "integer",
                "起始偏移量", false, 0),
            // --- 奖励函数 ---
            new ToolParameter("reward_type", "string",
                "奖励类型: accuracy, length_penalty, step", false, "accuracy"),
            new ToolParameter("penalty_weight", "number",
                "长度惩罚权重（仅 length_penalty）", false, 0.001),
            new ToolParameter("max_length", "integer",
                "最大生成长度（仅 length_penalty）", false, 1024),
            new ToolParameter("step_bonus", "number",
                "步骤奖励（仅 step 类型）", false, 0.1),
            // --- 评估 ---
            new ToolParameter("model_path", "string",
                "模型路径（仅 evaluate，需要）", false, null)
        );
    }

    // ==================== 1. load_dataset ====================

    @SuppressWarnings("unchecked")
    private String handleLoadDataset(Map<String, Object> params) {
        String format = (String) params.getOrDefault("format", "sft");
        String split = (String) params.getOrDefault("split", "train");
        int maxSamples = toInt(params.getOrDefault("max_samples", 100));
        int offset = toInt(params.getOrDefault("offset", 0));
        String modelName = (String) params.getOrDefault("model_name", "Qwen/Qwen3-0.6B");

        List<Map<String, String>> rawRows;
        try {
            rawRows = loadRows(split, maxSamples, offset);
        } catch (Exception e) {
            return err("数据集加载失败: " + e.getMessage());
        }

        if (rawRows.isEmpty()) return err("未加载到任何数据");

        List<Map<String, Object>> formatted = new ArrayList<>();
        for (var row : rawRows) {
            String question = row.getOrDefault("question", "");
            String answer = row.getOrDefault("answer", "");
            formatted.add(formatSample(format, question, answer, modelName));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("format", format);
        result.put("split", split);
        result.put("dataset_size", formatted.size());
        result.put("sample_keys", format.equals("rl")
                ? List.of("prompt", "ground_truth", "question", "full_answer")
                : List.of("prompt", "completion", "text"));
        result.put("samples", formatted);
        result.put("total_in_split", TOTAL_SAMPLES.getOrDefault(split, -1));

        return GSON.toJson(result);
    }

    // ==================== 2. create_reward ====================

    @SuppressWarnings("unchecked")
    private String handleCreateReward(Map<String, Object> params) {
        String rewardType = ((String) params.getOrDefault("reward_type", "accuracy")).toLowerCase();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("reward_type", rewardType);

        switch (rewardType) {
            case "accuracy" -> {
                result.put("description", "准确性奖励函数: 答案正确=1.0, 错误=0.0");
                result.put("implementation",
                        "比较 completion 中提取的 #### 答案 与 ground_truth 是否一致");
            }
            case "length_penalty" -> {
                double penaltyWeight = toDouble(params.getOrDefault("penalty_weight", 0.001));
                int maxLength = toInt(params.getOrDefault("max_length", 1024));
                result.put("penalty_weight", penaltyWeight);
                result.put("max_length", maxLength);
                result.put("description",
                        "长度惩罚奖励: base_reward - " + penaltyWeight + " × (len / " + maxLength + ")");
                result.put("implementation",
                        "在 accuracy 奖励基础上，对过长输出施加线性惩罚");
            }
            case "step" -> {
                double stepBonus = toDouble(params.getOrDefault("step_bonus", 0.1));
                result.put("step_bonus", stepBonus);
                result.put("description",
                        "步骤奖励: base_reward + " + stepBonus + " × 推理步骤数");
                result.put("implementation",
                        "在 accuracy 奖励基础上，对包含推理步骤的输出给予额外奖励");
            }
            default -> {
                return err("不支持的奖励类型: " + rewardType +
                          "。支持: accuracy, length_penalty, step");
            }
        }

        return GSON.toJson(result);
    }

    // ==================== 3. train ====================

    private String handleTrain(Map<String, Object> params) {
        String algorithm = ((String) params.getOrDefault("algorithm", "sft")).toLowerCase();
        String modelName = (String) params.getOrDefault("model_name", "Qwen/Qwen2-0.5B-Instruct");
        String datasetName = (String) params.getOrDefault("dataset", "gsm8k");
        int maxSamples = toInt(params.getOrDefault("max_samples", 0));
        int numEpochs = toInt(params.getOrDefault("num_epochs", 3));
        String outputDir = (String) params.getOrDefault("output_dir", "./output");
        boolean useLora = toBool(params.getOrDefault("use_lora", true));
        int batchSize = toInt(params.getOrDefault("batch_size", 4));

        if (!List.of("sft", "grpo").contains(algorithm)) {
            return err("不支持的算法: " + algorithm + "。支持: sft, grpo");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("algorithm", algorithm.toUpperCase());
        result.put("model", modelName);
        result.put("output_dir", outputDir);
        result.put("num_epochs", numEpochs);
        result.put("batch_size", batchSize);
        result.put("use_lora", useLora);

        // 实际训练需要 Python + TRL + transformers + torch
        // Java 端提供配置验证、数据准备、训练参数记录
        result.put("note", "实际训练需 Python 环境: pip install hello-agents[rl]");

        if ("sft".equals(algorithm)) {
            result.put("data_format", "SFT (prompt + completion → text)");
            result.put("description", "监督微调: 模型直接学习 GSM8K 的完整推理链");
        } else {
            result.put("data_format", "RL (prompt + ground_truth, 奖励驱动)");
            result.put("description",
                    "GRPO 强化学习: 模型通过准确性奖励信号自主改进推理能力");
        }

        System.out.println("=" .repeat(60));
        System.out.println("🚀 " + algorithm.toUpperCase() + " 训练配置已生成");
        System.out.println("   模型: " + modelName);
        System.out.println("   数据集: " + datasetName +
                (maxSamples > 0 ? " (" + maxSamples + " 条)" : " (全部)"));
        System.out.println("   轮数: " + numEpochs + " | 批次: " + batchSize +
                " | LoRA: " + useLora);
        System.out.println("   输出: " + outputDir);
        System.out.println("=" .repeat(60));

        return GSON.toJson(result);
    }

    // ==================== 4. evaluate ====================

    @SuppressWarnings("unchecked")
    private String handleEvaluate(Map<String, Object> params) {
        String modelPath = (String) params.get("model_path");
        if (modelPath == null || modelPath.isBlank()) {
            return err("缺少必需参数: model_path");
        }
        int maxSamples = toInt(params.getOrDefault("max_samples", 100));

        // 加载测试数据
        List<Map<String, String>> testRows;
        try {
            testRows = loadRows("test", maxSamples, 0);
        } catch (Exception e) {
            return err("测试数据加载失败: " + e.getMessage());
        }

        if (testRows.isEmpty()) return err("测试数据为空");

        // 提取 ground_truth
        List<String> groundTruths = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        for (var row : testRows) {
            questions.add(row.get("question"));
            groundTruths.add(extractFinalAnswer(row.get("answer")));
        }

        // 模拟评估：实际部署时会加载模型并生成 completion
        // 这里使用 ground_truth 本身作为 completion 来演示评估流程
        // (真实场景中 completion 来自模型推理)
        List<String> dummyCompletions = new ArrayList<>(groundTruths);

        // 计算 accuracy 奖励
        List<Double> rewards = computeAccuracyReward(dummyCompletions, groundTruths);
        double avgReward = rewards.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        long correct = rewards.stream().filter(r -> r >= 1.0).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("model_path", modelPath);
        result.put("num_samples", rewards.size());
        result.put("accuracy", String.format("%.2f%%", avgReward * 100));
        result.put("average_reward", String.format("%.4f", avgReward));
        result.put("correct", correct);
        result.put("total", rewards.size());
        result.put("note", "当前为演示评估（completion == ground_truth）。" +
                "实际部署需要加载训练好的模型进行推理。");

        System.out.println("✅ 评估完成!");
        System.out.println("   准确率: " + String.format("%.2f%%", avgReward * 100));
        System.out.println("   正确: " + correct + " / " + rewards.size());

        return GSON.toJson(result);
    }

    // ==================== 奖励函数实现（纯 Java） ====================

    /**
     * 准确性奖励: 提取 completion 中的 #### 答案，与 ground_truth 比较。
     * 匹配 → 1.0，否则 → 0.0
     */
    static List<Double> computeAccuracyReward(List<String> completions,
                                               List<String> groundTruths) {
        List<Double> rewards = new ArrayList<>();
        for (int i = 0; i < completions.size(); i++) {
            String pred = extractFinalAnswer(completions.get(i));
            String gt = groundTruths.get(i);
            rewards.add(pred.equals(gt) ? 1.0 : 0.0);
        }
        return rewards;
    }

    /**
     * 长度惩罚奖励: accuracy_reward - penaltyWeight × (completion_length / maxLength)
     */
    static List<Double> computeLengthPenaltyReward(List<String> completions,
                                                    List<String> groundTruths,
                                                    double penaltyWeight, int maxLength) {
        List<Double> baseRewards = computeAccuracyReward(completions, groundTruths);
        List<Double> rewards = new ArrayList<>();
        for (int i = 0; i < baseRewards.size(); i++) {
            double lenPenalty = penaltyWeight * ((double) completions.get(i).length() / maxLength);
            rewards.add(Math.max(0.0, baseRewards.get(i) - lenPenalty));
        }
        return rewards;
    }

    /**
     * 步骤奖励: accuracy_reward + stepBonus × (推理步骤数)
     * 步骤数通过统计 "\n" 行数来近似
     */
    static List<Double> computeStepReward(List<String> completions,
                                           List<String> groundTruths,
                                           double stepBonus) {
        List<Double> baseRewards = computeAccuracyReward(completions, groundTruths);
        List<Double> rewards = new ArrayList<>();
        for (int i = 0; i < baseRewards.size(); i++) {
            long steps = completions.get(i).lines().count();
            rewards.add(Math.min(2.0, baseRewards.get(i) + stepBonus * steps));
        }
        return rewards;
    }

    // ==================== 数据加载 ====================

    private List<Map<String, String>> loadRows(String split, int maxSamples, int offset)
            throws Exception {

        if (cache.containsKey(split)) {
            List<Map<String, String>> all = cache.get(split);
            int end = (maxSamples <= 0) ? all.size()
                    : Math.min(offset + maxSamples, all.size());
            if (offset >= all.size()) return List.of();
            return all.subList(offset, end);
        }

        System.out.println("[RLTrainingTool] 从 HuggingFace 加载 GSM8K/" + split + " ...");
        List<Map<String, String>> allRows = new ArrayList<>();
        int page = 0;

        while (true) {
            int pageOffset = page * HF_PAGE_SIZE;
            String url = HF_API + "?dataset=gsm8k&config=main&split=" + split
                    + "&offset=" + pageOffset + "&length=" + HF_PAGE_SIZE;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("HuggingFace API 返回 HTTP " + response.statusCode());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = GSON.fromJson(response.body(),
                    new TypeToken<Map<String, Object>>() {}.getType());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");

            if (rows == null || rows.isEmpty()) break;

            for (var r : rows) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) r.get("row");
                Map<String, String> record = new LinkedHashMap<>();
                record.put("question", String.valueOf(row.getOrDefault("question", "")));
                record.put("answer", String.valueOf(row.getOrDefault("answer", "")));
                allRows.add(record);
            }

            double numTotal = ((Number) body.getOrDefault("num_rows_total", 0)).doubleValue();
            int loaded = pageOffset + rows.size();
            System.out.println("  已加载 " + loaded + " / " + (int) numTotal + " 条");
            if (loaded >= numTotal) break;
            page++;
        }

        cache.put(split, allRows);
        System.out.println("[RLTrainingTool] ✅ 加载完成: " + allRows.size() + " 条");

        int end = (maxSamples <= 0) ? allRows.size()
                : Math.min(offset + maxSamples, allRows.size());
        if (offset >= allRows.size()) return List.of();
        return allRows.subList(offset, end);
    }

    // ==================== 格式化 ====================

    private static Map<String, Object> formatSample(String format, String question,
                                                     String answer, String modelName) {
        if ("rl".equalsIgnoreCase(format)) return formatRL(question, answer, modelName);
        return formatSFT(question, answer);
    }

    private static Map<String, Object> formatSFT(String question, String answer) {
        String prompt = "Question: " + question + "\n\nLet's solve this step by step:\n";
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("prompt", prompt);
        sample.put("completion", answer);
        sample.put("text", prompt + answer);
        return sample;
    }

    private static Map<String, Object> formatRL(String question, String answer, String modelName) {
        String prompt = "<|im_start|>user\n" + question + "\n<|im_end|>\n"
                + "<|im_start|>assistant\n";
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("prompt", prompt);
        sample.put("ground_truth", extractFinalAnswer(answer));
        sample.put("question", question);
        sample.put("full_answer", answer);
        return sample;
    }

    static String extractFinalAnswer(String answer) {
        if (answer == null || answer.isBlank()) return "";
        Matcher m = FINAL_ANSWER.matcher(answer);
        if (m.find()) return m.group(1);
        String[] lines = answer.strip().split("\n");
        return lines[lines.length - 1].trim();
    }

    // ==================== 辅助 ====================

    private static String err(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("message", msg);
        return GSON.toJson(r);
    }

    private static int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); }
            catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }

    private static boolean toBool(Object val) {
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return true;
    }

    public void clearCache() { cache.clear(); }
    public int cacheSize() { return cache.values().stream().mapToInt(List::size).sum(); }
}
