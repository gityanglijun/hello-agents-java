package com.example.agent.pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.agent.Agent;
import com.example.agent.Config;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.Message;

public class SimpleAgent extends Agent {

    public SimpleAgent(String name, HelloAgentsLLM llm, String systemPrompt, Config config) {
        super(name, llm, systemPrompt, config);
    }

    public SimpleAgent(String name, HelloAgentsLLM llm, String systemPrompt) {
        super(name, llm, systemPrompt, null);
    }

    public SimpleAgent(String name, HelloAgentsLLM llm) {
        super(name, llm, null, null);
    }

    @Override
    public String run(String inputText) {
        return run(inputText, 3);
    }

    public String run(String inputText, int maxToolIterations) {
        System.out.println("🤖 " + name + " 正在处理: " + inputText);

        List<Message> messages = buildMessages(inputText);
        String response = llm.thinkMessages(messages);
        if (response != null) {
            addMessage(new Message(inputText, Message.ROLE_USER));
            addMessage(new Message(response, Message.ROLE_ASSISTANT));
        }

        System.out.println("✅ " + name + " 响应完成");
        return response;
    }

    protected String getEnhancedSystemPrompt() {
        return systemPrompt != null ? systemPrompt : "你是一个有用的AI助手。";
    }

    protected List<Message> buildMessages(String inputText) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(getEnhancedSystemPrompt(), Message.ROLE_SYSTEM));

        messages.addAll(history);

        messages.add(new Message(inputText, Message.ROLE_USER));
        return messages;
    }
}
