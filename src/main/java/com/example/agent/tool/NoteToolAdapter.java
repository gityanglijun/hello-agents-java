package com.example.agent.tool;

import java.util.*;
import java.util.stream.Collectors;

/**
 * NoteTool 的 Tool 包装器 — 让 LLM 可以通过 function calling 调用笔记管理功能。
 *
 * 使用 action 参数调度: create / search / list / get / update / delete / stats
 */
public class NoteToolAdapter extends Tool {

    private final NoteTool noteTool;

    public NoteToolAdapter(NoteTool noteTool) {
        super("note",
              "笔记管理工具。支持创建、搜索、更新、删除、列出笔记。" +
              "笔记类型: blocker(阻塞问题), action(行动计划), " +
              "task_state(任务状态), conclusion(结论), knowledge(知识), general(通 用)");
        this.noteTool = noteTool;
    }

    @Override
    public String run(Map<String, Object> parameters) {
        String action = (String) parameters.getOrDefault("action", "search");
        return switch (action) {
            case "create"  -> handleCreate(parameters);
            case "search"  -> handleSearch(parameters);
            case "list"    -> handleList(parameters);
            case "get"     -> handleGet(parameters);
            case "update"  -> handleUpdate(parameters);
            case "delete"  -> handleDelete(parameters);
            case "stats"   -> handleStats();
            default -> "不支持的操作: " + action + "。可用: create, search, list, get, update, delete, stats";
        };
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("action", "string",
                "操作类型: create, search, list, get, update, delete, stats"),
            new ToolParameter("title", "string", "笔记标题", false, null),
            new ToolParameter("content", "string", "笔记内容 (Markdown 格式)", false, null),
            new ToolParameter("note_type", "string",
                "笔记类型: blocker, action, task_state, conclusion, knowledge, general",
                false, "general"),
            new ToolParameter("tags", "array", "标签列表", false, null),
            new ToolParameter("query", "string", "搜索关键词（搜索标题和正文）", false, null),
            new ToolParameter("note_id", "string", "笔记ID (get/update/delete 操作使用)", false, null),
            new ToolParameter("limit", "integer", "返回数量上限", false, 5)
        );
    }

    // ==================== handlers ====================

    @SuppressWarnings("unchecked")
    private String handleCreate(Map<String, Object> params) {
        String content = (String) params.getOrDefault("content", "");
        String noteType = (String) params.getOrDefault("note_type", "general");
        String title = (String) params.getOrDefault("title", "");
        Object tagsObj = params.get("tags");
        List<String> tags = new ArrayList<>();
        if (tagsObj instanceof List<?> list) {
            for (Object item : list) tags.add(item.toString());
        }

        if (content.isBlank()) {
            return "❌ 创建笔记需要 content 参数";
        }
        if (title.isBlank()) {
            // Auto-generate title from content
            title = content.length() > 50 ? content.substring(0, 50) + "..." : content;
            title = title.replace("\n", " ").replace("\r", "");
        }

        String id = noteTool.addNote(content, noteType, tags, title);
        return "✅ 笔记已创建: " + id + " [" + noteType + "] " + title;
    }

    private String handleSearch(Map<String, Object> params) {
        String query = (String) params.getOrDefault("query", "");
        String type = nullIfBlank((String) params.get("note_type"));
        int limit = params.containsKey("limit") ? ((Number) params.get("limit")).intValue() : 5;

        @SuppressWarnings("unchecked")
        List<String> tags = params.get("tags") instanceof List<?> list
                ? list.stream().map(Object::toString).collect(Collectors.toList())
                : null;

        List<NoteTool.Note> results;
        if (query.isBlank() && type == null && (tags == null || tags.isEmpty())) {
            results = noteTool.listNotes(null, null);
        } else {
            results = noteTool.searchNotes(query, type, tags);
        }

        if (results.isEmpty()) return "未找到匹配的笔记";

        StringBuilder sb = new StringBuilder("找到 " + results.size() + " 条笔记:\n");
        for (int i = 0; i < Math.min(results.size(), limit); i++) {
            NoteTool.Note note = results.get(i);
            sb.append("- [").append(note.type).append("] ").append(note.title)
              .append(" (id:").append(note.id).append(")\n");
        }
        return sb.toString();
    }

    private String handleList(Map<String, Object> params) {
        String type = nullIfBlank((String) params.get("note_type"));
        @SuppressWarnings("unchecked")
        List<String> tags = params.get("tags") instanceof List<?> list
                ? list.stream().map(Object::toString).collect(Collectors.toList())
                : null;
        int limit = params.containsKey("limit") ? ((Number) params.get("limit")).intValue() : 10;

        List<NoteTool.Note> results = noteTool.listNotes(type, tags);

        if (results.isEmpty()) return "暂无笔记";

        StringBuilder sb = new StringBuilder("共 " + results.size() + " 条笔记:\n");
        for (int i = 0; i < Math.min(results.size(), limit); i++) {
            NoteTool.Note note = results.get(i);
            sb.append("- [").append(note.type).append("] ").append(note.title)
              .append(" (id:").append(note.id).append(")\n");
        }
        return sb.toString();
    }

    private String handleGet(Map<String, Object> params) {
        String noteId = (String) params.get("note_id");
        if (noteId == null || noteId.isBlank()) {
            return "❌ get 操作需要 note_id 参数";
        }
        NoteTool.Note note = noteTool.getNote(noteId);
        if (note == null) return "❌ 笔记不存在: " + noteId;

        return "[" + note.type + "] " + note.title + "\n" +
               "标签: " + note.tags + "\n" +
               "更新: " + note.updatedAt + "\n" +
               "---\n" + note.content;
    }

    @SuppressWarnings("unchecked")
    private String handleUpdate(Map<String, Object> params) {
        String noteId = (String) params.get("note_id");
        if (noteId == null || noteId.isBlank()) {
            return "❌ update 操作需要 note_id 参数";
        }
        String content = (String) params.get("content");
        String title = (String) params.get("title");
        String type = nullIfBlank((String) params.get("note_type"));
        List<String> tags = params.get("tags") instanceof List<?> list
                ? list.stream().map(Object::toString).collect(Collectors.toList())
                : null;

        return noteTool.updateNote(noteId, content, title, tags, type);
    }

    private String handleDelete(Map<String, Object> params) {
        String noteId = (String) params.get("note_id");
        if (noteId == null || noteId.isBlank()) {
            return "❌ delete 操作需要 note_id 参数";
        }
        return noteTool.deleteNote(noteId);
    }

    private String handleStats() {
        int count = noteTool.count();
        Set<String> types = noteTool.listTypes();
        Set<String> tags = noteTool.listTags();
        return "笔记统计: 共 " + count + " 条\n" +
               "类型: " + types + "\n" +
               "标签: " + tags;
    }

    /** Expose underlying NoteTool for stats tracking. */
    public NoteTool getNoteTool() {
        return noteTool;
    }

    private static String nullIfBlank(String s) {
        return (s != null && !s.isBlank()) ? s : null;
    }
}
