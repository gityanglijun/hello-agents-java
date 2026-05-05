package com.example.agent.pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.agent.Agent;
import com.example.agent.Config;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.Message;
import com.example.agent.tool.ToolRegistry;

public class ReActAgent extends Agent {

    private static final String DEFAULT_REACT_PROMPT = """
            请注意，你是一个有能力调用外部工具的智能助手。

            可用工具如下：
            %s

            请严格按照以下格式进行回应：

            Thought: 你的思考过程，用于分析问题、拆解任务和规划下一步行动。
            Action: 你决定采取的行动，必须是以下格式之一：
            - `{tool_name}[{tool_input}]`：调用一个可用工具。
            - `Finish[最终答案]`：当你认为已经获得最终答案时。

            现在，请开始解决以下问题：
            Question: %s
            History: %s
            """;

    protected final ToolRegistry toolRegistry;
    protected final int maxSteps;
    protected final String promptTemplate;
    protected final List<String> currentHistory;

    public ReActAgent(
            String name,
            HelloAgentsLLM llm,
            ToolRegistry toolRegistry,
            String systemPrompt,
            Config config,
            int maxSteps,
            String customPrompt
    ) {
        super(name, llm, systemPrompt, config);
        this.toolRegistry = toolRegistry;
        this.maxSteps = maxSteps;
        this.promptTemplate = customPrompt != null ? customPrompt : DEFAULT_REACT_PROMPT;
        this.currentHistory = new ArrayList<>();
    }

    public ReActAgent(
            String name,
            HelloAgentsLLM llm,
            ToolRegistry toolRegistry,
            String systemPrompt,
            int maxSteps
    ) {
        this(name, llm, toolRegistry, systemPrompt, null, maxSteps, null);
    }

    @Override
    public String run(String inputText) {
        currentHistory.clear();
        String finalAnswer = null;
        int step = 0;

        while (step < maxSteps) {
            step++;
            System.out.println("\n--- 第 {" + step + "} 步 ---");

            String prompt = buildReActPrompt(inputText);
            String response = llm.thinkMessages(buildMessages(prompt));

            if (response == null || response.isBlank()) {
                System.out.println("错误：LLM未能返回有效响应。");
                break;
            }

            String[] thoughtAndAction = parseThoughtAction(response);
            String thought = thoughtAndAction[0];
            String action = thoughtAndAction[1];

            if (thought != null) {
                System.out.println("🤔 思考: " + thought);
            }
            if (action == null) {
                System.out.println("警告：未能解析出有效的Action，流程终止。");
                break;
            }

            if (action.startsWith("Finish")) {
                finalAnswer = parseActionInput(action);
                System.out.println("🎉 最终答案: " + finalAnswer);
                break;
            }

            String[] toolCall = parseAction(action);
            String toolName = toolCall[0];
            String toolInput = toolCall[1];

            if (toolName == null || toolInput == null) {
                currentHistory.add("Observation: 无效的Action格式，请检查。");
                continue;
            }

            String observation = executeTool(toolName, toolInput);
            currentHistory.add("Action: " + action);
            currentHistory.add("Observation: " + observation);
        }

        if (finalAnswer == null) {
            System.out.println("已达到最大步数，流程终止。");
        }

        addMessage(new Message(inputText, Message.ROLE_USER));
        if (finalAnswer != null) {
            addMessage(new Message(finalAnswer, Message.ROLE_ASSISTANT));
        }

        return finalAnswer;
    }

    // ========== 可由子类重写的方法 ==========

    protected String buildReActPrompt(String question) {
        String toolsDesc = toolRegistry.describeTools();
        String historyStr = String.join("\n", currentHistory);
        return String.format(promptTemplate, toolsDesc, question, historyStr);
    }

    protected List<Message> buildMessages(String prompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new Message(systemPrompt, Message.ROLE_SYSTEM));
        }
        messages.add(new Message(prompt, Message.ROLE_USER));
        return messages;
    }

    protected String executeTool(String toolName, String toolInput) {
        System.out.println("🎬 行动: " + toolName + "[" + toolInput + "]");
        if (toolRegistry.has(toolName)) {
            try {
                return toolRegistry.executeTool(toolName, toolInput);
            } catch (Exception e) {
                return "❌ 工具调用失败:" + e.getMessage();
            }
        }
        return "错误：未找到名为 '" + toolName + "' 的工具。";
    }

    // ========== 解析方法 ==========

    protected String[] parseThoughtAction(String text) {
        Pattern thoughtPattern = Pattern.compile("Thought:\\s*(.*?)(?=\nAction:|$)", Pattern.DOTALL);
        Matcher thoughtMatcher = thoughtPattern.matcher(text);
        String thought = thoughtMatcher.find() ? thoughtMatcher.group(1).trim() : null;

        Pattern actionPattern = Pattern.compile("Action:\\s*(.*?)$", Pattern.DOTALL);
        Matcher actionMatcher = actionPattern.matcher(text);
        String action = actionMatcher.find() ? actionMatcher.group(1).trim() : null;

        return new String[]{thought, action};
    }

    protected String[] parseAction(String actionText) {
        Pattern pattern = Pattern.compile("(\\w+)\\[(.*)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(actionText);
        if (matcher.matches()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return new String[]{null, null};
    }

    protected String parseActionInput(String actionText) {
        Pattern pattern = Pattern.compile("\\w+\\[(.*)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(actionText);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }
}
