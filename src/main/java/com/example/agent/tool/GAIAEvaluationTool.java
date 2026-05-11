package com.example.agent.tool;

import com.example.agent.Agent;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * GAIA 一键评估工具 — 评估 Agent 的通用问题解答能力。
 *
 * 对应 Python 版 hello_agents 的 GAIAEvaluationTool，提供：
 *   - run_eval: 加载 GAIA 数据集 → Agent 回答 → 评分 (精确/部分匹配) → 导出 → 报告
 *   - list_levels: 列出 3 个难度级别
 *
 * GAIA 是 Meta 提出的 Agent 基准测试，包含 466 个现实世界问题，
 * 分为 Level 1 (简单)、Level 2 (中等)、Level 3 (困难)。
 *
 * 使用示例：
 * <pre>
 *   HelloAgentsLLM llm = new HelloAgentsLLM();
 *   SimpleAgent agent = new SimpleAgent("MyAgent", llm, GAIA_SYSTEM_PROMPT);
 *   GAIAEvaluationTool tool = new GAIAEvaluationTool(agent);
 *   String result = tool.run(Map.of("action", "run_eval", "level", 1, "max_samples", 10));
 * </pre>
 */
public class GAIAEvaluationTool extends Tool {

    /** GAIA 官方系统提示词。Agent 必须用此提示词，否则 FINAL ANSWER 提取会失败。 */
    public static final String GAIA_SYSTEM_PROMPT = """
            You are a general AI assistant. I will ask you a question. \
            Report your thoughts, and finish your answer with the following template: \
            FINAL ANSWER: [YOUR FINAL ANSWER].
            YOUR FINAL ANSWER should be a number OR as few words as possible OR \
            a comma separated list of numbers and/or strings.
            If you are asked for a number, don't use comma to write your number \
            neither use units such as $ or percent sign unless specified otherwise.
            If you are asked for a string, don't use articles, neither abbreviations \
            (e.g. for cities), and write the digits in plain text unless specified otherwise.
            If you are asked for a comma separated list, apply the above rules \
            depending of whether the element to be put in the list is a number or a string.""";

    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Agent agent;
    private final GAIAEvaluator evaluator;
    private final Path reportDir;

    public GAIAEvaluationTool(Agent agent) {
        this(agent, Path.of("evaluation_reports"));
    }

    public GAIAEvaluationTool(Agent agent, Path reportDir) {
        super("gaia_evaluation",
              "GAIA 一键评估工具。评估 Agent 的通用问题解答能力，支持 3 个难度级别。" +
              "自动完成数据加载、Agent 推理、答案提取、评分和报告生成。" +
              "级别: 1 (简单), 2 (中等), 3 (困难)");
        this.agent = agent;
        this.evaluator = new GAIAEvaluator();
        this.reportDir = reportDir;
    }

