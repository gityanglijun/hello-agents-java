package com.example.agent.app;

import com.example.agent.Message;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.memory.MemoryTool;
import com.example.agent.pattern.FunctionCallAgent;
import com.example.agent.rag.ContextBuilder;
import com.example.agent.rag.ContextConfig;
import com.example.agent.rag.ContextPacket;
import com.example.agent.tool.NoteTool;
import com.example.agent.tool.NoteToolAdapter;
import com.example.agent.tool.TerminalTool;
import com.example.agent.tool.ToolRegistry;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 代码库维护助手 — 长程智能体 (Agentic 版)。
 *
 * 整合 FunctionCallAgent + ToolRegistry + ContextBuilder，
 * LLM 自主决策调用 TerminalTool / NoteTool / MemoryTool。
 *
 * <pre>
 * 架构分层：
 *   CodebaseMaintainer（调度中心）
 *       ├── ToolRegistry      → 注册所有工具，生成 OpenAI function calling schema
 *       ├── FunctionCallAgent  → Agentic 循环（LLM 自主决策调哪些工具）
 *       ├── ContextBuilder     → 组装初始上下文（笔记 + 记忆 → 系统提示）
 *       ├── TerminalTool       → 沙箱终端（LLM 可自主调用）
 *       ├── NoteToolAdapter    → 笔记管理（LLM 可自主调用）
 *       └── MemoryTool         → 短期记忆（ContextBuilder + LLM 可调用）
 *
 * 使用示例：
 *   CodebaseMaintainer cm = new CodebaseMaintainer("my_project", "./src");
 *   cm.explore(".");          // 探索代码库
 *   cm.analyze("");           // 分析代码质量
 *   cm.planNextSteps();       // 规划下一步
 *   cm.generateReport();      // 生成报告
 * </pre>
 */
public class CodebaseMaintainer {

    // ==================== 常量 ====================

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ==================== 核心组件 ====================

    private final String projectName;
    private final String codebasePath;
    private final String sessionId;
    private final HelloAgentsLLM llm;
    private final MemoryTool memoryTool;
    private final NoteTool noteTool;
    private final NoteToolAdapter noteToolAdapter;
    private final TerminalTool terminalTool;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry toolRegistry;
    private final FunctionCallAgent functionCallAgent;

    // ==================== 状态 ====================

    private final List<Message> conversationHistory;
    private final Map<String, Object> stats;

    // ==================== 构造 ====================

    public CodebaseMaintainer(String projectName, String codebasePath) {
        this(projectName, codebasePath, null);
    }

    /**
     * @param projectName   项目名称（用于命名空间和笔记目录命名）
     * @param codebasePath  代码库路径（TerminalTool 的工作目录）
     * @param llm           LLM 客户端（null 则自动创建）
     */
    public CodebaseMaintainer(String projectName, String codebasePath, HelloAgentsLLM llm) {
        this.projectName = projectName != null ? projectName : "default_project";
        this.codebasePath = codebasePath != null ? codebasePath : ".";
        this.sessionId = "session_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        this.llm = llm != null ? llm : createDefaultLLM();

        // --- 初始化工具 ---
        this.memoryTool = new MemoryTool();
        this.memoryTool.setCurrentSessionId(this.sessionId);
        this.noteTool = new NoteTool(Path.of("./" + this.projectName + "_notes"));
        this.noteToolAdapter = new NoteToolAdapter(this.noteTool);
        this.terminalTool = new TerminalTool(
                Path.of(this.codebasePath).toAbsolutePath(), 60, 10 * 1024 * 1024, true);

        // --- ContextBuilder 只依赖 MemoryTool ---
        ContextConfig config = ContextConfig.builder()
                .maxTokens(4000)
                .reserveRatio(0.15)
                .minRelevance(0.2)
                .enableCompression(true)
                .build();
        this.contextBuilder = new ContextBuilder(memoryTool, null, config);

        // --- 注册工具到 ToolRegistry ---
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.registerTool(this.terminalTool);
        this.toolRegistry.registerTool(this.memoryTool);
        this.toolRegistry.registerTool(this.noteToolAdapter);

        // --- 创建 Agentic 智能体 ---
        this.functionCallAgent = new FunctionCallAgent(
                "CodebaseMaintainer-" + projectName,
                this.llm,
                buildSystemInstructions("auto"),
                null,
                this.toolRegistry,
                true,
                100);

        // --- 状态初始化 ---
        this.conversationHistory = new ArrayList<>();
        this.stats = new LinkedHashMap<>();
        stats.put("session_start", LocalDateTime.now());
        stats.put("commands_executed", 0);
        stats.put("notes_created", 0);
        stats.put("issues_found", 0);

        System.out.println("✅ 代码库维护助手已初始化: " + this.projectName);
        System.out.println("📁 工作目录: " + this.terminalTool.getWorkspace());
        System.out.println("🆔 会话ID: " + this.sessionId);
        System.out.println("🔧 已注册工具: " + toolRegistry.listTools());
    }

