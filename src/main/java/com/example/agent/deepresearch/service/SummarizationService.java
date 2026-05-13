package com.example.agent.deepresearch.service;

import com.example.agent.deepresearch.DeepResearchConfig;
import com.example.agent.deepresearch.DeepResearchPrompts;
import com.example.agent.deepresearch.TextProcessingUtils;
import com.example.agent.deepresearch.model.DeepResearchModels.*;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.SimpleAgent;
import java.util.*;
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
