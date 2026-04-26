import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PlanAndSolveAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_PLANNER_PROMPT = """
你是一个顶级的AI规划专家。你的任务是将用户提出的复杂问题分解成一个由多个简单步骤组成的行动计划。
请确保计划中的每个步骤都是一个独立的、可执行的子任务，并且严格按照逻辑顺序排列。
你的输出必须是一个Python列表，其中每个元素都是一个描述子任务的字符串。

问题: {question}

请严格按照以下格式输出你的计划:
```python
["步骤1", "步骤2", "步骤3", ...]
```
""";

    private static final String DEFAULT_EXECUTOR_PROMPT = """
你是一位顶级的AI执行专家。你的任务是严格按照给定的计划，一步步地解决问题。
你将收到原始问题、完整的计划、以及到目前为止已经完成的步骤和结果。
请你专注于解决"当前步骤"，并仅输出该步骤的最终答案，不要输出任何额外的解释或对话。

# 原始问题:
{question}

# 完整计划:
{plan}

# 历史步骤与结果:
{history}

# 当前步骤:
{currentStep}

请仅输出针对"当前步骤"的回答:
""";

    private final String plannerPrompt;
    private final String executorPrompt;

    public PlanAndSolveAgent(
            String name,
            HelloAgentsLLM llm,
            String systemPrompt,
            Config config,
            String customPlannerPrompt,
            String customExecutorPrompt
    ) {
        super(name, llm, systemPrompt, config);
        this.plannerPrompt = customPlannerPrompt != null ? customPlannerPrompt : DEFAULT_PLANNER_PROMPT;
        this.executorPrompt = customExecutorPrompt != null ? customExecutorPrompt : DEFAULT_EXECUTOR_PROMPT;
    }

    public PlanAndSolveAgent(String name, HelloAgentsLLM llm) {
        this(name, llm, null, null, null, null);
    }

    @Override
    public String run(String inputText) {
        System.out.println("🤖 " + name + " 开始规划执行: " + inputText);

        // 第一步：生成计划
        List<String> plan = generatePlan(inputText);
        if (plan == null || plan.isEmpty()) {
            System.out.println("❌ 无法生成有效的行动计划");
            return null;
        }
        System.out.println("📋 计划共 " + plan.size() + " 步");

        // 第二步：逐步执行
        String result = executePlan(inputText, plan);

        // 保存到历史记录
        addMessage(new Message(inputText, Message.ROLE_USER));
        if (result != null) {
            addMessage(new Message(result, Message.ROLE_ASSISTANT));
        }

        System.out.println("✅ " + name + " 规划执行完成");
        return result;
    }

    // ========== 可由子类重写 ==========

    protected List<String> generatePlan(String question) {
        String prompt = plannerPrompt.replace("{question}", question);
        String response = llm.think(buildLLMMessages(prompt));
        if (response == null) return null;

        return parsePlan(response);
    }

    protected String executePlan(String question, List<String> plan) {
        StringBuilder history = new StringBuilder();
        String finalAnswer = "";

        System.out.println("--- 正在执行计划 ---");
        for (int i = 0; i < plan.size(); i++) {
            String step = plan.get(i);
            System.out.println("正在执行步骤 " + (i + 1) + ": " + step);

            String prompt = executorPrompt
                    .replace("{question}", question)
                    .replace("{plan}", plan.toString())
                    .replace("{history}", history.toString())
                    .replace("{currentStep}", step);

            String stepResult = llm.think(buildLLMMessages(prompt));
            stepResult = stepResult != null ? stepResult : "";

            history.append("步骤 ").append(i + 1).append(": ").append(step)
                    .append(" \n结果: ").append(stepResult).append("\n\n");
            finalAnswer = stepResult;
            System.out.println("✅ 步骤 " + (i + 1) + " 已完成");
        }

        return finalAnswer;
    }

    protected List<String> parsePlan(String response) {
        try {
            // 尝试从 ```python ... ``` 中提取
            if (response.contains("```python")) {
                String planStr = response.split("```python")[1].split("```")[0].trim();
                return MAPPER.readValue(planStr, new TypeReference<List<String>>() {});
            }
            // 尝试从 ```json ... ``` 中提取
            if (response.contains("```json")) {
                String planStr = response.split("```json")[1].split("```")[0].trim();
                return MAPPER.readValue(planStr, new TypeReference<List<String>>() {});
            }
            // 尝试直接解析为 JSON 数组
            return MAPPER.readValue(response, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            System.out.println("❌ 解析计划时出错: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Map<String, String>> buildLLMMessages(String prompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", Message.ROLE_SYSTEM, "content", systemPrompt));
        }
        messages.add(Map.of("role", Message.ROLE_USER, "content", prompt));
        return messages;
    }
}
