package com.example.agent.pattern;

import com.example.agent.Message;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.rag.ContextPacket;
import com.example.agent.tool.NoteTool;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 长期项目助手 — 集成 NoteTool + ContextBuilder。
 *
 * 每个 run() 调用自动执行：
 *   1. 从 NoteTool 检索相关笔记（优先 blocker 类型）
 *   2. 将笔记转为 ContextPacket 注入上下文流水线
 *   3. LLM 基于历史笔记 + 记忆 + RAG 生成回答
 *   4. 可选：将重要交互自动保存为笔记
 *
 * 使用示例：
 * <pre>
 *   ProjectAssistant assistant = new ProjectAssistant("项目助手", "data_pipeline_refactoring");
 *   String response = assistant.run("已完成数据模型层重构，下一步做什么？", true);
 * </pre>
 */
public class ProjectAssistant extends ContextAwareAgent {

    private final String projectName;
    private final NoteTool noteTool;

    public ProjectAssistant(String name, String projectName, HelloAgentsLLM llm, String systemPrompt) {
        super(name, llm, systemPrompt, projectName, null);
        this.projectName = projectName;
        this.noteTool = new NoteTool(java.nio.file.Paths.get("./" + projectName + "_notes"));
    }

    public ProjectAssistant(String name, String projectName, HelloAgentsLLM llm) {
        this(name, projectName, llm, null);
    }

    // ==================== 核心：带笔记的 run ====================

    /**
     * @param userInput      用户输入
     * @param noteAsAction   是否将本次交互自动保存为笔记
     */
    public String run(String userInput, boolean noteAsAction) {
        System.out.println("\n🤖 " + name + " 正在处理: " + userInput);

        // 1. 检索相关笔记
        List<NoteTool.Note> relevantNotes = retrieveRelevantNotes(userInput, 3);

        // 2. 笔记 → ContextPacket
        List<ContextPacket> notePackets = notesToPackets(relevantNotes);

        // 3. 构建上下文（复用 ContextAwareAgent 的 ContextBuilder）
        String optimizedContext = getContextBuilder().build(
                userInput,
                getConversationHistory(),
                buildSystemInstructions(),
                notePackets
        );

        // 4. LLM 调用
        List<Message> llmMessages = List.of(
                new Message(optimizedContext, Message.ROLE_SYSTEM),
                new Message(userInput, Message.ROLE_USER)
        );

        String response = llm.thinkMessages(llmMessages);
        if (response == null || response.isBlank()) {
            response = "（LLM 无响应）";
        }

        // 5. 自动保存笔记
        if (noteAsAction) {
            saveAsNote(userInput, response);
        }

        // 6. 更新对话历史
        LocalDateTime now = LocalDateTime.now();
        getConversationHistory().add(new Message(userInput, Message.ROLE_USER, now, null));
        getConversationHistory().add(new Message(response, Message.ROLE_ASSISTANT, now, null));
        addMessage(new Message(userInput, Message.ROLE_USER));
        addMessage(new Message(response, Message.ROLE_ASSISTANT));

        // 7. 记忆系统记录交互摘要
        String summary = response.length() > 200 ? response.substring(0, 200) + "..." : response;
        getMemoryTool().execute("add",
                "content", "Q: " + userInput + "\nA: " + summary,
                "memory_type", "episodic",
                "importance", 0.6,
                "event_type", "qa_interaction"
        );

        System.out.println("✅ " + name + " 响应完成 (相关笔记: " + relevantNotes.size() + " 条)");
        return response;
    }

    /** 不带笔记保存的简化调用 */
    @Override
    public String run(String userInput) {
        return run(userInput, false);
    }

    // ==================== 笔记检索 ====================

    /**
     * 检索与查询相关的笔记。优先返回 blocker 类型，再合并搜索匹配结果。
     */
    private List<NoteTool.Note> retrieveRelevantNotes(String query, int limit) {
        Map<String, NoteTool.Note> dedup = new LinkedHashMap<>();

        try {
            // 1. 优先检索 blocker 类型（阻塞性问题）
            List<NoteTool.Note> blockers = noteTool.listNotes("blocker", null);
            for (NoteTool.Note note : blockers) {
                dedup.put(note.id, note);
                if (dedup.size() >= limit) break;
            }

            // 2. 关键词搜索
            List<NoteTool.Note> searchResults = noteTool.searchNotes(query);
            for (NoteTool.Note note : searchResults) {
                dedup.putIfAbsent(note.id, note);
                if (dedup.size() >= limit) break;
            }

        } catch (Exception e) {
            System.out.println("[ProjectAssistant] 笔记检索失败: " + e.getMessage());
        }

        return new ArrayList<>(dedup.values()).subList(0, Math.min(limit, dedup.size()));
    }

    // ==================== 笔记 → ContextPacket ====================

