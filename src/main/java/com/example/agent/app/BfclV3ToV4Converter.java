package com.example.agent.app;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.BFCLEvaluator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * BFCL v3 → v4 格式转换器。
 *
 * BFCL v3 JSONL 数据缺少 ground_truth（只有 id/question/function），
 * 此工具使用 LLM 为每个问题生成期望的函数调用，输出 v4 兼容 JSON。
 *
 * 运行:
 *   mvn exec:java -Dexec.mainClass=com.example.agent.app.BfclV3ToV4Converter \
 *     -Dexec.args="simple 10"
 *
 * 参数:
 *   arg[0]: 类别 (simple/multiple/parallel/parallel_multiple/irrelevance/java/javascript)
 *   arg[1]: 最大样本数 (默认 10, 0=全部)
 */
public class BfclV3ToV4Converter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String SYSTEM_PROMPT = """
            You are a function calling assistant. Given a user question and \
            available functions, output the correct function call.

            Output ONLY a JSON object (no explanation):
            {"name": "<function_name>", "arguments": {<argument_dict>}}

            If NO function should be called, output:
            {"name": "", "arguments": {}}""";

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        String category = args.length > 0 ? args[0] : "simple";
        int maxSamples = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        System.out.println("=".repeat(60));
        System.out.println("BFCL v3 → v4 格式转换");
        System.out.println("=".repeat(60));
        System.out.println("  类别: " + category);
        System.out.println("  样本数: " + (maxSamples > 0 ? maxSamples : "全部"));

        // 1. 初始化 LLM
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("ERR: LLM 初始化失败: " + e.getMessage());
            return;
        }
        System.out.println("  模型: " + llm.model);

        // 2. 加载 v3 JSONL 数据
        Path v3File = Path.of("evaluation_data", "BFCL_v3_" + category + ".json");
        if (!Files.exists(v3File)) {
            System.out.println("ERR: v3 文件不存在: " + v3File.toAbsolutePath());
            System.out.println("请先下载 v3 数据: 见 evaluation_data/README.md");
            return;
        }

        List<Map<String, Object>> v3Samples;
        try {
            BFCLEvaluator evaluator = new BFCLEvaluator();
            String jsonContent = Files.readString(v3File);
            // v3 是 JSONL 格式
            List<Map<String, Object>> loaded = new ArrayList<>();
            for (String line : jsonContent.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String, Object> obj = GSON.fromJson(line, Map.class);
                loaded.add(obj);
            }
            v3Samples = loaded;
        } catch (Exception e) {
            System.out.println("ERR: 加载失败: " + e.getMessage());
            return;
        }

        System.out.println("  加载了 " + v3Samples.size() + " 条 v3 样本");

        if (maxSamples > 0 && v3Samples.size() > maxSamples) {
            v3Samples = v3Samples.subList(0, maxSamples);
        }

        // 3. 转换
        List<Map<String, Object>> v4Results = new ArrayList<>();
        for (int i = 0; i < v3Samples.size(); i++) {
            Map<String, Object> sample = v3Samples.get(i);
            String id = String.valueOf(sample.getOrDefault("id", category + "_" + i));

            System.out.print("  [" + (i + 1) + "/" + v3Samples.size() + "] " + id + " ... ");

            Map<String, Object> groundTruth;
            try {
                groundTruth = generateGroundTruth(llm, sample);
                System.out.println("OK  " + groundTruth);
            } catch (Exception e) {
                System.out.println("ERR: " + e.getMessage());
                groundTruth = Map.of("name", "", "arguments", Map.of());
            }

            Map<String, Object> v4Sample = new LinkedHashMap<>();
            v4Sample.put("id", id);
            v4Sample.put("question", sample.get("question"));
            v4Sample.put("function", sample.get("function"));
            v4Sample.put("ground_truth", groundTruth);
            v4Results.add(v4Sample);

            // 延迟
            if (i < v3Samples.size() - 1) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }

        // 4. 保存
        Path outputFile = Path.of("evaluation_data",
                "BFCL_v4_" + category + "_from_v3.json");
        try {
            Files.writeString(outputFile, GSON.toJson(v4Results));
            System.out.println("\nOK: 已生成 " + v4Results.size() + " 条 → " + outputFile.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("\nERR: 保存失败: " + e.getMessage());
        }

        System.out.println("\n使用方法:");
        System.out.println("  mvn exec:java -Dexec.mainClass=com.example.agent.app.BfclCustomEvaluation \\");
        System.out.println("    -Dexec.args=\"" + category + " 0 evaluation_data/BFCL_v4_"
                + category + "_from_v3.json\"");
    }

    /** 调用 LLM 生成 ground_truth。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> generateGroundTruth(
            HelloAgentsLLM llm, Map<String, Object> sample) throws Exception {

        // 提取问题文本
        String question = extractUserQuestion(sample.get("question"));
        // 提取函数列表
        List<Map<String, Object>> functions =
                (List<Map<String, Object>>) sample.get("function");

        // 格式化函数列表
        StringBuilder funcDesc = new StringBuilder();
        for (var func : functions) {
            String name = String.valueOf(func.get("name"));
            String desc = String.valueOf(func.getOrDefault("description", ""));
            funcDesc.append("Function: ").append(name).append("\n");
            funcDesc.append("Description: ").append(desc).append("\n");

            Map<String, Object> params = (Map<String, Object>) func.get("parameters");
            if (params != null) {
                Map<String, Object> props = (Map<String, Object>) params.get("properties");
                if (props != null) {
                    funcDesc.append("Parameters:\n");
                    for (var entry : props.entrySet()) {
                        Map<String, Object> pinfo = (Map<String, Object>) entry.getValue();
                        funcDesc.append("  ").append(entry.getKey())
                                .append(" (").append(pinfo.getOrDefault("type", "string")).append(")")
                                .append(": ").append(pinfo.getOrDefault("description", "")).append("\n");
                    }
                }
            }
            funcDesc.append("\n");
        }

        // 调用 LLM
        String userPrompt = "Available functions:\n\n" + funcDesc
                + "User question: " + question;

        String response = llm.think(List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt)));

        // 解析 JSON 响应
        String json = response.trim();
        // 去除 markdown 代码块
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start >= 0 && end > start) json = json.substring(start, end).trim();
        }

        Map<String, Object> result = GSON.fromJson(json, Map.class);
        return result != null ? result : Map.of("name", "", "arguments", Map.of());
    }

    /** 从 question 嵌套列表中提取 user 文本。 */
    @SuppressWarnings("unchecked")
    private static String extractUserQuestion(Object questionObj) {
        if (questionObj instanceof List<?> outer) {
            for (var inner : outer) {
                if (inner instanceof List<?> innerList) {
                    for (var msgObj : innerList) {
                        if (msgObj instanceof Map) {
                            Map<String, Object> msg = (Map<String, Object>) msgObj;
                            if ("user".equals(msg.get("role"))) {
                                return String.valueOf(msg.getOrDefault("content", ""));
                            }
                        }
                    }
                }
            }
        }
        return "";
    }
}
