package com.example.agent.app;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.example.agent.memory.MemoryTool;
import com.example.agent.rag.RAGTool;

/**
 * 智能文档问答助手（PDF 学习助手）
 *
 * 封装 RAGTool + MemoryTool 的调用逻辑，提供完整的交互式学习体验：
 * - 智能文档处理（PDF → Markdown → 分块 → 向量化）
 * - 高级检索问答（MQE + HyDE）
 * - 多层次记忆管理（工作/情景/语义/感知）
 * - 个性化学习支持（记忆整合/选择性遗忘/学习报告）
 */
public class PDFLearningAssistant {

    private final String userId;
    private final String sessionId;
    private final MemoryTool memoryTool;
    private final RAGTool ragTool;
    private final Map<String, Object> stats;
    private String currentDocument;

    // ==================== 构造 ====================

    public PDFLearningAssistant() {
        this("default_user");
    }

    public PDFLearningAssistant(String userId) {
        this.userId = userId != null ? userId : "default_user";
        this.sessionId = "session_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // RAG 知识库按用户隔离（通过 rag_namespace）
        this.ragTool = new RAGTool();

        // 记忆系统按 session 隔离
        this.memoryTool = new MemoryTool();
        this.memoryTool.setCurrentSessionId(this.sessionId);

        // 初始化统计
        this.stats = new LinkedHashMap<>();
        stats.put("session_start", LocalDateTime.now());
        stats.put("documents_loaded", 0);
        stats.put("questions_asked", 0);
        stats.put("concepts_learned", 0);

        this.currentDocument = null;

        System.out.println("[助手] 会话已创建: " + this.sessionId + " (用户: " + this.userId + ")");
    }

    // ==================== 文档加载 ====================

    /**
     * 加载 PDF 文档到知识库。
     * 内部触发：Tika 转换 → 智能分块 → 向量化 → 记忆记录
     *
     * @param pdfPath PDF 文件路径
     * @return {success, message, document, process_time}
     */
    public Map<String, Object> loadDocument(String pdfPath) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (pdfPath == null || pdfPath.isBlank()) {
            result.put("success", false);
            result.put("message", "文件路径不能为空");
            return result;
        }

        File file = new File(pdfPath);
        if (!file.exists()) {
            result.put("success", false);
            result.put("message", "文件不存在: " + pdfPath);
            return result;
        }

        long startTime = System.currentTimeMillis();

        // 【RAGTool】处理文档: Tika 转换 → Markdown 智能分块 → 向量化
        String ragResult = ragTool.run(ragParams(
                "action", "add_file",
                "file_path", pdfPath,
                "chunk_size", 1000,
                "chunk_overlap", 200
        ));

        double processTime = (System.currentTimeMillis() - startTime) / 1000.0;

        if (ragResult != null && ragResult.contains("✅")) {
            currentDocument = file.getName();
            stats.put("documents_loaded", (int) stats.get("documents_loaded") + 1);

            // 【MemoryTool】记录加载事件到情景记忆
            memoryTool.execute("add",
                    "content", "加载了文档《" + currentDocument + "》",
                    "memory_type", "episodic",
                    "importance", 0.9,
                    "event_type", "document_loaded"
            );

            result.put("success", true);
            result.put("message", "加载成功！（耗时: " + String.format("%.1f", processTime) + "秒）");
            result.put("document", currentDocument);
            result.put("process_time", processTime);
        } else {
            result.put("success", false);
            result.put("message", "加载失败: " + (ragResult != null ? ragResult : "未知错误"));
        }

