package com.example.agent.app;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.BFCLEvaluator;
import com.example.agent.tool.BFCLEvaluator.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BFCL 一键评估 CLI 脚本 — Java 版。
 *
 * 对应 Python 第12章一键评估脚本，提供完整的 BFCL 评估 CLI：
 *   1. 检查/加载 BFCL 数据
 *   2. 运行 HelloAgents 评估
 *   3. 导出 BFCL 格式结果
 *   4. 生成评估报告
 *   5. 展示评估结果
 *
 * 运行:
 *   mvn exec:java -Dexec.mainClass=com.example.agent.app.BfclEvaluationRunner
 *   mvn exec:java -Dexec.mainClass=com.example.agent.app.BfclEvaluationRunner \
 *     -Dexec.args="--category simple_python --samples 10 --model-name my-model"
 *
 * 可选参数:
 *   --category:  评估类别（默认: simple_python）
 *   --samples:   样本数量（默认: 5, 0=全部）
 *   --model-name: 模型名称（默认: LLM 实际模型名）
 *   --data-source: 数据源 huggingface 或本地 JSON 路径（默认: huggingface）
 */
public class BfclEvaluationRunner {

    /** BFCL 函数调用系统提示词。 */
    static final String FUNCTION_CALLING_SYSTEM_PROMPT = """
            你是一个专业的函数调用助手。

            你的任务是：根据用户的问题和提供的函数定义，生成正确的函数调用。

            输出格式要求：
            1. 必须是纯JSON格式，不要添加任何解释文字
            2. 使用JSON数组格式：[{"name": "函数名", "arguments": {"参数名": "参数值"}}]
            3. 如果需要调用多个函数，在数组中添加多个对象
            4. 如果不需要调用函数，返回空数组：[]

            示例：
            用户问题：查询北京的天气
            可用函数：get_weather(city: str)
            正确输出：[{"name": "get_weather", "arguments": {"city": "北京"}}]

            注意：
            - 只输出JSON，不要添加"好的"、"我来帮你"等额外文字
            - 参数值必须与函数定义的类型匹配
            - 参数名必须与函数定义完全一致""";

