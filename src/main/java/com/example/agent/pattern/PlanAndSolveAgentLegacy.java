package com.example.agent.pattern;
import java.util.List;

import com.example.agent.llm.HelloAgentsLLM;

public class PlanAndSolveAgentLegacy {
    public HelloAgentsLLM llmClient;
    public Planner planner;
    public Executor executor;

    public PlanAndSolveAgentLegacy(HelloAgentsLLM llmClient){
        this.llmClient = llmClient;
        this.planner = new Planner(llmClient);
        this.executor = new Executor(llmClient);
    }

    public void run(String question){
        System.out.println("\n--- 开始处理问题 ---\n问题: "+question+"");
        List<String> plan = planner.plan(question);
        if(plan == null){
            System.out.println("\\n--- 任务终止 --- \\n无法生成有效的行动计划。");
            return ;
        }
        String finalAnswer = executor.execute(question,plan);
        System.out.println("\n--- 任务完成 ---\n最终答案: "+finalAnswer+"");
    }

    public static void main(String[] args){
        try{
            HelloAgentsLLM llmClient = new HelloAgentsLLM();
            PlanAndSolveAgentLegacy agent = new PlanAndSolveAgentLegacy(llmClient);
            String question = "一个水果店周一卖出了15个苹果。周二卖出的苹果数量是周一的两倍。周三卖出的数量比周二少了5个。请问这三天总共卖出了多少个苹果？";
            agent.run(question);
        }
        catch (Exception e){
            throw e;
        }
    }
}
