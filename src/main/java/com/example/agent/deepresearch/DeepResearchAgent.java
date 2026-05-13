package com.example.agent.deepresearch;

import com.example.agent.deepresearch.model.DeepResearchModels.*;
import com.example.agent.deepresearch.service.*;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.ToolAwareSimpleAgent;
import com.example.agent.tool.NoteTool;
import com.example.agent.tool.NoteToolAdapter;
import com.example.agent.tool.ToolRegistry;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 深度研究 Agent — 主编排器。
 * 对应 Python agent.py DeepResearchAgent。
 */
public class DeepResearchAgent {

    private static final Gson GSON = new Gson();

    private final DeepResearchConfig config;
    private final HelloAgentsLLM llm;
    private final ToolRegistry toolRegistry;
    private final ToolCallTracker toolTracker;
    private final NoteTool noteTool;
    private final java.util.concurrent.locks.ReentrantLock stateLock = new java.util.concurrent.locks.ReentrantLock();

    private final ToolAwareSimpleAgent todoAgent;
    private final ToolAwareSimpleAgent reportAgent;

    private final PlanningService planning;
    private final SearchService search;
    private final SummarizationService summarization;
    private final ReportingService reporting;

    public DeepResearchAgent(DeepResearchConfig config) {
        this.config = config;

        // 初始化 LLM
        this.llm = initLlm();

        // 初始化笔记工具
        NoteTool nt = null;
        ToolRegistry tr = new ToolRegistry();
        if (config.isEnableNotes()) {
            nt = new NoteTool(java.nio.file.Paths.get(config.getNotesWorkspace()));
            NoteToolAdapter adapter = new NoteToolAdapter(nt);
            tr.registerTool(adapter);
        }
        this.noteTool = nt;
        this.toolRegistry = tr;

        // 工具调用追踪器
        this.toolTracker = new ToolCallTracker();

        // 创建 Agent
        this.todoAgent = createToolAwareAgent("研究规划专家",
                DeepResearchPrompts.TODO_PLANNER_SYSTEM);
        this.reportAgent = createToolAwareAgent("报告撰写专家",
                DeepResearchPrompts.REPORT_WRITER_SYSTEM);

        // 创建服务
        this.planning = new PlanningService(llm, todoAgent);
        this.search = new SearchService();
        this.summarization = new SummarizationService(
                () -> createToolAwareAgent("研究执行专家",
                        DeepResearchPrompts.TASK_SUMMARIZER_SYSTEM),
                config);
        this.reporting = new ReportingService(llm, reportAgent);
    }

    // ==================== LLM 初始化 ====================

    private HelloAgentsLLM initLlm() {
        String modelId = config.getLlmModelId();
        String apiKey = config.getLlmApiKey();
        String baseUrl = config.getLlmBaseUrl();

        if (modelId != null && !modelId.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank()) {
            return new HelloAgentsLLM(modelId, apiKey, baseUrl, 300);
        }

        try {
            return new HelloAgentsLLM();
        } catch (Exception e) {
            System.err.println("[DeepResearchAgent] LLM 初始化失败: " + e.getMessage());
            throw new RuntimeException("无法初始化 LLM", e);
        }
    }

    // ==================== Agent 创建 ====================

    private ToolAwareSimpleAgent createToolAwareAgent(String name, String systemPrompt) {
        ToolAwareSimpleAgent agent = new ToolAwareSimpleAgent(name, llm, systemPrompt,
                toolTracker::record);
        ToolAwareSimpleAgent.attachRegistry(agent, toolRegistry);
        return agent;
    }

    // ==================== 同步运行 ====================

    /** 同步执行深度研究，返回 SummaryStateOutput。 */
    public SummaryStateOutput run(String topic) {
        SummaryState state = new SummaryState(topic);
        System.out.println("\n🔬 开始深度研究: " + topic);

        // 1. 规划任务
        System.out.println("\n📋 第1步: 规划调研任务...");
        List<TodoItem> tasks = planning.planTodoList(state);
        if (tasks.isEmpty()) {
            tasks = List.of(planning.createFallbackTask(state));
        }
        state.setTodoItems(tasks);
        System.out.println("   规划了 " + tasks.size() + " 个任务");

        // 2. 执行每个任务
        for (int i = 0; i < tasks.size(); i++) {
            TodoItem task = tasks.get(i);
            System.out.println("\n🔍 第" + (i+2) + "步: 执行任务 " + task.getId() + " - " + task.getTitle());
            executeTask(state, task, i + 1, false);
        }

        // 3. 生成报告
        System.out.println("\n📝 最后一步: 生成研究报告...");
        String report = reporting.generateReport(state);
        state.setStructuredReport(report);

        // 4. 持久化报告
        persistFinalReport(state, report);

        String runningSummary = buildRunningSummary(state);
        state.setRunningSummary(runningSummary);

        System.out.println("✅ 深度研究完成");

        SummaryStateOutput output = new SummaryStateOutput();
        output.setRunningSummary(runningSummary);
        output.setReportMarkdown(report);
        output.setTodoItems(state.getTodoItems());
        return output;
    }

    // ==================== 流式运行 ====================

