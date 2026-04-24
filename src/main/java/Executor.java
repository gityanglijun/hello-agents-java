import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Executor {

    // --- 3. 执行器 (Executor) 定义 ---
    public static String EXECUTOR_PROMPT_TEMPLATE = """
你是一位顶级的AI执行专家。你的任务是严格按照给定的计划，一步步地解决问题。
你将收到原始问题、完整的计划、以及到目前为止已经完成的步骤和结果。
请你专注于解决“当前步骤”，并仅输出该步骤的最终答案，不要输出任何额外的解释或对话。

# 原始问题:
%s

# 完整计划:
%s
   
# 历史步骤与结果:
%s

# 当前步骤:
%s

请仅输出针对“当前步骤”的回答:
""";
    public HelloAgentsLLM llmClient;
    public Executor(HelloAgentsLLM llmClient){
        this.llmClient = llmClient;
    }

    public String execute(String question, List<String> plan){
        String history = "";
        String finalAnswer = "";

        System.out.println("\n--- 正在执行计划 ---");
        for (int i = 0; i < plan.size(); i++) {
            int stepNumber = i + 1;   // 对应 Python 的 i，从1开始
            String step = plan.get(i); // 对应 Python 的 step
            // 使用 stepNumber 和 step
            System.out.printf("正在执行步骤 %d: %s%n", stepNumber, step);

            String prompt = EXECUTOR_PROMPT_TEMPLATE.formatted(question,plan,history,step);

            List<Map<String,String>> messages = new ArrayList<>();

            Map<String,String> message = new HashMap<>();
            message.put("role","user");
            message.put("content",prompt);
            messages.add(message);

            String resPonseText = llmClient.think(messages);
            resPonseText = resPonseText == null ? "":resPonseText;

            history += String.format("步骤 %d: %s \n结果: %s\n\n",stepNumber,step,resPonseText);
            finalAnswer = resPonseText;
            System.out.printf("✅ 步骤 %d 已完成，结果: %s",stepNumber,finalAnswer);

        }
        return finalAnswer;
    }
}
