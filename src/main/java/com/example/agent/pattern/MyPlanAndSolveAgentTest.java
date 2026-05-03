package com.example.agent.pattern;

import com.example.agent.llm.MyLLM;

public class MyPlanAndSolveAgentTest {

    public static void main(String[] args) {
        MyLLM llm = new MyLLM.Builder().build();

        // ==================== 测试1: 数学多步推理 ====================
        System.out.println("=== 测试1: 数学多步推理 ===");
        MyPlanAndSolveAgent agent = new MyPlanAndSolveAgent("规划助手", llm);
        String result = agent.run("一个水果店周一卖出了15个苹果。周二卖出的苹果数量是周一的两倍。周三卖出的数量比周二少了5个。请问这三天总共卖出了多少个苹果？");
        System.out.println("规划执行结果: " + result + "\n");

        // ==================== 测试2: 代码生成任务 ====================
        System.out.println("=== 测试2: 代码生成任务 ===");
        MyPlanAndSolveAgent codeAgent = new MyPlanAndSolveAgent("代码规划助手", llm);
        String code = codeAgent.run("请编写一个Java程序，实现冒泡排序算法。");
        System.out.println("代码生成结果: " + code + "\n");

        // ==================== 测试3: 查看对话历史 ====================
        System.out.println("=== 测试3: 对话历史 ===");
        System.out.println("规划助手历史: " + agent.getHistory().size() + " 条消息");
        System.out.println("代码助手历史: " + codeAgent.getHistory().size() + " 条消息");
    }
}
