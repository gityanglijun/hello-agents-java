package com.example.agent.deepresearch.service;

import com.example.agent.deepresearch.model.DeepResearchModels.TodoItem;
import com.google.gson.Gson;
import java.util.*;

/**
 * 笔记引导构建器，为 summarizer agent 生成 note 工具调用指令。
 * 对应 Python services/notes.py build_note_guidance()。
 */
public final class NoteGuidanceBuilder {

    private static final Gson GSON = new Gson();

    /**
     * 根据任务是否已有 note_id，生成读取/更新 或 创建 note 的 [TOOL_CALL:note:...] 指令。
     */
    public static String buildNoteGuidance(TodoItem task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<String> tags = new ArrayList<>();
        tags.add("deep_research");
        tags.add("task_" + task.getId());

        if (task.getNoteId() != null && !task.getNoteId().isBlank()) {
            // 已有笔记 → 先读取再更新
            Map<String, Object> readPayload = new LinkedHashMap<>();
            readPayload.put("action", "read");
            readPayload.put("note_id", task.getNoteId());
            String readJson = GSON.toJson(readPayload);

            payload.put("action", "update");
            payload.put("note_id", task.getNoteId());
            payload.put("task_id", task.getId());
            payload.put("title", task.getTitle() != null ? task.getTitle() : "任务 " + task.getId());
            payload.put("note_type", "task_state");
            payload.put("tags", tags);
            payload.put("content", "请基于检索结果，追加任务总结、关键发现和来源评价。");
            String updateJson = GSON.toJson(payload);

            return "[TOOL_CALL:note:" + readJson + "]\n" +
                   "[TOOL_CALL:note:" + updateJson + "]";
        } else {
            // 无笔记 → 创建新笔记
            payload.put("action", "create");
            payload.put("task_id", task.getId());
            payload.put("title", "任务 " + task.getId() + ": " + (task.getTitle() != null ? task.getTitle() : "调研任务"));
            payload.put("note_type", "task_state");
            payload.put("tags", tags);
            payload.put("content", "请记录任务概览、系统提示、来源概览、任务总结");

            return "[TOOL_CALL:note:" + GSON.toJson(payload) + "]";
        }
    }
}
