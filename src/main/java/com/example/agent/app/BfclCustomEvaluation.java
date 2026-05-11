package com.example.agent.app;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.BFCLEvaluator;
import com.example.agent.tool.BFCLEvaluator.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * BFCL 自定义评估 — 使用底层组件进行自定义评估流程。
 *
 * 对应 Python 第12章示例3 (12.2.5 方式3):
 *   1. 创建 LLM → 适配 BFCLEvaluator.LlmCaller
 *   2. 加载数据集 (HuggingFace 或本地 JSON)
 *   3. 创建评估器 → 运行评估
 *   4. 查看每个样本的详细结果
 *   5. 导出 BFCL 格式结果
 *
 * 运行: mvn exec:java -Dexec.mainClass=com.example.agent.app.BfclCustomEvaluation
 */
public class BfclCustomEvaluation {

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("BFCL 自定义评估");
        System.out.println("=".repeat(60));

        // 1. 创建 LLM
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }
        System.out.println("✅ LLM: " + llm.model);

        // 2. 加载数据集
        String category = args.length > 0 ? args[0] : "simple_python";
        int maxSamples = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        String dataSource = args.length > 2 ? args[2] : resolveBfclDataSource(category);

        System.out.println("📚 加载 BFCL 数据集 (类别: " + category + ") ...");

        BFCLEvaluator evaluator = new BFCLEvaluator();
        List<BfclSample> samples;

        try {
            if (!"huggingface".equals(dataSource)) {
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
            System.out.println("   使用本地测试数据代替:");
            String fallback = findAnyLocalBfclFile();
            if (fallback != null) {
                System.out.println("   --data-source " + fallback);
            }
            return;
        }

        System.out.println("✅ 加载了 " + samples.size() + " 个测试样本");

        // 3. 构建 LLM 调用适配器
        LlmCaller caller = buildCaller(llm);

        // 4. 运行评估
        System.out.println("\n🔄 开始评估 (" + samples.size() + " 个样本) ...\n");
        List<EvalResult> results = evaluator.evaluate(caller, samples);

        // 5. 汇总
        long correct = results.stream().filter(EvalResult::success).count();
        double accuracy = (double) correct / results.size();

        System.out.println("\n📊 评估结果:");
        System.out.println("   总样本数: " + results.size());
        System.out.println("   正确样本数: " + correct);
        System.out.println("   准确率: " + String.format("%.2f%%", accuracy * 100));

        // 6. 查看每个样本的详细结果
        System.out.println("\n📝 详细结果:");
        for (var detail : results) {
            System.out.println("样本 " + detail.sampleId() + ":");
            System.out.println("   问题: " + detail.question());
            System.out.println("   预测: " + detail.predicted());
            System.out.println("   正确答案: " + detail.expected());
            System.out.println("   结果: " + (detail.success() ? "✅ 正确" : "❌ 错误"));
            if (detail.error() != null && !detail.error().isEmpty()) {
                System.out.println("   错误信息: " + detail.error());
            }
            System.out.println();
        }

        // 7. 导出结果
        String exportJson = evaluator.exportToBfclFormat(results, llm.model);
        try {
            Path exportDir = Path.of("evaluation_results", "bfcl_official");
            Files.createDirectories(exportDir);
            Path exportFile = exportDir.resolve("BFCL_v4_" + category + "_result.json");
            Files.writeString(exportFile, exportJson);
            System.out.println("✅ 结果已导出: " + exportFile);
        } catch (Exception e) {
            System.out.println("❌ 导出失败: " + e.getMessage());
        }
    }

    /** 将 HelloAgentsLLM 适配为 BFCLEvaluator.LlmCaller。 */
    private static LlmCaller buildCaller(HelloAgentsLLM llm) {
        return (messages, toolSchemas) -> {
            try {
                List<com.example.agent.Message> msgList = new java.util.ArrayList<>();
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

                List<LlmCaller.ToolCall> calls = new java.util.ArrayList<>();
                for (var tc : result.toolCalls()) {
                    calls.add(new LlmCaller.ToolCall(tc.name(), tc.arguments()));
                }
                return new LlmCaller.ToolCallResult(calls, null);

            } catch (Exception e) {
                return new LlmCaller.ToolCallResult(List.of(), e.getMessage());
            }
        };
    }

    // ==================== 数据源自动解析 ====================

    /**
     * 自动选择可用的本地 BFCL 数据文件。
     * 优先级: v4_from_v3 > v4_test > v3 > huggingface
     */
    private static String resolveBfclDataSource(String category) {
        // 将 category 映射为文件前缀 (如 simple_python → simple)
        String prefix = switch (category) {
            case "simple_python", "simple_java", "simple_javascript" ->
                category.replace("simple_", "").equals("python") ? "simple"
                    : category.replace("simple_", "");
            default -> category;
        };

        // 1. 优先用从 v3 转换来的 v4 数据
        Path v4FromV3 = Path.of("evaluation_data", "BFCL_v4_" + prefix + "_from_v3.json");
        if (Files.exists(v4FromV3)) return v4FromV3.toString();

        // 2. 手工测试数据
        Path v4Test = Path.of("evaluation_data", "BFCL_v4_" + prefix + "_test.json");
        if (Files.exists(v4Test)) return v4Test.toString();

        // 3. v3 原始数据 (没有 ground_truth，但可以测试函数调用)
        Path v3File = Path.of("evaluation_data", "BFCL_v3_" + prefix + ".json");
        if (Files.exists(v3File)) {
            System.out.println("   ⚠️ 使用 v3 数据 (无 ground_truth，无法评分)");
            return v3File.toString();
        }

        // 4. 任何可用的 BFCL v4 文件
        String any = findAnyLocalBfclFile();
        if (any != null) return any;

        // 5. 回退到 HuggingFace
        return "huggingface";
    }

    /** 查找任意可用的本地 BFCL 数据文件。 */
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
