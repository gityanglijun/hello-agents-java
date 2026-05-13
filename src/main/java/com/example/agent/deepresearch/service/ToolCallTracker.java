package com.example.agent.deepresearch.service;

import com.example.agent.deepresearch.model.DeepResearchModels.TodoItem;
import com.example.agent.tool.ToolRegistry;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具调用事件追踪器，收集工具调用事件并转为 SSE 负载。
 * 对应 Python services/tool_events.py。
 */
public class ToolCallTracker {

    private static final Gson GSON = new Gson();

    private final List<ToolCallEvent> events = new ArrayList<>();
    private int cursor = 0;
    private final ReentrantLock lock = new ReentrantLock();
    private Consumer<Map<String, Object>> eventSink;

    // ==================== Event ====================

    public static class ToolCallEvent {
        public String id;
        public String agent;
        public String tool;
        public String rawParameters;
        public Map<String, Object> parsedParameters;
        public String result;
        public Integer taskId;
        public String noteId;

        public Map<String, Object> asDict() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", id);
            d.put("agent", agent);
            d.put("tool", tool);
            d.put("raw_parameters", rawParameters);
            d.put("parsed_parameters", parsedParameters);
            d.put("result", result);
            if (taskId != null) d.put("task_id", taskId);
            if (noteId != null) d.put("note_id", noteId);
            return d;
        }
    }

    // ==================== Record ====================

    /** 记录工具调用事件。payload 包含 agent, tool, parameters, result 等字段。 */
    public void record(Map<String, Object> payload) {
        ToolCallEvent event = new ToolCallEvent();
        event.id = UUID.randomUUID().toString().substring(0, 8);
        event.agent = (String) payload.getOrDefault("agent", "unknown");
        event.tool = (String) payload.getOrDefault("tool", "unknown");
        event.rawParameters = (String) payload.getOrDefault("raw_parameters", "");
        event.result = (String) payload.getOrDefault("result", "");

        // 解析参数
        Object params = payload.get("parsed_parameters");
        if (params instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pm = (Map<String, Object>) params;
            event.parsedParameters = pm;
            event.taskId = inferTaskId(pm);
        } else if (event.rawParameters != null && !event.rawParameters.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> pm = GSON.fromJson(event.rawParameters, Map.class);
                event.parsedParameters = pm;
                event.taskId = inferTaskId(pm);
            } catch (Exception ignored) {}
        }

        // 推断 note_id（note 工具）
        if ("note".equals(event.tool)) {
            event.noteId = extractNoteId(event.result);
        }

        lock.lock();
        try {
            events.add(event);
        } finally {
            lock.unlock();
        }

        // 立即推送到 sink
        if (eventSink != null) {
            eventSink.accept(event.asDict());
        }
    }

    /** 清空上次 drain 之后的新事件，返回 SSE 事件列表。 */
    public List<Map<String, Object>> drain(List<TodoItem> todoItems) {
        lock.lock();
        try {
            List<ToolCallEvent> newEvents = new ArrayList<>();
            if (cursor < events.size()) {
                newEvents.addAll(events.subList(cursor, events.size()));
                cursor = events.size();
            }

            // 将 note_id 自动附加到匹配的 TodoItem
            for (ToolCallEvent e : newEvents) {
                if (e.noteId != null && e.taskId != null) {
                    attachNoteToTask(todoItems, e.taskId, e.noteId);
                }
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (ToolCallEvent e : newEvents) {
                results.add(e.asDict());
            }
            return results;
        } finally {
            lock.unlock();
        }
    }

    /** 重置所有事件和游标。 */
    public void reset() {
        lock.lock();
        try {
            events.clear();
            cursor = 0;
        } finally {
            lock.unlock();
        }
    }

    /** 获取所有事件的 dict 列表。 */
    public List<Map<String, Object>> asDicts() {
        lock.lock();
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ToolCallEvent e : events) {
                result.add(e.asDict());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** 设置事件即时回调。 */
    public void setEventSink(Consumer<Map<String, Object>> sink) {
        this.eventSink = sink;
    }

    // ==================== 内部 ====================

    private static void attachNoteToTask(List<TodoItem> tasks, int taskId, String noteId) {
        for (TodoItem t : tasks) {
            if (t.getId() == taskId) {
                t.setNoteId(noteId);
                t.setNotePath("notes/note_" + noteId + ".md");
                return;
            }
        }
    }

    /** 从参数中推断 task_id：直接字段 > tags 中的 task_N > title 中的 "任务 N"。 */
    private static Integer inferTaskId(Map<String, Object> params) {
        if (params == null) return null;

        Object tid = params.get("task_id");
        if (tid instanceof Number) return ((Number) tid).intValue();
        if (tid instanceof String) {
            try { return Integer.parseInt((String) tid); } catch (NumberFormatException ignored) {}
        }

        // tags 中的 task_N
        Object tags = params.get("tags");
        if (tags instanceof List) {
            for (Object tag : (List<?>) tags) {
                String s = String.valueOf(tag);
                if (s.startsWith("task_")) {
                    try { return Integer.parseInt(s.substring(5)); } catch (NumberFormatException ignored) {}
                }
            }
        }

        // title 中的 "任务 N"
        Object title = params.get("title");
        if (title instanceof String) {
            Matcher m = Pattern.compile("任务\\s*(\\d+)").matcher((String) title);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }

        return null;
    }

    private static String extractNoteId(String response) {
        if (response == null || response.isBlank()) return null;
        // 提取 "ID: xxx" 格式（Python 版 NoteTool 返回的消息会包含 ID）
        Matcher m = Pattern.compile("ID:\\s*([^\\s\n]+)").matcher(response);
        if (m.find()) return m.group(1);

        // 备选：尝试从 JSON 响应中提取
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = GSON.fromJson(response, Map.class);
            Object id = map.get("note_id");
            if (id != null) return String.valueOf(id);
        } catch (Exception ignored) {}

        return null;
    }
}