        return result;
    }

    // ==================== 智能问答 ====================

    /**
     * 向已加载的文档提问（使用高级检索 MQE + HyDE）。
     *
     * 内部流程：工作记忆 ← 问题 → RAGTool 高级检索 → 情景记忆 ← QA 交互
     *
     * @param question             用户问题
     * @param useAdvancedSearch    是否启用高级检索（MQE + HyDE）
     * @return 答案
     */
    public String ask(String question, boolean useAdvancedSearch) {
        if (currentDocument == null) {
            return "⚠️ 请先加载文档！";
        }
        if (question == null || question.isBlank()) {
            return "⚠️ 问题不能为空";
        }

        // 1. 【MemoryTool】记录问题到工作记忆
        memoryTool.execute("add",
                "content", "提问: " + question,
                "memory_type", "working",
                "importance", 0.6
        );

        // 2. 【RAGTool】两阶段检索：混合检索粗筛 → LLM Reranker 精排
        String answer = ragTool.run(ragParams(
                "action", "ask",
                "query", question,
                "top_k", 5,
                "enable_advanced_search", useAdvancedSearch,
                "enable_mqe", useAdvancedSearch,
                "enable_hyde", useAdvancedSearch,
                "candidate_pool_multiplier", 15,
                "enable_rerank", useAdvancedSearch
        ));

        // 3. 【MemoryTool】记录 QA 交互到情景记忆
        memoryTool.execute("add",
                "content", "关于「" + truncate(question, 80) + "」的学习",
                "memory_type", "episodic",
                "importance", 0.7,
                "event_type", "qa_interaction"
        );

        stats.put("questions_asked", (int) stats.get("questions_asked") + 1);

        return answer;
    }

    /** 简写：默认启用高级检索 */
    public String ask(String question) {
        return ask(question, true);
    }

    // ==================== 学习笔记 ====================

    /**
     * 添加学习笔记到语义记忆。
     *
     * @param content 笔记内容
     * @param concept 关联概念（可选）
     */
    public void addNote(String content, String concept) {
        memoryTool.execute("add",
                "content", content,
                "memory_type", "semantic",
                "importance", 0.8,
                "concept", concept != null ? concept : "general"
        );
        stats.put("concepts_learned", (int) stats.get("concepts_learned") + 1);
    }

    /** 添加笔记（无指定概念） */
    public void addNote(String content) {
        addNote(content, "general");
    }

    // ==================== 学习回顾 ====================

    /**
     * 从记忆系统中检索学习历程。
     *
     * @param query 检索关键词
     * @param limit 返回数量上限
     * @return 检索结果
     */
    public String recall(String query, int limit) {
        return memoryTool.execute("search",
                "query", query,
                "limit", limit
        );
    }

    public String recall(String query) {
        return recall(query, 5);
    }

    // ==================== 记忆整合 ====================

    /** 将重要的情景记忆提升为语义记忆 */
    public Map<String, Object> consolidateMemories() {
        Map<String, Object> result = new LinkedHashMap<>();
        String consResult = memoryTool.execute("consolidate",
                "from_type", "episodic",
                "to_type", "semantic",
                "threshold", 0.6
        );
        result.put("success", consResult != null && consResult.contains("✅"));
        result.put("result", consResult);
        return result;
    }

    // ==================== 学习统计 ====================

    /**
     * 获取当前会话的学习统计。
     *
     * @return 包含会话时长、加载文档数、提问次数、笔记数等的 Map
     */
    public Map<String, Object> getStats() {
        LocalDateTime start = (LocalDateTime) stats.get("session_start");
        long durationSeconds = Duration.between(start, LocalDateTime.now()).getSeconds();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("会话时长", durationSeconds + "秒");
        result.put("加载文档", stats.get("documents_loaded"));
        result.put("提问次数", stats.get("questions_asked"));
        result.put("学习笔记", stats.get("concepts_learned"));
        result.put("当前文档", currentDocument != null ? currentDocument : "未加载");
        return result;
    }

    // ==================== 学习报告 ====================

    /**
     * 生成详细的学习报告，可选保存为 JSON 文件。
     *
     * @param saveToFile 是否保存为 JSON 文件
     * @return 结构化的学习报告
     */
    public Map<String, Object> generateReport(boolean saveToFile) {
        // 记忆摘要
        String memorySummary = memoryTool.execute("summary", "limit", 10);
        String ragStats = ragTool.run(ragParams("action", "stats"));

        LocalDateTime start = (LocalDateTime) stats.get("session_start");
        long durationSeconds = Duration.between(start, LocalDateTime.now()).getSeconds();

        // 构建报告结构（对齐 Python 版本）
        Map<String, Object> report = new LinkedHashMap<>();

        Map<String, Object> sessionInfo = new LinkedHashMap<>();
        sessionInfo.put("session_id", sessionId);
        sessionInfo.put("user_id", userId);
        sessionInfo.put("start_time", start.toString());
        sessionInfo.put("duration_seconds", durationSeconds);
        report.put("session_info", sessionInfo);

        Map<String, Object> learningMetrics = new LinkedHashMap<>();
        learningMetrics.put("documents_loaded", stats.get("documents_loaded"));
        learningMetrics.put("questions_asked", stats.get("questions_asked"));
        learningMetrics.put("concepts_learned", stats.get("concepts_learned"));
        report.put("learning_metrics", learningMetrics);

        report.put("memory_summary", memorySummary);
        report.put("rag_status", ragStats);

        // 可选：保存为 JSON
        if (saveToFile) {
            String reportFile = "learning_report_" + sessionId + ".json";
            try (FileWriter fw = new FileWriter(reportFile, java.nio.charset.StandardCharsets.UTF_8)) {
                fw.write(toJson(report));
            } catch (IOException e) {
                System.out.println("[报告] 保存失败: " + e.getMessage());
            }
            report.put("report_file", reportFile);
        }

        return report;
    }

    /** 生成报告（默认保存到文件） */
    public Map<String, Object> generateReport() {
        return generateReport(true);
    }

    // ==================== 访问器 ====================

    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getCurrentDocument() { return currentDocument; }
    public MemoryTool getMemoryTool() { return memoryTool; }
    public RAGTool getRagTool() { return ragTool; }

    // ==================== 内部工具方法 ====================

    /** 构建 Map<String, Object> 参数（键值对交替），用于 RAGTool.run() */
    private static Map<String, Object> ragParams(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** 简易 Map → JSON 序列化（零外部依赖） */
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
}
