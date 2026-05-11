package com.example.agent.tool;

import com.example.agent.llm.HelloAgentsLLM;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * BFCL 一键评估工具 — 评估 LLM 的函数调用能力。
 *
 * 对应 Python 版 hello_agents 的 BFCLEvaluationTool，提供：
 *   - run_eval: 加载数据集 → 评估 LLM → 导出结果 → 生成报告
 *   - list_categories: 列出支持的评估类别
 *
 * 评估流程（等同 Python 版 3 步骤）:
 *   1. 加载 BFCL v4 数据集（HuggingFace API 或本地 JSON）
 *   2. 对每条样本，LLM 接收 question + 工具定义，返回 function call
 *   3. AST 式匹配评分（函数名 + 参数），生成 Markdown 报告
 *
 * 使用示例：
 * <pre>
 *   HelloAgentsLLM llm = new HelloAgentsLLM();
 *   BFCLEvaluationTool tool = new BFCLEvaluationTool(llm);
 *
 *   // 评估 simple_python 类别
 *   String result = tool.run(Map.of(
 *       "action", "run_eval",
 *       "category", "simple_python",
 *       "max_samples", 10));
 * </pre>
 */
public class BFCLEvaluationTool extends Tool {

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HelloAgentsLLM llm;
    private final BFCLEvaluator evaluator;
    private final Path reportDir;

    public BFCLEvaluationTool(HelloAgentsLLM llm) {
        this(llm, null, Path.of("evaluation_reports"));
    }

    /**
     * @param llm       LLM 客户端
     * @param dataDir   BFCL 本地数据目录（null 则从 HuggingFace 在线加载）
     * @param reportDir 报告输出目录
     */
    public BFCLEvaluationTool(HelloAgentsLLM llm, Path dataDir, Path reportDir) {
        super("bfcl_evaluation",
              "BFCL 一键评估工具。评估智能体的工具调用能力，支持 7 个评估类别。" +
              "自动完成数据加载、评估运行、结果导出和报告生成。" +
              "类别: simple_python, simple_java, simple_javascript, " +
              "multiple, parallel, parallel_multiple, irrelevance");
        this.llm = llm;
        this.evaluator = dataDir != null ? null : new BFCLEvaluator();  // 延迟加载
        this.reportDir = reportDir;
    }

