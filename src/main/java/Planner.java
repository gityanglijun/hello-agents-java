import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Planner {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static String PLANNER_PROMPT_TEMPLATE = """
你是一个顶级的AI规划专家。你的任务是将用户提出的复杂问题分解成一个由多个简单步骤组成的行动计划。
请确保计划中的每个步骤都是一个独立的、可执行的子任务，并且严格按照逻辑顺序排列。
你的输出必须是一个严格的 JSON 字符串数组，其中每个元素都是一个描述子任务的字符串。

问题: %s

请严格按照以下格式输出你的计划，```json 与 ``` 作为前后缀是必要的：
```json
["步骤1", "步骤2", "步骤3"]
""";

    public HelloAgentsLLM llmClient;

    public Planner(HelloAgentsLLM llmClient){
        this.llmClient = llmClient;
    }

    public List<String> plan(String question){
        String prompt = PLANNER_PROMPT_TEMPLATE.formatted(question);

        List<Map<String,String>> messages = new ArrayList<>();

        Map<String,String> message = new HashMap<>();
        message.put("role","user");
        message.put("content",prompt);

        messages.add(message);
        System.out.println("--- 正在生成计划 ---");
        String responseText = llmClient.think(messages);
        responseText = responseText != null ? responseText:"";
        System.out.println("✅ 计划已生成:\n"+responseText);

        try {
            String planStr = responseText.split("```json")[1].split("```")[0].strip();
            List<String> plan = parsePlan(planStr);
            return plan;
        }
        catch (Exception e){
            System.out.println("❌ 解析计划时出错: {e}");
            System.out.println("原始响应: {response_text}");
            return new ArrayList<>();
        }
    }


    public List<String> parsePlan(String planStr) {
        try {
            // 直接解析 JSON 数组
            return mapper.readValue(planStr, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // 解析失败时返回空列表（对应 Python 的 except 和类型检查）
            return List.of();
        }
    }
}