    private List<ContextPacket> notesToPackets(List<NoteTool.Note> notes) {
        List<ContextPacket> packets = new ArrayList<>();

        for (NoteTool.Note note : notes) {
            String content = "[笔记:" + note.title + "]\n" + note.content;

            // token 估算：中英文混合，约 2.5 chars/token
            int tokenCount = content.length() * 2 / 5;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", "note");
            meta.put("note_type", note.type);
            meta.put("note_id", note.id);
            if (!note.tags.isEmpty()) meta.put("tags", note.tags);

            packets.add(new ContextPacket(
                    content,
                    parseTimestamp(note.updatedAt),
                    tokenCount,
                    0.75,  // 笔记具有较高相关性
                    meta
            ));
        }

        return packets;
    }

    // ==================== 自动保存笔记 ====================

    private void saveAsNote(String userInput, String response) {
        try {
            // 按关键词判断笔记类型
            String noteType;
            String lower = userInput.toLowerCase();
            if (lower.contains("问题") || lower.contains("阻塞") || lower.contains("错误")
                    || lower.contains("bug") || lower.contains("block")) {
                noteType = "blocker";
            } else if (lower.contains("计划") || lower.contains("下一步") || lower.contains("任务")
                    || lower.contains("plan") || lower.contains("todo")) {
                noteType = "action";
            } else {
                noteType = "conclusion";
            }

            String title = userInput.length() > 30 ? userInput.substring(0, 30) + "..." : userInput;
            String content = "## 问题\n" + userInput + "\n\n## 分析\n" + response;

            String id = noteTool.addNote(content, noteType,
                    List.of(projectName, "auto_generated"), title);

            System.out.println("[ProjectAssistant] 笔记已保存: " + id + " (类型=" + noteType + ")");

        } catch (Exception e) {
            System.out.println("[ProjectAssistant] 保存笔记失败: " + e.getMessage());
        }
    }

    // ==================== 系统指令 ====================

    private String buildSystemInstructions() {
        String base = systemPrompt;
        if (base != null && !base.isBlank()) return base;

        return "你是 " + projectName + " 项目的长期助手。\n\n"
                + "你的职责:\n"
                + "1. 基于历史笔记提供连贯的建议\n"
                + "2. 追踪项目进展和待解决问题\n"
                + "3. 在回答时引用相关的历史笔记\n"
                + "4. 提供具体、可操作的下一步建议\n\n"
                + "注意:\n"
                + "- 优先关注标记为 blocker 的问题\n"
                + "- 在建议中说明依据来源（笔记、记忆或知识库）\n"
                + "- 保持对项目整体进度的认识";
    }

    // ==================== 便捷方法 ====================

    /** 手动添加项目笔记 */
    public String addNote(String content, String type, List<String> tags, String title) {
        List<String> allTags = new ArrayList<>(tags != null ? tags : List.of());
        if (!allTags.contains(projectName)) allTags.add(0, projectName);
        return noteTool.addNote(content, type, allTags, title);
    }

    /** 查看笔记摘要 */
    public String getNoteSummary() {
        int total = noteTool.count();
        if (total == 0) return "📝 暂无项目笔记";

        Set<String> types = noteTool.listTypes();
        Set<String> allTags = noteTool.listTags();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 ").append(projectName).append(" 笔记摘要:\n");
        sb.append("  总数: ").append(total).append(" 条\n");
        sb.append("  类型: ").append(types).append("\n");
        sb.append("  标签: ").append(allTags).append("\n");

        sb.append("  最近笔记:\n");
        List<NoteTool.Note> recent = noteTool.listNotes();
        for (int i = 0; i < Math.min(5, recent.size()); i++) {
            NoteTool.Note note = recent.get(i);
            String preview = note.title.length() > 50 ? note.title.substring(0, 50) + "..." : note.title;
            sb.append("    - [").append(note.type).append("] ").append(preview)
                    .append(" (").append(note.updatedAt).append(")\n");
        }

        return sb.toString().trim();
    }

    /** 搜索项目笔记 */
    public List<NoteTool.Note> searchNotes(String query) {
        return noteTool.searchNotes(query);
    }

    /** 列出指定类型的笔记 */
    public List<NoteTool.Note> listNotesByType(String type) {
        return noteTool.listNotes(type, null);
    }

    /** 删除笔记 */
    public String deleteNote(String noteId) {
        return noteTool.deleteNote(noteId);
    }

    /** 重建笔记索引 */
    public String rebuildNoteIndex() {
        return noteTool.rebuildIndex();
    }

    // ==================== 访问器 ====================

    public NoteTool getNoteTool() { return noteTool; }
    public String getProjectName() { return projectName; }

    // ==================== 辅助 ====================

    private static LocalDateTime parseTimestamp(String iso) {
        try {
            return LocalDateTime.parse(iso, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