    @Override
    public String run(Map<String, Object> parameters) {
        String action = (String) parameters.getOrDefault("action", "run_eval");
        return switch (action) {
            case "run_eval"        -> handleRunEval(parameters);
            case "list_categories" -> handleListCategories();
            default -> err("不支持的操作: " + action + "。可用: run_eval, list_categories");
        };
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("action", "string",
                "操作: run_eval (运行评估), list_categories (列出类别)", false, "run_eval"),
            new ToolParameter("category", "string",
                "评估类别: simple_python, simple_java, simple_javascript, "
                + "multiple, parallel, parallel_multiple, irrelevance",
                false, "simple_python"),
            new ToolParameter("max_samples", "integer",
                "评估样本数（默认5，设为0=全部）", false, 5),
            new ToolParameter("model_name", "string",
                "模型名称（用于报告标题）", false, "default"),
            new ToolParameter("data_source", "string",
                "数据源: huggingface (在线) 或本地 JSON 文件路径", false, "huggingface")
        );
    }

    // ==================== run_eval ====================

    @SuppressWarnings("unchecked")
    private String handleRunEval(Map<String, Object> params) {
        String category = (String) params.getOrDefault("category", "simple_python");
        int maxSamples = toInt(params.getOrDefault("max_samples", 5));
        String modelName = (String) params.getOrDefault("model_name", llm.model);
        String dataSource = (String) params.getOrDefault("data_source", "huggingface");
        if ("huggingface".equals(dataSource)) {
            dataSource = autoDetectBfclFile(category);
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("BFCL 一键评估");
        System.out.println("=".repeat(60));
        System.out.println("  评估类别: " + category);
        System.out.println("  样本数量: " + (maxSamples > 0 ? maxSamples : "全部"));
        System.out.println("  模型: " + modelName);
        System.out.println("  数据源: " + dataSource);

        // 步骤1: 加载数据
        System.out.println("\n── 步骤1: 加载 BFCL 数据 ──");
        List<BFCLEvaluator.BfclSample> samples;

        try {
            if ("huggingface".equals(dataSource)) {
                samples = evaluator.loadFromHuggingFace(category, maxSamples);
            } else {
                // 从本地 JSON 文件加载
                String json = Files.readString(Path.of(dataSource));
                samples = evaluator.loadFromJson(json);
                if (maxSamples > 0 && samples.size() > maxSamples) {
                    samples = samples.subList(0, maxSamples);
                }
            }
        } catch (Exception e) {
            return err("数据加载失败: " + e.getMessage());
        }

        if (samples.isEmpty()) return err("未加载到任何样本");

        // 步骤2: 运行评估
        System.out.println("\n── 步骤2: 运行评估 ──");
        BFCLEvaluator.LlmCaller caller = buildLlmCaller(llm);
        List<BFCLEvaluator.EvalResult> results = evaluator.evaluate(caller, samples);

        // 统计
        long correct = results.stream().filter(BFCLEvaluator.EvalResult::success).count();
        double accuracy = (double) correct / results.size();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("category", category);
        summary.put("model_name", modelName);
        summary.put("total_samples", (long) results.size());
        summary.put("correct_samples", correct);
        summary.put("overall_accuracy", String.format("%.2f%%", accuracy * 100));
        summary.put("detailed_results", results);

        // 步骤3: 导出 + 报告
        System.out.println("\n── 步骤3: 导出结果 + 生成报告 ──");

        // 导出 BFCL 格式 JSON
        String exportJson = evaluator.exportToBfclFormat(results, modelName);
        try {
            Files.createDirectories(Path.of("evaluation_results", "bfcl_official"));
            Path exportFile = Path.of("evaluation_results", "bfcl_official",
                    "BFCL_v4_" + category + "_result.json");
            Files.writeString(exportFile, exportJson);
            System.out.println("✅ 结果已导出: " + exportFile);
        } catch (IOException e) {
            System.out.println("⚠️ 导出失败: " + e.getMessage());
        }

        // 生成 Markdown 报告
        String report = generateReport(summary);
        System.out.println("\n📊 评估结果: " + correct + "/" + results.size()
                + " (" + String.format("%.2f%%", accuracy * 100) + ")");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "success");
        output.put("overall_accuracy", String.format("%.2f%%", accuracy * 100));
        output.put("correct_samples", correct);
        output.put("total_samples", results.size());
        output.put("category", category);
        output.put("report", report);
        output.put("export_file", exportJson.length() > 500
                ? exportJson.substring(0, 500) + "..." : exportJson);

        return GSON.toJson(output);
    }

    // ==================== list_categories ====================

    private String handleListCategories() {
        String info = """
                BFCL v4 评估类别:
                - simple_python       (Python 单函数调用, 399 条)
                - simple_java         (Java 单函数调用, 399 条)
                - simple_javascript   (JavaScript 单函数调用, 399 条)
                - multiple            (多函数选一, 199 条)
                - parallel            (同函数并行调用, 199 条)
                - parallel_multiple   (不同函数并行调用, 199 条)
                - irrelevance         (无关检测—不应调函数, 239 条)

                数据来源: HuggingFace gorilla-llm/Berkeley-Function-Calling-Leaderboard
                """;
        return info;
    }

    // ==================== LLM 调用适配 ====================

    /**
     * 将 HelloAgentsLLM 适配为 BFCLEvaluator.LlmCaller。
     * 使用现有的 thinkWithTools 方法。
     */
    private static BFCLEvaluator.LlmCaller buildLlmCaller(HelloAgentsLLM llm) {
        return (messages, toolSchemas) -> {
            try {
                // 构建 Message 列表
                List<com.example.agent.Message> msgList = new ArrayList<>();
                for (var m : messages) {
                    String role = m.get("role");
                    String content = m.get("content");
                    if ("system".equals(role)) {
                        msgList.add(new com.example.agent.Message(content,
                                com.example.agent.Message.ROLE_SYSTEM));
                    } else {
                        msgList.add(new com.example.agent.Message(content,
                                com.example.agent.Message.ROLE_USER));
                    }
                }

                // 调用 LLM with tools
                HelloAgentsLLM.ThinkWithToolsResult result =
                        llm.thinkWithTools(msgList, toolSchemas, null);

                if (result == null) {
                    return new BFCLEvaluator.LlmCaller.ToolCallResult(
                            List.of(), "LLM returned null");
                }

                if (!result.hasToolCalls()) {
                    // 无 tool call → 可能是 irrelevance 类别的正确行为
                    return new BFCLEvaluator.LlmCaller.ToolCallResult(
                            List.of(), result.content());
                }

                List<BFCLEvaluator.LlmCaller.ToolCall> calls = new ArrayList<>();
                for (var tc : result.toolCalls()) {
                    calls.add(new BFCLEvaluator.LlmCaller.ToolCall(
                            tc.name(), tc.arguments()));
                }

                return new BFCLEvaluator.LlmCaller.ToolCallResult(calls, null);

            } catch (Exception e) {
                return new BFCLEvaluator.LlmCaller.ToolCallResult(
                        List.of(), e.getMessage());
            }
        };
    }

    // ==================== 报告生成 ====================

    @SuppressWarnings("unchecked")
    private String generateReport(Map<String, Object> summary) {
        String timestamp = LocalDateTime.now().format(ISO);
        String category = (String) summary.get("category");
        String modelName = (String) summary.get("model_name");
        long correct = ((Number) summary.get("correct_samples")).longValue();
        long total = ((Number) summary.get("total_samples")).longValue();
        double accuracy = (double) correct / total;
        List<BFCLEvaluator.EvalResult> details =
                (List<BFCLEvaluator.EvalResult>) summary.get("detailed_results");

        StringBuilder sb = new StringBuilder();
        sb.append("# BFCL 评估报告\n\n");
        sb.append("**生成时间**: ").append(timestamp).append("\n\n");
        sb.append("## 📊 评估概览\n\n");
        sb.append("- **模型**: ").append(modelName).append("\n");
        sb.append("- **评估类别**: ").append(category).append("\n");
        sb.append("- **总体准确率**: ").append(String.format("%.2f%%", accuracy * 100)).append("\n");
        sb.append("- **正确/总数**: ").append(correct).append("/").append(total).append("\n\n");

        // 准确率可视化
        sb.append("## 📊 准确率可视化\n\n```\n");
        int barLen = (int) (accuracy * 50);
        sb.append("█".repeat(barLen)).append("░".repeat(50 - barLen));
        sb.append(" ").append(String.format("%.2f%%", accuracy * 100)).append("\n```\n\n");

        // 样本详情
        if (details != null && !details.isEmpty()) {
            sb.append("## 📝 样本详情\n\n");
            sb.append("| 样本ID | 问题 | 预测 | 期望 | 结果 |\n");
            sb.append("|--------|------|------|------|------|\n");
            for (var d : details) {
                String q = d.question().length() > 50
                        ? d.question().substring(0, 50) + "..." : d.question();
                String pred = d.predicted().isEmpty() ? "(无)" : d.predicted().toString();
                if (pred.length() > 40) pred = pred.substring(0, 40) + "...";
                String exp = d.expected().toString();
                if (exp.length() > 40) exp = exp.substring(0, 40) + "...";
                sb.append("| ").append(d.sampleId())
                  .append(" | ").append(q)
                  .append(" | ").append(pred)
                  .append(" | ").append(exp)
                  .append(" | ").append(d.success() ? "✅" : "❌").append(" |\n");
            }
            sb.append("\n");
        }

        // 建议
        sb.append("## 💡 建议\n\n");
        if (accuracy >= 0.9) {
            sb.append("- ✅ 表现优秀！\n");
        } else if (accuracy >= 0.7) {
            sb.append("- ⚠️ 表现良好，仍有提升空间。建议检查错误样本，优化系统提示词。\n");
        } else {
            sb.append("- ❌ 需要改进：优化系统提示词、检查工具定义格式\n");
        }

        // 保存文件
        try {
            Files.createDirectories(reportDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path reportFile = reportDir.resolve("bfcl_report_" + ts + ".md");
            Files.writeString(reportFile, sb.toString());
            System.out.println("📄 报告已保存: " + reportFile);
        } catch (IOException e) {
            System.out.println("⚠️ 报告保存失败: " + e.getMessage());
        }

        return sb.toString();
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
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    /** 自动检测可用的本地 BFCL 数据文件。 */
    private static String autoDetectBfclFile(String category) {
        String prefix = switch (category) {
            case "simple_python" -> "simple";
            case "simple_java" -> "java";
            case "simple_javascript" -> "javascript";
            default -> category;
        };

        for (String suffix : List.of("_from_v3.json", "_test.json", ".json")) {
            Path f = Path.of("evaluation_data", "BFCL_v4_" + prefix + suffix);
            if (Files.exists(f)) return f.toString();
        }
        // v3 fallback
        Path v3 = Path.of("evaluation_data", "BFCL_v3_" + prefix + ".json");
        if (Files.exists(v3)) return v3.toString();

        return "huggingface";
    }
}