    /**
     * 流式执行深度研究，通过 callback 发送 SSE 事件。
     * @param topic 研究主题
     * @param eventSink 事件回调，接收 JSON 字符串
     */
    public void runStream(String topic, Consumer<String> eventSink) throws Exception {
        SummaryState state = new SummaryState(topic);
        toolTracker.reset();
        toolTracker.setEventSink(dict -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "tool_call");
            event.put("data", dict);
            eventSink.accept(GSON.toJson(event));
        });

        sendEvent(eventSink, "status", Map.of("message", "开始深度研究", "topic", topic));

        // 1. 规划
        sendEvent(eventSink, "status", Map.of("message", "正在规划调研任务..."));
        List<TodoItem> tasks = planning.planTodoList(state);
        if (tasks.isEmpty()) {
            tasks = List.of(planning.createFallbackTask(state));
        }
        state.setTodoItems(tasks);

        List<Map<String, Object>> taskDicts = new ArrayList<>();
        for (TodoItem t : tasks) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", t.getId());
            d.put("title", t.getTitle());
            d.put("intent", t.getIntent());
            d.put("query", t.getQuery());
            d.put("status", t.getStatus());
            taskDicts.add(d);
        }
        sendEvent(eventSink, "todo_list", Map.of("tasks", taskDicts));

        // 2. 并行执行任务（使用线程池）
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(tasks.size(), 3));
        BlockingQueue<Map<String, Object>> eventQueue = new LinkedBlockingQueue<>();
        CountDownLatch latch = new CountDownLatch(tasks.size());

        for (int i = 0; i < tasks.size(); i++) {
            final TodoItem task = tasks.get(i);
            final int step = i + 1;
            executor.submit(() -> {
                try {
                    executeTaskStream(state, task, step, eventQueue);
                } catch (Exception e) {
                    task.setStatus("error");
                    task.setSummary("执行异常: " + e.getMessage());
                    Map<String, Object> errEvent = new LinkedHashMap<>();
                    errEvent.put("type", "task_status");
                    errEvent.put("task_id", task.getId());
                    errEvent.put("status", "error");
                    errEvent.put("message", e.getMessage());
                    eventQueue.add(errEvent);
                } finally {
                    latch.countDown();
                }
            });
        }

        // 排空事件队列
        while (latch.getCount() > 0 || !eventQueue.isEmpty()) {
            Map<String, Object> evt = eventQueue.poll(100, TimeUnit.MILLISECONDS);
            if (evt != null) {
                eventSink.accept(GSON.toJson(evt));
            }
        }
        executor.shutdown();

        // 排空残余的工具事件
        drainToolEvents(state, eventSink);

        // 3. 生成报告
        sendEvent(eventSink, "status", Map.of("message", "正在生成最终报告..."));
        String report = reporting.generateReport(state);
        state.setStructuredReport(report);

        persistFinalReport(state, report);

        sendEvent(eventSink, "final_report", Map.of("report", report));
        sendEvent(eventSink, "done", Map.of("message", "研究完成"));
    }

    // ==================== 任务执行 ====================

    @SuppressWarnings("unchecked")
    private void executeTask(SummaryState state, TodoItem task, int step, boolean emitStream) {
        // 搜索
        Map<String, Object> searchResult = search.dispatchSearch(task.getQuery(), config,
                state.getResearchLoopCount());
        List<Map<String, Object>> payload = (List<Map<String, Object>>) searchResult.getOrDefault("payload", List.of());
        List<String> notices = (List<String>) searchResult.getOrDefault("notices", List.of());

        if (payload.isEmpty()) {
            task.setStatus("skipped");
            task.setSummary("无搜索结果: " + String.join(", ", notices));
            return;
        }

        // 准备上下文
        String context = search.prepareResearchContext(searchResult, config);
        String answerText = (String) searchResult.getOrDefault("answer_text", "");

        state.getWebResearchResults().add(context);
        state.getSourcesGathered().add(context);
        state.setResearchLoopCount(state.getResearchLoopCount() + 1);

        task.setSourcesSummary(TextProcessingUtils.formatSources(
                payload.stream().map(p -> {
                    Map<String, String> s = new LinkedHashMap<>();
                    s.put("title", (String) p.getOrDefault("title", ""));
                    s.put("url", (String) p.getOrDefault("url", ""));
                    return s;
                }).toList()));

        // 总结
        String summary = summarization.summarizeTask(state, task, context);
        task.setSummary(summary);
        task.setStatus("completed");

        // 排空工具事件
        toolTracker.drain(state.getTodoItems());
    }

    @SuppressWarnings("unchecked")
    private void executeTaskStream(SummaryState state, TodoItem task, int step,
                                    BlockingQueue<Map<String, Object>> eventQueue) {
        Map<String, Object> statusEvent = new LinkedHashMap<>();
        statusEvent.put("type", "task_status");
        statusEvent.put("task_id", task.getId());
        statusEvent.put("status", "searching");
        statusEvent.put("message", "正在搜索: " + task.getQuery());
        eventQueue.add(statusEvent);

        // 搜索
        Map<String, Object> searchResult = search.dispatchSearch(task.getQuery(), config,
                state.getResearchLoopCount());
        List<Map<String, Object>> payload = (List<Map<String, Object>>) searchResult.getOrDefault("payload", List.of());
        List<String> notices = (List<String>) searchResult.getOrDefault("notices", List.of());

        if (payload.isEmpty()) {
            task.setStatus("skipped");
            task.setSummary("无搜索结果: " + String.join(", ", notices));
            Map<String, Object> skipEvent = new LinkedHashMap<>();
            skipEvent.put("type", "task_status");
            skipEvent.put("task_id", task.getId());
            skipEvent.put("status", "skipped");
            skipEvent.put("message", "无搜索结果");
            eventQueue.add(skipEvent);
            return;
        }

        // 准备上下文 & 来源摘要（提前构建，供 sources 和 completed 事件共用）
        String context = search.prepareResearchContext(searchResult, config);
        stateLock.lock();
        try {
            state.getWebResearchResults().add(context);
            state.getSourcesGathered().add(context);
            state.setResearchLoopCount(state.getResearchLoopCount() + 1);
        } finally {
            stateLock.unlock();
        }

        task.setSourcesSummary(TextProcessingUtils.formatSources(
                payload.stream().map(p -> {
                    Map<String, String> s = new LinkedHashMap<>();
                    s.put("title", (String) p.getOrDefault("title", ""));
                    s.put("url", (String) p.getOrDefault("url", ""));
                    return s;
                }).toList()));

        // 发送来源事件 — 前端 parseSources 按文本格式解析
        Map<String, Object> sourcesEvent = new LinkedHashMap<>();
        sourcesEvent.put("type", "sources");
        sourcesEvent.put("task_id", task.getId());
        sourcesEvent.put("sources_summary", task.getSourcesSummary());
        eventQueue.add(sourcesEvent);

        // 流式总结
        Map<String, Object> summarisingEvent = new LinkedHashMap<>();
        summarisingEvent.put("type", "task_status");
        summarisingEvent.put("task_id", task.getId());
        summarisingEvent.put("status", "summarizing");
        summarisingEvent.put("message", "正在生成总结...");
        eventQueue.add(summarisingEvent);

        String summary = summarization.streamTaskSummary(state, task, context, chunk -> {
            Map<String, Object> chunkEvent = new LinkedHashMap<>();
            chunkEvent.put("type", "task_summary_chunk");
            chunkEvent.put("task_id", task.getId());
            chunkEvent.put("content", chunk);
            eventQueue.add(chunkEvent);
        });
        task.setSummary(summary);
        task.setStatus("completed");

        // 发送任务完成状态（前端依赖此事件更新进度和状态标签）
        Map<String, Object> completedEvent = new LinkedHashMap<>();
        completedEvent.put("type", "task_status");
        completedEvent.put("task_id", task.getId());
        completedEvent.put("status", "completed");
        completedEvent.put("summary", summary);
        completedEvent.put("sources_summary", task.getSourcesSummary());
        eventQueue.add(completedEvent);
    }

    // ==================== 工具事件管理 ====================

    private void drainToolEvents(SummaryState state, Consumer<String> eventSink) {
        List<Map<String, Object>> events = toolTracker.drain(state.getTodoItems());
        for (var evt : events) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("type", "tool_call");
            wrapped.put("data", evt);
            eventSink.accept(GSON.toJson(wrapped));
        }
    }

    // ==================== 报告持久化 ====================

    private void persistFinalReport(SummaryState state, String report) {
        if (!config.isEnableNotes() || noteTool == null) return;

        try {
            // 创建或更新结论笔记
            String existingNoteId = state.getReportNoteId();
            if (existingNoteId != null && !existingNoteId.isBlank()) {
                noteTool.updateNote(existingNoteId, report,
                        "深度研究报告: " + state.getResearchTopic(),
                        List.of("deep_research", "report"), "conclusion");
                state.setReportNoteId(existingNoteId);
            } else {
                String newId = noteTool.addNote(report, "conclusion",
                        List.of("deep_research", "report"),
                        "深度研究报告: " + state.getResearchTopic());
                state.setReportNoteId(newId);
                state.setReportNotePath("notes/" + newId + ".md");
            }
        } catch (Exception e) {
            System.err.println("[DeepResearchAgent] 报告持久化失败: " + e.getMessage());
        }
    }

    private String buildRunningSummary(SummaryState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 深度研究: ").append(state.getResearchTopic()).append("\n\n");
        sb.append("## 任务概览\n\n");
        for (TodoItem t : state.getTodoItems()) {
            sb.append("- **").append(t.getTitle()).append("**: ")
              .append(t.getStatus() != null ? t.getStatus() : "pending").append("\n");
        }
        return sb.toString();
    }

    // ==================== 事件发送 ====================

    private void sendEvent(Consumer<String> sink, String type, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.putAll(data);
        sink.accept(GSON.toJson(event));
    }

    // ==================== 静态便捷方法 ====================

    public static SummaryStateOutput runDeepResearch(String topic, DeepResearchConfig config) {
        DeepResearchAgent agent = new DeepResearchAgent(config);
        return agent.run(topic);
    }
}
