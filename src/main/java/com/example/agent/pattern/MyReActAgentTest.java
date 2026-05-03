package com.example.agent.pattern;
import java.lang.reflect.Method;

import com.example.agent.tool.CalculatorTool;
import com.example.agent.llm.MyLLM;
import com.example.agent.tool.ToolRegistry;

public class MyReActAgentTest {

    public static void main(String[] args) throws Exception {
        MyLLM llm = new MyLLM.Builder().build();

        // ==================== 测试1: 基础 ReAct 对话 ====================
        System.out.println("=== 测试1: 基础 ReAct 对话 ===");
        ToolRegistry registry = new ToolRegistry();
        Method calcMethod = CalculatorTool.class.getMethod("calculate", String.class);
        registry.register("calculator", "计算数学表达式，例如: 15 * 8 + 32", calcMethod);

        MyReActAgent agent = new MyReActAgent(
                "推理助手",
                llm,
                registry,
                "你是一个有用的助手，可以使用工具来回答问题。",
                null,
                5,
                null
        );
        String result = agent.run("请帮我计算 123 * 456 的结果是多少？");
        System.out.println("ReAct 响应: " + result + "\n");

        // ==================== 测试2: 自定义提示词 ====================
        System.out.println("=== 测试2: 自定义提示词 ===");
        String customPrompt = """
                你是一个数学专家。你有以下工具可用:
                %s

                请一步一步思考，并使用工具进行计算。

                问题: %s
                历史: %s
                """;

        MyReActAgent customAgent = new MyReActAgent(
                "数学助手",
                llm,
                registry,
                "你是数学专家。",
                null,
                3,
                customPrompt
        );
        String result2 = customAgent.run("计算 (25 + 37) * 4 的结果");
        System.out.println("自定义 ReAct 响应: " + result2 + "\n");
    }
}
