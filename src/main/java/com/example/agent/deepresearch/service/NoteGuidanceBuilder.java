package com.example.agent.deepresearch.service;

import com.example.agent.deepresearch.model.DeepResearchModels.TodoItem;
import com.google.gson.Gson;
import java.util.*;

/**
 * 笔记引导构建器，为 summarizer agent 生成带解释的 note 工具调用指引。
 * 对齐 Python services/notes.py build_note_guidance()。
 */
public final class NoteGuidanceBuilder {

    private static final Gson GSON = new Gson();

    /**
     * 根据任务是否已有 note_id，生成读取/更新 或 创建 note 的指引文本。
     * 对齐 Python：输出人类可读的协作指引 + [TOOL_CALL:...] 指令。
     */
    public static String buildNoteGuidance(TodoItem task) {
        List<String> tags = List.of("deep_research", "task_" + task.getId());
        String tagsLiteral = GSON.toJson(tags);
        String taskTitle = "任务 " + task.getId() + ": " + (task.getTitle() != null ? task.getTitle() : "调研任务");

        if (task.getNoteId() != null && !task.getNoteId().isBlank()) {
            // 已有笔记 → 先读取再更新
            Map<String, Object> readPayload = new LinkedHashMap<>();
            readPayload.put("action", "read");
            readPayload.put("note_id", task.getNoteId());
            String readJson = GSON.toJson(readPayload);

            Map<String, Object> updatePayload = new LinkedHashMap<>();
            updatePayload.put("action", "update");
            updatePayload.put("note_id", task.getNoteId());
            updatePayload.put("task_id", task.getId());
            updatePayload.put("title", taskTitle);
            updatePayload.put("note_type", "task_state");
            updatePayload.put("tags", tags);
            updatePayload.put("content", "请将本轮新增信息补充到任务概览中");
            String updateJson = GSON.toJson(updatePayload);

            return "笔记协作指引：\n"
                    + "- 当前任务笔记 ID：" + task.getNoteId() + "。\n"
                    + "- 在书写总结前必须调用：[TOOL_CALL:note:" + readJson + "] 获取最新内容。\n"
                    + "- 完成分析后调用：[TOOL_CALL:note:" + updateJson + "] 同步增量信息。\n"
                    + "- 更新时保持原有段落结构，新增内容请在对应段落中补充。\n"
                    + "- 建议 tags 保持为 " + tagsLiteral + "，保证其他 Agent 可快速定位。\n"
                    + "- 成功同步到笔记后，再输出面向用户的总结。\n";
        } else {
            // 无笔记 → 创建新笔记
            Map<String, Object> createPayload = new LinkedHashMap<>();
            createPayload.put("action", "create");
            createPayload.put("task_id", task.getId());
            createPayload.put("title", taskTitle);
            createPayload.put("note_type", "task_state");
            createPayload.put("tags", tags);
            createPayload.put("content", "请记录任务概览、来源概览");
            String createJson = GSON.toJson(createPayload);

            return "笔记协作指引：\n"
                    + "- 当前任务尚未建立笔记，请先调用：[TOOL_CALL:note:" + createJson + "]。\n"
                    + "- 创建成功后记录返回的 note_id，并在后续所有更新中复用。\n"
                    + "- 同步笔记后，再输出面向用户的总结。\n";
        }
    }
}
