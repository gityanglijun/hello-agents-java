package com.example.agent.deepresearch.service;

import com.example.agent.deepresearch.DeepResearchConfig;
import com.example.agent.deepresearch.DeepResearchPrompts;
import com.example.agent.deepresearch.TextProcessingUtils;
import com.example.agent.deepresearch.model.DeepResearchModels.*;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.SimpleAgent;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 总结服务，为单个任务生成要点总结。
 * 对应 Python services/summarizer.py。
 */
public class SummarizationService {

    private final Supplier<SimpleAgent> summarizerFactory;
    private final DeepResearchConfig config;

    public SummarizationService(Supplier<SimpleAgent> summarizerFactory, DeepResearchConfig config) {
        this.summarizerFactory = summarizerFactory;
        this.config = config;
    }

    /** 同步执行任务总结。 */
    public String summarizeTask(SummaryState state, TodoItem task, String context) {
        String prompt = buildPrompt(state, task, context);
        SimpleAgent agent = summarizerFactory.get();

        try {
            String rawResponse = agent.run(prompt);
            agent.clearHistory();

            String cleaned = TextProcessingUtils.stripThinkingTokens(rawResponse);
            cleaned = TextProcessingUtils.stripToolCalls(cleaned);
            return cleaned;
        } catch (Exception e) {
            System.err.println("[SummarizationService] 总结失败 (task " + task.getId() + "): " + e.getMessage());
            return "任务总结生成失败: " + e.getMessage();
        }
    }

    /**
     * 流式执行任务总结，通过 callback 逐块输出，返回完整文本。
     * 对齐 Python SummarizationService.stream_task_summary()。
     *
     * 使用增量清洗策略：每收到一个 chunk 就清洗全量累积文本，
     * 只将新增的干净内容推送到前端，避免 [TOOL_CALL:...] 和 &lt;think&gt; 标签泄露。
     */
    public String streamTaskSummary(SummaryState state, TodoItem task, String context,
                                     Consumer<String> chunkCallback) {
        String prompt = buildPrompt(state, task, context);
        SimpleAgent agent = summarizerFactory.get();

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", agent.getEnhancedSystemPrompt()));
        messages.add(Map.of("role", "user", "content", prompt));

        StringBuilder fullText = new StringBuilder();
        int[] sentLen = {0};

        try {
            agent.getLlm().streamThink(messages).forEach(chunk -> {
                fullText.append(chunk);

                String cleaned = TextProcessingUtils.stripThinkingTokens(fullText.toString());
                cleaned = TextProcessingUtils.stripToolCalls(cleaned);

                if (cleaned.length() > sentLen[0]) {
                    String delta = cleaned.substring(sentLen[0]);
                    chunkCallback.accept(delta);
                    sentLen[0] = cleaned.length();
                }
            });
        } catch (Exception e) {
            System.err.println("[SummarizationService] 流式总结失败 (task " + task.getId() + "): " + e.getMessage());
            String fallback = "任务总结生成失败: " + e.getMessage();
            chunkCallback.accept(fallback);
            return fallback;
        } finally {
            agent.clearHistory();
        }

        // 最终清洗（与增量清洗一致）
        String cleaned = TextProcessingUtils.stripThinkingTokens(fullText.toString());
        cleaned = TextProcessingUtils.stripToolCalls(cleaned);
        return cleaned;
    }

    // ==================== 内部 ====================

    private String buildPrompt(SummaryState state, TodoItem task, String context) {
        String noteGuidance = NoteGuidanceBuilder.buildNoteGuidance(task);

        return String.format("""
                <CONTEXT>
                研究主题: %s
                当前任务: %s
                任务意图: %s
                检索查询: %s
                </CONTEXT>

                <SEARCH_RESULTS>
                %s
                </SEARCH_RESULTS>

                %s

                %s
                """,
                state.getResearchTopic(),
                task.getTitle() != null ? task.getTitle() : "任务 " + task.getId(),
                task.getIntent() != null ? task.getIntent() : "",
                task.getQuery() != null ? task.getQuery() : "",
                context != null ? context : "无检索结果",
                DeepResearchPrompts.TASK_SUMMARIZER_SYSTEM,
                noteGuidance
        );
    }
}
