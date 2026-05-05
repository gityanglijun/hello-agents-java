package com.example.agent.pattern;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.agent.Agent;
import com.example.agent.Config;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.Message;

public class ReflectionAgent extends Agent {

    private static final Map<String, String> DEFAULT_PROMPTS = new HashMap<>();
    private static final String NO_IMPROVEMENT = "无需改进";
    private static final int MAX_REFINE_ITERATIONS = 3;

    static {
        DEFAULT_PROMPTS.put("initial", """
请根据以下要求完成任务:

任务: {task}

请提供一个完整、准确的回答。
""");
        DEFAULT_PROMPTS.put("reflect", """
请仔细审查以下回答，并找出可能的问题或改进空间:

# 原始任务:
{task}

# 当前回答:
{content}

请分析这个回答的质量，指出不足之处，并提出具体的改进建议。
如果回答已经很好，请回答"无需改进"。
""");
        DEFAULT_PROMPTS.put("refine", """
请根据反馈意见改进你的回答:

# 原始任务:
{task}

# 上一轮回答:
{lastAttempt}

# 反馈意见:
{feedback}

请提供一个改进后的回答。
""");
    }

    protected final Map<String, String> prompts;

    public ReflectionAgent(
            String name,
            HelloAgentsLLM llm,
            String systemPrompt,
            Config config,
            Map<String, String> customPrompts
    ) {
        super(name, llm, systemPrompt, config);
        this.prompts = customPrompts != null ? customPrompts : new HashMap<>(DEFAULT_PROMPTS);
    }

    public ReflectionAgent(String name, HelloAgentsLLM llm) {
        this(name, llm, null, null, null);
    }

    @Override
    public String run(String inputText) {
        System.out.println("🤖 " + name + " 开始反思流程: " + inputText);

        String currentAnswer = llm.thinkMessages(buildMessages(
                buildPrompt("initial", Map.of("task", inputText))
        ));
        System.out.println("📝 初始回答生成完成");
        if (currentAnswer == null) return null;

        for (int i = 0; i < MAX_REFINE_ITERATIONS; i++) {
            System.out.println("🔄 反思迭代 " + (i + 1) + "/" + MAX_REFINE_ITERATIONS);

            String feedback = llm.thinkMessages(buildMessages(
                    buildPrompt("reflect", Map.of("task", inputText, "content", currentAnswer))
            ));

            if (feedback == null || feedback.contains(NO_IMPROVEMENT)) {
                System.out.println("✅ 回答已无需改进");
                break;
            }

            String improved = llm.thinkMessages(buildMessages(
                    buildPrompt("refine", Map.of("task", inputText, "lastAttempt", currentAnswer, "feedback", feedback))
            ));
            if (improved == null) break;
            currentAnswer = improved;
            System.out.println("📝 回答已改进");
        }

        addMessage(new Message(inputText, Message.ROLE_USER));
        addMessage(new Message(currentAnswer, Message.ROLE_ASSISTANT));
        System.out.println("✅ " + name + " 反思流程完成");
        return currentAnswer;
    }

    // ========== 可由子类重写 ==========

    protected String buildPrompt(String stage, Map<String, String> variables) {
        String template = prompts.get(stage);
        if (template == null) {
            throw new IllegalArgumentException("未知的提示词阶段: " + stage);
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result.trim();
    }

    protected List<Message> buildMessages(String prompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new Message(systemPrompt, Message.ROLE_SYSTEM));
        }
        messages.add(new Message(prompt, Message.ROLE_USER));
        return messages;
    }
}
