package com.example.agent.pattern;

import com.example.agent.Message;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.memory.MemoryTool;
import com.example.agent.rag.ContextBuilder;
import com.example.agent.rag.ContextConfig;
import com.example.agent.rag.RAGTool;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上下文感知 Agent — 集成 ContextBuilder 的完整智能体。
 *
 * 每次 run() 自动执行四阶段上下文构建流水线：
 *   gather → select → structure → compress
 *
 * 使用示例：
 * <pre>
 *   ContextAwareAgent agent = new ContextAwareAgent("助手", llm,
 *       "你是资深Python数据工程顾问。", "user123", "./kb");
 *   agent.loadDocument("pandas_guide.pdf");
 *   String answer = agent.run("如何优化Pandas内存占用？");
 * </pre>
 */
public class ContextAwareAgent extends SimpleAgent {

    private final MemoryTool memoryTool;
    private final RAGTool ragTool;
    private final ContextBuilder contextBuilder;
    private final List<Message> conversationHistory;
    private final String userId;

    public ContextAwareAgent(String name, HelloAgentsLLM llm, String systemPrompt,
                             String userId, String knowledgeBasePath) {
        super(name, llm, systemPrompt);
        this.userId = userId != null ? userId : "default";
        this.memoryTool = new MemoryTool();
        this.ragTool = new RAGTool();

        ContextConfig config = ContextConfig.builder()
                .maxTokens(4000)
                .reserveRatio(0.2)
                .minRelevance(0.2)
                .enableCompression(true)
                .build();

        this.contextBuilder = new ContextBuilder(memoryTool, ragTool, config);
        this.conversationHistory = new ArrayList<>();

        System.out.println("[ContextAwareAgent] " + name + " 初始化完成 (用户: " + this.userId + ")");
    }

    public ContextAwareAgent(String name, HelloAgentsLLM llm, String systemPrompt) {
        this(name, llm, systemPrompt, "default", null);
    }

    // ==================== 文档加载 ====================

    public void loadDocument(String filePath) {
        String result = ragTool.run(Map.of("action", "add_file", "file_path", filePath));
        System.out.println("[ContextAwareAgent] " + result);

        memoryTool.execute("add",
                "content", "加载了文档: " + filePath,
                "memory_type", "episodic",
                "importance", 0.9,
                "event_type", "document_loaded"
        );
    }

    // ==================== 核心：上下文感知运行 ====================

    @Override
    public String run(String userInput) {
        System.out.println("\n🤖 " + name + " 正在处理: " + userInput);

        // 1. 构建优化上下文（四阶段流水线）
        String optimizedContext = contextBuilder.build(
                userInput,
                conversationHistory,
                systemPrompt,
                null
        );

        // 2. LLM 调用：最优上下文作为 system prompt
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", optimizedContext),
                Map.of("role", "user", "content", userInput)
        );

        String response = llm.think(messages);
        if (response == null || response.isBlank()) {
            response = "（LLM 无响应）";
        }

        // 3. 更新对话历史
        LocalDateTime now = LocalDateTime.now();
        conversationHistory.add(new Message(userInput, Message.ROLE_USER, now, null));
        conversationHistory.add(new Message(response, Message.ROLE_ASSISTANT, now, null));

        // 同时更新 Agent 基类的历史
        addMessage(new Message(userInput, Message.ROLE_USER));
        addMessage(new Message(response, Message.ROLE_ASSISTANT));

        // 4. 将重要交互记录到记忆系统
        String summary = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        memoryTool.execute("add",
                "content", "Q: " + userInput + "\nA: " + summary,
                "memory_type", "episodic",
                "importance", 0.6,
                "event_type", "qa_interaction"
        );

        System.out.println("✅ " + name + " 响应完成");
        return response;
    }

    // ==================== 访问器 ====================

    public MemoryTool getMemoryTool() { return memoryTool; }
    public RAGTool getRagTool() { return ragTool; }
    public ContextBuilder getContextBuilder() { return contextBuilder; }
    public List<Message> getConversationHistory() { return conversationHistory; }

    @Override
    public void clearHistory() {
        super.clearHistory();
        conversationHistory.clear();
    }
}