    public static void main(String[] args) {
        // 解析参数
        String category = "simple_python";
        int maxSamples = 5;
        String modelName = null;
        String dataSource = "huggingface";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--category"   -> category = args[++i];
                case "--samples"    -> maxSamples = Integer.parseInt(args[++i]);
                case "--model-name" -> modelName = args[++i];
                case "--data-source" -> dataSource = args[++i];
            }
        }
        // 自动检测本地数据文件（若未显式指定）
        if ("huggingface".equals(dataSource)) {
            dataSource = resolveBfclDataSource(category);
        }

        System.out.println("=".repeat(60));
        System.out.println("BFCL 一键评估脚本 (Java 版)");
        System.out.println("=".repeat(60));
        System.out.println("\n配置:");
        System.out.println("   评估类别: " + category);
        System.out.println("   样本数量: " + (maxSamples > 0 ? maxSamples : "全部"));
        System.out.println("   数据源: " + dataSource);

        // 1. 创建 LLM
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }
        if (modelName == null) modelName = llm.model;
        System.out.println("   模型: " + modelName);

        // 2. 加载数据
        System.out.println("\n" + "=".repeat(60));
        System.out.println("步骤1: 加载 BFCL 数据");
        System.out.println("=".repeat(60));

        BFCLEvaluator evaluator = new BFCLEvaluator();
        List<BfclSample> samples;

        try {
            if (!"huggingface".equals(dataSource) && Files.exists(Path.of(dataSource))) {
                String json = Files.readString(Path.of(dataSource));
                samples = evaluator.loadFromJson(json);
                if (maxSamples > 0 && samples.size() > maxSamples) {
                    samples = samples.subList(0, maxSamples);
                }
            } else {
                samples = evaluator.loadFromHuggingFace(category, maxSamples);
            }
        } catch (Exception e) {
            System.out.println("❌ 数据加载失败: " + e.getMessage());
            System.out.println("\nHuggingFace 不可访问。解决方案:");
            System.out.println("  1. 使用本地测试数据:");
            System.out.println("     --data-source evaluation_data/BFCL_v4_simple_python_test.json");
            System.out.println("  2. 或使用 v3 参考数据 (无法评分):");
            System.out.println("     --data-source evaluation_data/BFCL_v3_simple.json");
            System.out.println("  3. 获取完整 v4 数据需 HuggingFace Token，见 evaluation_data/README.md");
            return;
        }

        System.out.println("✅ 加载了 " + samples.size() + " 个测试样本");

        // 3. 运行评估
        System.out.println("\n" + "=".repeat(60));
        System.out.println("步骤2: 运行 HelloAgents 评估");
        System.out.println("=".repeat(60));

        LlmCaller caller = buildCaller(llm);
        List<EvalResult> results = evaluator.evaluate(caller, samples);

        long correct = results.stream().filter(EvalResult::success).count();
        double accuracy = (double) correct / results.size();

        // 构建结果汇总
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("category", category);
        summary.put("model_name", modelName);
        summary.put("total_samples", (long) results.size());
        summary.put("correct_samples", correct);
        summary.put("overall_accuracy", String.format("%.2f%%", accuracy * 100));
        summary.put("detailed_results", results);

        System.out.println("\n📊 评估结果:");
        System.out.println("   准确率: " + String.format("%.2f%%", accuracy * 100));
        System.out.println("   正确数: " + correct + "/" + results.size());

        // 4. 导出
        System.out.println("\n" + "=".repeat(60));
        System.out.println("步骤3: 导出 BFCL 格式结果");
        System.out.println("=".repeat(60));

        String exportJson = evaluator.exportToBfclFormat(results, modelName);
        try {
            Path exportDir = Path.of("evaluation_results", "bfcl_official");
            Files.createDirectories(exportDir);
            Path exportFile = exportDir.resolve("BFCL_v4_" + category + "_result.json");
            Files.writeString(exportFile, exportJson);
            System.out.println("✅ 结果已导出: " + exportFile.toAbsolutePath());

            // 也复制到 BFCL result 目录（适配 bfcl CLI）
            String safeModelName = modelName.replace("/", "_");
            Path bfclResultDir = Path.of("result", safeModelName);
            Files.createDirectories(bfclResultDir);
            Files.copy(exportFile, bfclResultDir.resolve(exportFile.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("✅ 已复制到 BFCL 结果目录: " + bfclResultDir.toAbsolutePath());

        } catch (IOException e) {
            System.out.println("❌ 导出失败: " + e.getMessage());
        }

        // 5. 生成报告
        System.out.println("\n" + "=".repeat(60));
        System.out.println("步骤4: 生成评估报告");
        System.out.println("=".repeat(60));

        generateReport(summary);

        // 6. 显示详细结果
        System.out.println("\n" + "=".repeat(60));
        System.out.println("步骤5: 详细结果（前5条）");
        System.out.println("=".repeat(60));

        int display = Math.min(5, results.size());
        for (int i = 0; i < display; i++) {
            var d = results.get(i);
            System.out.println("\n样本 " + d.sampleId() + ":");
            System.out.println("   问题: " + d.question());
            System.out.println("   预测: " + d.predicted());
            System.out.println("   期望: " + d.expected());
            System.out.println("   结果: " + (d.success() ? "✅ 正确" : "❌ 错误"));
        }

        // 7. 提示 BFCL 官方评估
        System.out.println("\n" + "=".repeat(60));
        System.out.println("💡 运行 BFCL 官方评估 (需要 Python + bfcl-eval):");
        System.out.println("   pip install bfcl-eval");
        System.out.println("   bfcl evaluate --model " + modelName
                + " --test-category " + category);
        System.out.println("=".repeat(60));
        System.out.println("\n✅ 评估完成！");
    }

    // ==================== LLM 适配 ====================

    private static LlmCaller buildCaller(HelloAgentsLLM llm) {
        return (messages, toolSchemas) -> {
            try {
                List<com.example.agent.Message> msgList = new ArrayList<>();

                // 添加系统提示词
                msgList.add(new com.example.agent.Message(
                        FUNCTION_CALLING_SYSTEM_PROMPT,
                        com.example.agent.Message.ROLE_SYSTEM));

                for (var m : messages) {
                    msgList.add(new com.example.agent.Message(
                            m.get("content"),
                            "system".equals(m.get("role"))
                                    ? com.example.agent.Message.ROLE_SYSTEM
                                    : com.example.agent.Message.ROLE_USER));
                }

                HelloAgentsLLM.ThinkWithToolsResult result =
                        llm.thinkWithTools(msgList, toolSchemas, null);

                if (result == null)
                    return new LlmCaller.ToolCallResult(List.of(), "LLM returned null");

                if (!result.hasToolCalls())
                    return new LlmCaller.ToolCallResult(List.of(), result.content());

                List<LlmCaller.ToolCall> calls = new ArrayList<>();
                for (var tc : result.toolCalls()) {
                    calls.add(new LlmCaller.ToolCall(tc.name(), tc.arguments()));
                }
                return new LlmCaller.ToolCallResult(calls, null);

            } catch (Exception e) {
                return new LlmCaller.ToolCallResult(List.of(), e.getMessage());
            }
        };
    }

    // ==================== 报告 ====================

    @SuppressWarnings("unchecked")
    private static void generateReport(Map<String, Object> summary) {
        String category = (String) summary.get("category");
        String modelName = (String) summary.get("model_name");
        long correct = ((Number) summary.get("correct_samples")).longValue();
        long total = ((Number) summary.get("total_samples")).longValue();
        double accuracy = (double) correct / total;

        StringBuilder sb = new StringBuilder();
        sb.append("# BFCL 评估报告\n\n");
        sb.append("**生成时间**: ").append(java.time.LocalDateTime.now()).append("\n\n");
        sb.append("## 📊 评估概览\n\n");
        sb.append("- **模型**: ").append(modelName).append("\n");
        sb.append("- **评估类别**: ").append(category).append("\n");
        sb.append("- **总体准确率**: ").append(String.format("%.2f%%", accuracy * 100)).append("\n");
        sb.append("- **正确/总数**: ").append(correct).append("/").append(total).append("\n\n");

        sb.append("```\n");
        int barLen = (int) (accuracy * 50);
        sb.append("█".repeat(barLen)).append("░".repeat(50 - barLen));
        sb.append(" ").append(String.format("%.2f%%", accuracy * 100)).append("\n```\n");

        try {
            Path reportDir = Path.of("evaluation_reports");
            Files.createDirectories(reportDir);
            String ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Files.writeString(reportDir.resolve("bfcl_report_" + ts + ".md"), sb.toString());
            System.out.println("📄 报告已保存: evaluation_reports/bfcl_report_" + ts + ".md");
        } catch (IOException e) {
            System.out.println("⚠️ 报告保存失败: " + e.getMessage());
        }
    }

    // ==================== 数据源自动解析 ====================

    private static String resolveBfclDataSource(String category) {
        String prefix = switch (category) {
            case "simple_python" -> "simple";
            case "simple_java" -> "java";
            case "simple_javascript" -> "javascript";
            default -> category;
        };

        Path v4FromV3 = Path.of("evaluation_data", "BFCL_v4_" + prefix + "_from_v3.json");
        if (Files.exists(v4FromV3)) return v4FromV3.toString();

        Path v4Test = Path.of("evaluation_data", "BFCL_v4_" + prefix + "_test.json");
        if (Files.exists(v4Test)) return v4Test.toString();

        Path v3File = Path.of("evaluation_data", "BFCL_v3_" + prefix + ".json");
        if (Files.exists(v3File)) {
            System.out.println("   ⚠️ 使用 v3 数据 (无 ground_truth，无法评分)");
            return v3File.toString();
        }

        String any = findAnyLocalBfclFile();
        if (any != null) return any;

        return "huggingface";
    }

    private static String findAnyLocalBfclFile() {
        try {
            var dir = Path.of("evaluation_data");
            if (!Files.isDirectory(dir)) return null;
            try (var stream = Files.list(dir)) {
                return stream
                    .filter(p -> p.getFileName().toString().startsWith("BFCL_v4_")
                            && p.getFileName().toString().endsWith(".json"))
                    .findFirst()
                    .map(Path::toString)
                    .orElse(null);
            }
        } catch (IOException e) {
            return null;
        }
    }
}
