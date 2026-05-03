package com.example.agent.pattern;

import com.example.agent.Config;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.tool.ToolRegistry;

public class MyReActAgent extends ReActAgent {

    public MyReActAgent(
            String name,
            HelloAgentsLLM llm,
            ToolRegistry toolRegistry,
            String systemPrompt,
            Config config,
            int maxSteps,
            String customPrompt
    ) {
        super(name, llm, toolRegistry, systemPrompt, config, maxSteps, customPrompt);
        System.out.println("✅ " + name + " 初始化完成，最大步数: " + maxSteps);
    }

    public MyReActAgent(
            String name,
            HelloAgentsLLM llm,
            ToolRegistry toolRegistry,
            String systemPrompt,
            int maxSteps
    ) {
        this(name, llm, toolRegistry, systemPrompt, null, maxSteps, null);
    }
}
