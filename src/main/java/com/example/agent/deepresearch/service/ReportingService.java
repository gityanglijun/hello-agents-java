package com.example.agent.deepresearch.service;

import com.example.agent.deepresearch.DeepResearchPrompts;
import com.example.agent.deepresearch.TextProcessingUtils;
import com.example.agent.deepresearch.model.DeepResearchModels.*;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.SimpleAgent;
import java.util.*;

/**
 * 报告生成服务，基于所有任务总结生成最终研究报告。
 * 对应 Python services/reporter.py。
 */
public class ReportingService {

    private final SimpleAgent reportAgent;
    private final HelloAgentsLLM llm;

    public ReportingService(HelloAgentsLLM llm, SimpleAgent reportAgent) {
        this.llm = llm;
        this.reportAgent = reportAgent;
    }

    /** 生成最终报告。 */
    public String generateReport(SummaryState state) {
        StringBuilder tasksBlock = new StringBuilder();
        for (TodoItem task : state.getTodoItems()) {
            tasksBlock.append("---\n");
            tasksBlock.append("任务 ID: ").append(task.getId()).append("\n");
            tasksBlock.append("标题: ").append(task.getTitle() != null ? task.getTitle() : "N/A").append("\n");
            tasksBlock.append("意图: ").append(task.getIntent() != null ? task.getIntent() : "N/A").append("\n");
            tasksBlock.append("查询: ").append(task.getQuery() != null ? task.getQuery() : "N/A").append("\n");
            tasksBlock.append("状态: ").append(task.getStatus() != null ? task.getStatus() : "pending").append("\n");
            tasksBlock.append("总结: ").append(task.getSummary() != null ? task.getSummary() : "暂无").append("\n");
            tasksBlock.append("来源概要: ").append(task.getSourcesSummary() != null ? task.getSourcesSummary() : "暂无").append("\n");
        }

        StringBuilder notesBlock = new StringBuilder();
        List<String> noteIds = new ArrayList<>();
        for (TodoItem task : state.getTodoItems()) {
            if (task.getNoteId() != null && !task.getNoteId().isBlank()) {
                noteIds.add(task.getNoteId());
                notesBlock.append("- 任务 ").append(task.getId()).append(" → note_id: ").append(task.getNoteId()).append("\n");
            }
        }

        String prompt = String.format("""
                <CONTEXT>
                研究主题: %s
                研究循环次数: %d
                </CONTEXT>

                <TASKS>
                %s
                </TASKS>

                <NOTES>
                %s
                </NOTES>

                %s

                请读取以上任务笔记（使用 [TOOL_CALL:note:{\"action\":\"read\",\"note_id\":\"<note_id>\"}] ），
                然后基于所有任务总结生成结构化的研究报告。
                """,
                state.getResearchTopic(),
                state.getResearchLoopCount(),
                tasksBlock.toString(),
                notesBlock.length() > 0 ? notesBlock.toString() : "无笔记",
                DeepResearchPrompts.REPORT_WRITER_SYSTEM
        );

        try {
            String rawResponse = reportAgent.run(prompt);
            reportAgent.clearHistory();

            String cleaned = TextProcessingUtils.stripThinkingTokens(rawResponse);
            cleaned = TextProcessingUtils.stripToolCalls(cleaned);

            if (cleaned != null && !cleaned.isBlank()) {
                return cleaned;
            }
            return "报告生成失败：无有效输出。";
        } catch (Exception e) {
            System.err.println("[ReportingService] 报告生成失败: " + e.getMessage());
            return "报告生成异常: " + e.getMessage();
        }
    }
}