    // ==================== 核心运行方法 (Agentic) ====================

    /**
     * 运行助手（Agentic 模式 — LLM 自主决策调用工具）。
     *
     * @param userInput 用户输入
     * @param mode      运行模式: "auto" | "explore" | "analyze" | "plan"
     * @return 助手回答
     */
    public String run(String userInput, String mode) {
        if (mode == null || mode.isBlank()) mode = "auto";

        System.out.println("\n" + "=".repeat(60));
        System.out.println("👤 用户: " + userInput);
        System.out.println("=".repeat(60) + "\n");

        // Step 1: 构建初始上下文（笔记 + 记忆 → 系统提示）
        List<NoteTool.Note> relevantNotes = retrieveRelevantNotes(userInput, 3);
        List<ContextPacket> notePackets = notesToPackets(relevantNotes);

        String systemContext = contextBuilder.build(
                userInput,
                conversationHistory,
                buildSystemInstructions(mode),
                notePackets);
        functionCallAgent.setSystemPrompt(systemContext);

        // Step 2: Agentic 运行（LLM 自主决定调用 terminal/note/memory）
        String response = functionCallAgent.run(userInput);

        // Step 3: 收集统计 + 更新对话历史
        collectStatsFromAgent();
        updateHistory(userInput, response);

        System.out.println("\n" + "=".repeat(60) + "\n");

        return response != null ? response : "（LLM 无响应）";
    }

    /** 默认 auto 模式 */
    public String run(String userInput) {
        return run(userInput, "auto");
    }

    // ==================== 笔记检索（初始上下文） ====================

    private List<NoteTool.Note> retrieveRelevantNotes(String query, int limit) {
        Map<String, NoteTool.Note> dedup = new LinkedHashMap<>();

        try {
            // 1. 优先拉 blocker
            List<NoteTool.Note> blockers = noteTool.listNotes("blocker", null);
            for (NoteTool.Note note : blockers) {
                dedup.put(note.id, note);
                if (dedup.size() >= limit) break;
            }

            // 2. 关键词搜索
            List<NoteTool.Note> searchResults = noteTool.searchNotes(query, null, null);
            for (NoteTool.Note note : searchResults) {
                dedup.putIfAbsent(note.id, note);
                if (dedup.size() >= limit) break;
            }

        } catch (Exception e) {
            System.out.println("[WARNING] 笔记检索失败: " + e.getMessage());
        }

        return new ArrayList<>(dedup.values()).subList(0, Math.min(limit, dedup.size()));
    }

    private List<ContextPacket> notesToPackets(List<NoteTool.Note> notes) {
        List<ContextPacket> packets = new ArrayList<>();
        Map<String, Double> relevanceByType = Map.of(
                "blocker", 0.9,
                "action", 0.8,
                "task_state", 0.75,
                "conclusion", 0.7
        );

        for (NoteTool.Note note : notes) {
            double relevance = relevanceByType.getOrDefault(note.type, 0.6);

            String content = "[笔记:" + note.title + "]\n"
                    + "类型: " + note.type + "\n\n"
                    + note.content;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", "note");
            meta.put("note_type", note.type);
            meta.put("note_id", note.id);
            if (!note.tags.isEmpty()) meta.put("tags", note.tags);

            packets.add(new ContextPacket(
                    content,
                    parseTimestamp(note.updatedAt),
                    estimateTokens(content),
                    relevance,
                    meta));
        }
        return packets;
    }

