package com.example.agent.pattern;

import com.example.agent.llm.MyLLM;
import com.example.agent.rag.ContextBuilder;
import com.example.agent.rag.ContextConfig;

/**
 * ContextAwareAgent 完整测试 —— 演示上下文感知流水线的实际效果。
 *
 * 运行：
 *   mvn compile exec:java "-Dexec.mainClass=com.example.agent.pattern.ContextAwareAgentTest" "-Dexec.classpathScope=compile" "-Dfile.encoding=UTF-8" -q
 */
public class ContextAwareAgentTest {

    public static void main(String[] args) {
        MyLLM llm = new MyLLM.Builder().build();

        // ==================== 测试1: 基础上下文构建 ====================
        System.out.println("=" .repeat(60));
        System.out.println("测试1: 基础上下文构建（无文档，纯记忆 + 对话历史）");
        System.out.println("=" .repeat(60));

        ContextAwareAgent agent = new ContextAwareAgent(
                "Pandas优化顾问",
                llm,
                "你是资深Python数据工程顾问。回答需: 1) 提供具体可行的建议 2) 解释技术原理 3) 给出代码示例",
                "user123",
                null
        );

        // 预置记忆
        agent.getMemoryTool().execute("add",
                "content", "用户正在开发数据分析工具，使用Python和Pandas",
                "memory_type", "semantic",
                "importance", 0.8
        );
        agent.getMemoryTool().execute("add",
                "content", "已完成CSV读取模块的开发，但遇到大文件内存不足问题",
                "memory_type", "episodic",
                "importance", 0.7
        );

        // 模拟对话历史
        agent.getConversationHistory().add(
                new com.example.agent.Message("我正在开发一个数据分析工具", "user"));
        agent.getConversationHistory().add(
                new com.example.agent.Message("很好！数据分析工具通常需要处理大量数据。您计划使用什么技术栈？", "assistant"));
        agent.getConversationHistory().add(
                new com.example.agent.Message("我打算使用Python和Pandas，已经完成了CSV读取模块", "user"));
        agent.getConversationHistory().add(
                new com.example.agent.Message("不错的选择！Pandas在数据处理方面非常强大。接下来您可能需要考虑数据清洗和转换。", "assistant"));

        // 直接调用 ContextBuilder 查看结构化上下文
        ContextBuilder builder = agent.getContextBuilder();
        String context = builder.build(
                "如何优化Pandas的内存占用？",
                agent.getConversationHistory(),
                "你是资深Python数据工程顾问。回答需: 1) 提供具体可行的建议 2) 解释技术原理 3) 给出代码示例",
                null
        );

        System.out.println("\n构建的上下文:");
        System.out.println("-".repeat(40));
        System.out.println(context);
        System.out.println("-".repeat(40));

        // ==================== 测试2: 带 RAG 文档的完整流水线 ====================
        System.out.println("\n" + "=" .repeat(60));
        System.out.println("测试2: 带文档的完整流水线（如果 models 目录有 PDF 则自动加载）");
        System.out.println("=" .repeat(60));

        ContextAwareAgent agent2 = new ContextAwareAgent(
                "文档学习助手",
                llm,
                "你是严谨的文档问答助手。只基于参考资料回答，不要编造或推测。",
                "user456",
                null
        );

        // 尝试自动加载测试文档
        String testPdf = "E:\\AIAgent\\hello-agents-java\\models\\Happy-LLM-0727.pdf";
        java.io.File pdfFile = new java.io.File(testPdf);
        if (pdfFile.exists()) {
            agent2.loadDocument(testPdf);
            System.out.println();
        } else {
            System.out.println("（跳过: 测试文档不存在）\n");
        }

        // ==================== 测试3: ContextConfig 参数验证 ====================
        System.out.println("=" .repeat(60));
        System.out.println("测试3: ContextConfig 参数验证");
        System.out.println("=" .repeat(60));

        ContextConfig defaultConfig = ContextConfig.defaults();
        System.out.println("默认配置: " + defaultConfig);
        System.out.println("有效 token 预算: " + defaultConfig.effectiveMaxTokens()
                + " (max=" + defaultConfig.maxTokens()
                + " × (1 - reserveRatio=" + defaultConfig.reserveRatio() + "))");

        // 自定义配置
        ContextConfig customConfig = ContextConfig.builder()
                .maxTokens(2000)
                .reserveRatio(0.15)
                .minRelevance(0.15)
                .recencyWeight(0.2)
                .relevanceWeight(0.8)
                .enableCompression(true)
                .build();
        System.out.println("自定义配置: " + customConfig);
        System.out.println("有效 token 预算: " + customConfig.effectiveMaxTokens());

        // 参数校验
        System.out.println("\n参数校验:");
        try {
            ContextConfig.builder().reserveRatio(1.5).build();
        } catch (IllegalArgumentException e) {
            System.out.println("  ✅ reserveRatio=1.5 → " + e.getMessage());
        }
        try {
            ContextConfig.builder().recencyWeight(0.5).relevanceWeight(0.3).build();
        } catch (IllegalArgumentException e) {
            System.out.println("  ✅ 权重之和≠1.0 → " + e.getMessage());
        }

        System.out.println("\n✅ 所有测试完成");
    }
}