    @Override
    public String run(Map<String, Object> parameters) {
        String action = (String) parameters.getOrDefault("action", "run_eval");
        return switch (action) {
            case "run_eval"        -> handleRunEval(parameters);
            case "list_levels"     -> handleListLevels();
            default -> err("不支持的操作: " + action + "。可用: run_eval, list_levels");
        };
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("action", "string",
                "操作: run_eval (运行评估), list_levels (列出级别)", false, "run_eval"),
            new ToolParameter("level", "integer",
                "GAIA 难度级别: 1 (简单), 2 (中等), 3 (困难), 0 (全部)", false, 1),
            new ToolParameter("max_samples", "integer",
                "评估样本数（默认5，设为0=全部）", false, 5),
            new ToolParameter("model_name", "string",
                "模型名称（用于报告标题）", false, "default"),
            new ToolParameter("data_source", "string",
                "数据源: huggingface (在线，需 HF_TOKEN) 或本地 JSON 文件路径", false, "huggingface"),
            new ToolParameter("export_results", "boolean",
                "是否导出 GAIA 官方格式结果", false, true),
            new ToolParameter("generate_report", "boolean",
                "是否生成 Markdown 报告", false, true)
        );
    }

    // ==================== run_eval ====================

    private String handleRunEval(Map<String, Object> params) {
        int level = toInt(params.getOrDefault("level", 1));
        int maxSamples = toInt(params.getOrDefault("max_samples", 5));
        String modelName = (String) params.getOrDefault("model_name", agent.toString());
        String dataSource = (String) params.getOrDefault("data_source", "huggingface");
        if ("huggingface".equals(dataSource)) {
            Path localGaia = Path.of("evaluation_data", "GAIA_test.json");
            if (Files.exists(localGaia)) dataSource = localGaia.toString();
        }
        boolean exportResults = params.getOrDefault("export_results", true) instanceof Boolean b ? b : true;
        boolean generateReport = params.getOrDefault("generate_report", true) instanceof Boolean b ? b : true;

        System.out.println("\n" + "=".repeat(60));
        System.out.println("GAIA 一键评估");
        System.out.println("=".repeat(60));
        System.out.println("  智能体: " + agent.toString());
        System.out.println("  级别: " + (level > 0 ? "Level " + level : "全部"));
        System.out.println("  样本数: " + (maxSamples > 0 ? maxSamples : "全部"));
        System.out.println("  模型: " + modelName);

        // 步骤1: 加载数据
        System.out.println("\n── 步骤1: 加载 GAIA 数据 ──");

        List<GAIAEvaluator.GaiaSample> samples;
        try {
            if ("huggingface".equals(dataSource)) {
                samples = evaluator.loadFromHuggingFace(level, maxSamples);
            } else {
                String json = Files.readString(Path.of(dataSource));
                samples = evaluator.loadFromJson(json);
                if (maxSamples > 0 && samples.size() > maxSamples) {
                    samples = samples.subList(0, maxSamples);
                }
            }
        } catch (Exception e) {
            return err("数据加载失败: " + e.getMessage() + "\n"
                    + "GAIA 是受限数据集，需要:\n"
                    + "  1. 在 HuggingFace 上申请访问: https://huggingface.co/datasets/gaia-benchmark/GAIA\n"
                    + "  2. 设置 HF_TOKEN 环境变量\n"
                    + "  3. 或使用本地测试数据: --data_source evaluation_data/GAIA_test.json");
        }

        if (samples.isEmpty()) return err("未加载到任何样本");

        // 步骤2: 构建 AgentRunner 并运行评估
        System.out.println("\n── 步骤2: 运行 Agent 评估 ──");
        GAIAEvaluator.AgentRunner runner = agent::run;
        List<GAIAEvaluator.GaiaResult> results = evaluator.evaluate(runner, samples);

        // 统计
        long exactCorrect = results.stream().filter(GAIAEvaluator.GaiaResult::exactMatch).count();
        long partialCorrect = results.stream().filter(GAIAEvaluator.GaiaResult::partialMatch).count();
        double exactRate = results.isEmpty() ? 0 : (double) exactCorrect / results.size();
        double partialRate = results.isEmpty() ? 0 : (double) partialCorrect / results.size();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("level", level);
        summary.put("model_name", modelName);
        summary.put("total_samples", (long) results.size());
        summary.put("exact_correct", exactCorrect);
        summary.put("partial_correct", partialCorrect);
        summary.put("exact_match_rate", String.format("%.2f%%", exactRate * 100));
        summary.put("partial_match_rate", String.format("%.2f%%", partialRate * 100));
        summary.put("detailed_results", results);

        // 步骤3: 导出 + 报告
        System.out.println("\n── 步骤3: 导出结果 + 生成报告 ──");

        if (exportResults) {
            String exportJson = evaluator.exportToGaiaFormat(results, modelName);
            try {
                Files.createDirectories(Path.of("evaluation_results", "gaia_submissions"));
                Path exportFile = Path.of("evaluation_results", "gaia_submissions",
                        "gaia_" + (level > 0 ? "level" + level : "all") + "_result.json");
                Files.writeString(exportFile, exportJson);
                System.out.println("✅ 结果已导出: " + exportFile);
            } catch (IOException e) {
                System.out.println("⚠️ 导出失败: " + e.getMessage());
            }
        }

        if (generateReport) {
            String report = generateReport(summary);
            System.out.println("📄 报告已生成");
        }

        // 打印结果摘要
        System.out.println("\n📊 评估结果:");
        System.out.println("   总样本数: " + results.size());
        System.out.println("   精确匹配: " + exactCorrect + "/" + results.size()
                + " (" + String.format("%.2f%%", exactRate * 100) + ")");
        System.out.println("   部分匹配: " + partialCorrect + "/" + results.size()
                + " (" + String.format("%.2f%%", partialRate * 100) + ")");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "success");
        output.put("exact_match_rate", String.format("%.2f%%", exactRate * 100));
        output.put("partial_match_rate", String.format("%.2f%%", partialRate * 100));
        output.put("exact_correct", exactCorrect);
        output.put("partial_correct", partialCorrect);
        output.put("total_samples", (long) results.size());
        output.put("level", level);

        return GSON.toJson(output);
    }

    // ==================== list_levels ====================

    private String handleListLevels() {
        String info = """
                GAIA 评估级别:
                - Level 1 (简单): 165 个问题 — 基础查询、简单推理
                - Level 2 (中等): 152 个问题 — 多步推理、工具组合
                - Level 3 (困难): 149 个问题 — 复杂推理、长上下文

                数据集: gaia-benchmark/GAIA (HuggingFace, 需申请访问权限)
                配置: 2023_level1 / 2023_level2 / 2023_level3 / 2023_all
                """;
        return info;
    }

    // ==================== 报告生成 ====================

    @SuppressWarnings("unchecked")
    private String generateReport(Map<String, Object> summary) {
        String timestamp = LocalDateTime.now().format(ISO);
        int level = ((Number) summary.get("level")).intValue();
        String modelName = (String) summary.get("model_name");
        long exactCorrect = ((Number) summary.get("exact_correct")).longValue();
        long partialCorrect = ((Number) summary.get("partial_correct")).longValue();
        long total = ((Number) summary.get("total_samples")).longValue();
        double exactRate = (double) exactCorrect / total;
        double partialRate = (double) partialCorrect / total;
        List<GAIAEvaluator.GaiaResult> details =
                (List<GAIAEvaluator.GaiaResult>) summary.get("detailed_results");

        StringBuilder sb = new StringBuilder();
        sb.append("# GAIA 评估报告\n\n");
        sb.append("**生成时间**: ").append(timestamp).append("\n\n");
        sb.append("## 📊 评估概览\n\n");
        sb.append("- **模型**: ").append(modelName).append("\n");
        sb.append("- **难度级别**: ").append(level > 0 ? "Level " + level : "全部").append("\n");
        sb.append("- **精确匹配率**: ").append(String.format("%.2f%%", exactRate * 100)).append("\n");
        sb.append("- **部分匹配率**: ").append(String.format("%.2f%%", partialRate * 100)).append("\n");
        sb.append("- **精确/部分/总数**: ").append(exactCorrect).append("/")
          .append(partialCorrect).append("/").append(total).append("\n\n");

        // 准确率可视化
        sb.append("## 📊 准确率可视化\n\n```\n");
        sb.append("精确: ");
        int barLen = (int) (exactRate * 50);
        sb.append("█".repeat(barLen)).append("░".repeat(50 - barLen));
        sb.append(" ").append(String.format("%.2f%%", exactRate * 100)).append("\n");
        sb.append("部分: ");
        int pBarLen = (int) (partialRate * 50);
        sb.append("█".repeat(pBarLen)).append("░".repeat(50 - pBarLen));
        sb.append(" ").append(String.format("%.2f%%", partialRate * 100)).append("\n```\n\n");

        // 样本详情
        if (details != null && !details.isEmpty()) {
            sb.append("## 📝 样本详情\n\n");
            sb.append("| ID | 问题 | 预测 | 期望 | 结果 |\n");
            sb.append("|----|------|------|------|------|\n");
            for (var d : details) {
                String q = d.question().length() > 40
                        ? d.question().substring(0, 40) + "..." : d.question();
                String pred = d.predicted();
                if (pred.length() > 30) pred = pred.substring(0, 30) + "...";
                String exp = d.expected();
                if (exp.length() > 30) exp = exp.substring(0, 30) + "...";
                String status = d.exactMatch() ? "✅" : d.partialMatch() ? "⚠️" : "❌";
                sb.append("| ").append(d.sampleId())
                  .append(" | ").append(q)
                  .append(" | ").append(pred)
                  .append(" | ").append(exp)
                  .append(" | ").append(status).append(" |\n");
            }
            sb.append("\n");
        }

        // 建议
        sb.append("## 💡 建议\n\n");
        if (exactRate >= 0.6) {
            sb.append("- ✅ 表现优秀！GAIA Level ").append(level).append(" 能力扎实。\n");
            if (level < 3) sb.append("- 💡 建议尝试更高难度级别。\n");
        } else if (exactRate >= 0.3) {
            sb.append("- ⚠️ 表现良好，仍有提升空间。建议：\n");
            sb.append("  - 优化系统提示词，确保 FINAL ANSWER 格式正确\n");
            sb.append("  - 检查答案提取逻辑\n");
            sb.append("  - 增强多步推理能力\n");
        } else {
            sb.append("- ❌ 需要改进。建议：\n");
            sb.append("  - 确认 Agent 使用了 GAIA 官方系统提示词\n");
            sb.append("  - 检查 LLM 模型是否足够强大\n");
            sb.append("  - 增加工具使用能力\n");
        }

        // 保存文件
        try {
            Files.createDirectories(reportDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path reportFile = reportDir.resolve("gaia_report_" + ts + ".md");
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
}