    // ==================== 系统指令 ====================

    private String buildSystemInstructions(String mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ").append(projectName).append(" 项目的代码库维护助手。\n\n");
        sb.append("运行环境: ").append(System.getProperty("os.name", "Unknown")).append("\n");
        sb.append("你可以使用以下工具（由系统自动提供）:\n");
        sb.append("- **terminal**: 执行只读 shell 命令，探索代码库结构\n");
        sb.append("- **note**: 创建/搜索/更新/删除笔记，记录发现、问题和计划\n");
        sb.append("- **memory**: 管理跨会话记忆（working/episodic/semantic/perceptual）\n\n");
        sb.append("核心原则:\n");
        sb.append("- 主动使用工具获取所需信息，不要猜测\n");
        sb.append("- 探索代码库时先了解整体结构（ls/dir），再深入具体文件（cat/type）\n");
        sb.append("- 发现重要信息时使用 note 工具创建笔记\n");
        sb.append("- 发现问题或风险时，创建 blocker 类型笔记记录\n");
        sb.append("- 不需要工具时可以直接文本回复\n");
        sb.append("- 使用中文回复\n");
        sb.append("\n当前会话ID: ").append(sessionId).append("\n");

        String modeSpecific = switch (mode) {
            case "explore" -> """

                    当前模式: 探索代码库

                    你应该:
                    - 主动使用 terminal 了解代码结构
                    - 识别关键模块和文件
                    - 使用 note 工具记录项目架构
                    """;
            case "analyze" -> """

                    当前模式: 分析代码质量

                    你应该:
                    - 使用 terminal 查找代码问题（搜索 TODO、FIXME、重复代码等）
                    - 评估代码质量
                    - 将发现的问题记录为 blocker 类型笔记
                    """;
            case "plan" -> """

                    当前模式: 任务规划

                    你应该:
                    - 使用 note 工具搜索历史笔记和任务
                    - review 已有任务状态
                    - 制定清晰的下一步行动计划
                    """;
            default -> """

                    当前模式: 自动决策

                    你应该:
                    - 根据用户需求灵活选择策略
                    - 在需要时使用工具
                    - 保持回答的专业性和实用性
                    """;
        };

        sb.append(modeSpecific);
        return sb.toString();
    }

    // ==================== 统计收集 ====================

    private void collectStatsFromAgent() {
        Map<String, Integer> toolCounts = functionCallAgent.getToolCallCounts();
        int terminalCalls = toolCounts.getOrDefault("terminal", 0);
        int noteActions = toolCounts.getOrDefault("note", 0);
        int totalCalls = functionCallAgent.getTotalToolCalls();

        incrStat("commands_executed", terminalCalls);
        incrStat("notes_created", noteActions);

        if (totalCalls > 0) {
            System.out.println("🔧 本轮工具调用: " + totalCalls + " 次 " + toolCounts);
        }
    }

    // ==================== 对话历史 ====================

    private void updateHistory(String userInput, String response) {
        LocalDateTime now = LocalDateTime.now();
        conversationHistory.add(new Message(userInput, Message.ROLE_USER, now, null));
        if (response != null) {
            conversationHistory.add(new Message(response, Message.ROLE_ASSISTANT, now, null));
        }

        // 限制历史长度（最近 10 轮 = 20 条消息）
        if (conversationHistory.size() > 20) {
            int excess = conversationHistory.size() - 20;
            conversationHistory.subList(0, excess).clear();
        }
    }

    // ==================== 便捷方法 ====================

    /** 探索代码库 */
    public String explore(String target) {
        return run("请探索 " + (target != null ? target : ".") + " 的代码结构", "explore");
    }

    /** 分析代码质量 */
    public String analyze(String focus) {
        String query = "请分析代码质量";
        if (focus != null && !focus.isBlank()) query += "，重点关注" + focus;
        return run(query, "analyze");
    }

    /** 规划下一步 */
    public String planNextSteps() {
        return run("根据当前进度，规划下一步任务", "plan");
    }

