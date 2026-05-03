package com.example.agent;
import java.util.ArrayList;
import java.util.List;

import com.example.agent.llm.HelloAgentsLLM;

public abstract class Agent {

    protected final String name;
    protected final HelloAgentsLLM llm;
    protected final String systemPrompt;
    protected final Config config;
    protected final List<Message> history;

    public Agent(String name, HelloAgentsLLM llm, String systemPrompt, Config config) {
        this.name = name;
        this.llm = llm;
        this.systemPrompt = systemPrompt;
        this.config = config != null ? config : new Config.Builder().build();
        this.history = new ArrayList<>();
    }

    public Agent(String name, HelloAgentsLLM llm) {
        this(name, llm, null, null);
    }

    public Agent(String name, HelloAgentsLLM llm, String systemPrompt) {
        this(name, llm, systemPrompt, null);
    }

    public abstract String run(String inputText);

    public void addMessage(Message message) {
        history.add(message);
    }

    public void clearHistory() {
        history.clear();
    }

    public List<Message> getHistory() {
        return new ArrayList<>(history);
    }

    @Override
    public String toString() {
        return "Agent(name=" + name + ", provider=" + llm.provider + ")";
    }
}
