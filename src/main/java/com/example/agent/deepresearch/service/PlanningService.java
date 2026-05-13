package com.example.agent.deepresearch.service;

import com.example.agent.deepresearch.DeepResearchPrompts;
import com.example.agent.deepresearch.model.DeepResearchModels.*;
import com.example.agent.deepresearch.TextProcessingUtils;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.SimpleAgent;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规划服务，将研究主题拆分为待办任务。
 * 对应 Python services/planner.py。
 */
public class PlanningService {

    private static final Gson GSON = new Gson();
    private final SimpleAgent plannerAgent;
    private final HelloAgentsLLM llm;

    public PlanningService(HelloAgentsLLM llm, SimpleAgent plannerAgent) {
        this.llm = llm;
        this.plannerAgent = plannerAgent;
    }

    /** 执行规划，返回任务列表。若规划失败则返回单条回退任务。 */
    public List<TodoItem> planTodoList(SummaryState state) {
        String instructions = DeepResearchPrompts.todoPlannerInstructions(state.getResearchTopic());

        try {
            // 先用直接 LLM 调用获取 JSON（绕过 agent 工具循环，避免第一轮 JSON 被工具调用覆盖）
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", DeepResearchPrompts.TODO_PLANNER_SYSTEM));
            messages.add(Map.of("role", "user", "content", instructions));
            String rawResponse = llm.think(messages);

            List<TodoItem> tasks = extractTasks(rawResponse);

            // 再运行 agent 创建笔记（独立调用，不影响任务提取）
            if (!tasks.isEmpty()) {
                plannerAgent.run(instructions);
            }
            plannerAgent.clearHistory();

            return tasks;
        } catch (Exception e) {
            System.err.println("[PlanningService] 规划失败: " + e.getMessage());
            return List.of(createFallbackTask(state));
        }
    }

    /** 创建回退任务（单一搜索任务）。 */
    public TodoItem createFallbackTask(SummaryState state) {
        TodoItem task = new TodoItem();
        task.setId(1);
        task.setTitle("综合调研");
        task.setIntent("对研究主题进行整体搜索和调研");
        task.setQuery(state.getResearchTopic());
        task.setStatus("pending");
        return task;
    }

    // ==================== 内部 ====================

    @SuppressWarnings("unchecked")
    private List<TodoItem> extractTasks(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) return List.of();

        String cleaned = TextProcessingUtils.stripThinkingTokens(rawResponse);

        // 1) 标准 JSON: {"tasks": [...]}
        Map<String, Object> json = extractJsonPayload(cleaned);
        if (json != null) {
            Object tasksRaw = json.get("tasks");
            if (tasksRaw instanceof List) {
                return buildTasksFromJson((List<Map<String, Object>>) tasksRaw);
            }
        }

        // 2) TOOL_CALL 语法
        Map<String, Object> toolPayload = extractToolPayload(cleaned);
        if (toolPayload != null) {
            Object tasksRaw = toolPayload.get("tasks");
            if (tasksRaw instanceof List) {
                return buildTasksFromJson((List<Map<String, Object>>) tasksRaw);
            }
        }

        // 3) Markdown 表格 fallback（LLM 可能忽略 JSON 格式要求）
        List<TodoItem> tableTasks = extractMarkdownTable(cleaned);
        if (!tableTasks.isEmpty()) return tableTasks;

        return List.of();
    }

    private List<TodoItem> buildTasksFromJson(List<Map<String, Object>> taskList) {
        List<TodoItem> items = new ArrayList<>();
        for (int i = 0; i < taskList.size(); i++) {
            Map<String, Object> t = taskList.get(i);
            TodoItem item = new TodoItem();
            item.setId(i + 1);
            item.setTitle((String) t.getOrDefault("title", "任务 " + (i + 1)));
            item.setIntent((String) t.getOrDefault("intent", ""));
            item.setQuery((String) t.getOrDefault("query", ""));
            item.setStatus("pending");
            items.add(item);
        }
        return items;
    }

    /** 从文本中提取 JSON 负载（最外层 {...}）。 */
    static Map<String, Object> extractJsonPayload(String text) {
        // 先找 { ... }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            try {
                return GSON.fromJson(text.substring(braceStart, braceEnd + 1), Map.class);
            } catch (Exception ignored) {}
        }
        // 再找 [ ... ]
        int bracketStart = text.indexOf('[');
        int bracketEnd = text.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            try {
                return Map.of("tasks", GSON.fromJson(
                        text.substring(bracketStart, bracketEnd + 1),
                        new TypeToken<List<Map<String, Object>>>(){}.getType()));
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 从 Markdown 表格中提取任务。匹配 | id | title | intent | query | 格式。 */
    static List<TodoItem> extractMarkdownTable(String text) {
        List<TodoItem> items = new ArrayList<>();
        Pattern rowPattern = Pattern.compile(
                "^\\s*\\|\\s*(\\d+)\\s*\\|\\s*(.+?)\\s*\\|\\s*(.+?)\\s*\\|\\s*(.+?)\\s*\\|\\s*$",
                Pattern.MULTILINE);
        Matcher m = rowPattern.matcher(text);
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            String title = m.group(2).trim();
            String intent = m.group(3).trim();
            String query = m.group(4).trim()
                    .replaceAll("^`|`$", "")  // 去掉反引号包裹
                    .replaceAll("`", "");      // 去掉内部反引号
            // 跳过表头行（title 为 "标题" 或 "任务" 等）
            if (title.contains("标题") && intent.contains("意图")) continue;
            TodoItem item = new TodoItem();
            item.setId(id);
            item.setTitle(title);
            item.setIntent(intent);
            item.setQuery(query);
            item.setStatus("pending");
            items.add(item);
        }
        return items;
    }

    /** 从 TOOL_CALL 语法提取参数。 */
    static Map<String, Object> extractToolPayload(String text) {
        Pattern p = Pattern.compile("\\[TOOL_CALL:(?<tool>[^:]+):(?<body>[^\\]]+)\\]");
        Matcher m = p.matcher(text);
        if (m.find()) {
            String body = m.group("body").trim();
            // JSON body
            try {
                return GSON.fromJson(body, Map.class);
            } catch (Exception ignored) {}
            // key=value body
            Map<String, Object> result = new LinkedHashMap<>();
            String[] pairs = body.split(",");
            for (String pair : pairs) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    result.put(kv[0].trim(), kv[1].trim());
                }
            }
            return result;
        }
        return null;
    }
}