    /** 执行终端命令（直接调用，不经过 Agentic 循环） */
    public String executeCommand(String command) {
        String result = terminalTool.run(Map.of("command", command));
        incrStat("commands_executed");
        return result;
    }

    /** 手动创建笔记（直接调用，不经过 Agentic 循环） */
    public String createNote(String title, String content, String type, List<String> tags) {
        List<String> allTags = new ArrayList<>(tags != null ? tags : List.of());
        if (!allTags.contains(projectName)) allTags.add(0, projectName);
        String id = noteTool.addNote(content, type, allTags, title);
        incrStat("notes_created");
        return id;
    }

    // ==================== 统计与报告 ====================

    public Map<String, Object> getStats() {
        LocalDateTime start = (LocalDateTime) stats.get("session_start");
        long durationSeconds = Duration.between(start, LocalDateTime.now()).getSeconds();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", sessionId);
        result.put("project", projectName);
        result.put("duration_seconds", durationSeconds);
        result.put("commands_executed", stats.get("commands_executed"));
        result.put("notes_created", stats.get("notes_created"));
        result.put("issues_found", stats.get("issues_found"));
        return result;
    }

    public Map<String, Object> generateReport(boolean saveToFile) {
        Map<String, Object> report = new LinkedHashMap<>();

        Map<String, Object> sessionInfo = new LinkedHashMap<>();
        sessionInfo.put("session_id", sessionId);
        sessionInfo.put("project", projectName);
        sessionInfo.put("start_time", stats.get("session_start").toString());
        sessionInfo.put("duration_seconds",
                Duration.between((LocalDateTime) stats.get("session_start"), LocalDateTime.now()).getSeconds());
        report.put("session_info", sessionInfo);

        report.put("activity", getStats());
        report.put("note_count", noteTool.count());
        report.put("note_types", noteTool.listTypes());

        if (saveToFile) {
            String reportFile = "maintainer_report_" + sessionId + ".json";
            try (FileWriter fw = new FileWriter(reportFile, java.nio.charset.StandardCharsets.UTF_8)) {
                fw.write(toJson(report));
                System.out.println("📄 报告已保存: " + reportFile);
                report.put("report_file", reportFile);
            } catch (IOException e) {
                System.out.println("[WARNING] 保存报告失败: " + e.getMessage());
            }
        }

        return report;
    }

    /** 生成报告（默认保存） */
    public Map<String, Object> generateReport() {
        return generateReport(true);
    }

    // ==================== 访问器 ====================

    public String getProjectName() { return projectName; }
    public String getSessionId() { return sessionId; }
    public MemoryTool getMemoryTool() { return memoryTool; }
    public NoteTool getNoteTool() { return noteTool; }
    public TerminalTool getTerminalTool() { return terminalTool; }
    public ContextBuilder getContextBuilder() { return contextBuilder; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public FunctionCallAgent getFunctionCallAgent() { return functionCallAgent; }

    // ==================== 内部辅助 ====================

    private HelloAgentsLLM createDefaultLLM() {
        try {
            return new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("[WARNING] LLM 初始化失败: " + e.getMessage());
            return null;
        }
    }

    private void incrStat(String key) {
        stats.put(key, ((Number) stats.getOrDefault(key, 0)).intValue() + 1);
    }

    private void incrStat(String key, int delta) {
        stats.put(key, ((Number) stats.getOrDefault(key, 0)).intValue() + delta);
    }

    private int estimateTokens(String text) {
        return com.example.agent.rag.MarkdownChunker.approxTokenLen(text);
    }

    private LocalDateTime parseTimestamp(String iso) {
        try {
            return LocalDateTime.parse(iso, ISO);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private static <V> Map<String, V> mapOf(String k1, V v1, String k2, V v2) {
        Map<String, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{\n");
        int count = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (count++ > 0) sb.append(",\n");
            sb.append("  \"").append(escapeJson(e.getKey())).append("\": ");
            Object val = e.getValue();
            if (val instanceof Map) {
                sb.append(toJson((Map<String, Object>) val));
            } else if (val instanceof Number || val instanceof Boolean) {
                sb.append(val);
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
            }
        }
        return sb.append("\n}").toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
