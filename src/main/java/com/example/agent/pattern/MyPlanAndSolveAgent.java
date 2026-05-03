package com.example.agent.pattern;

import com.example.agent.Config;
import com.example.agent.llm.HelloAgentsLLM;

public class MyPlanAndSolveAgent extends PlanAndSolveAgent {

    public MyPlanAndSolveAgent(
            String name,
            HelloAgentsLLM llm,
            String systemPrompt,
            Config config,
            String customPlannerPrompt,
            String customExecutorPrompt
    ) {
        super(name, llm, systemPrompt, config, customPlannerPrompt, customExecutorPrompt);
    }

    public MyPlanAndSolveAgent(String name, HelloAgentsLLM llm) {
        super(name, llm);
    }
}
