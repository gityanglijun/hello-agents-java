package com.example.agent.rag;

import com.example.agent.Message;
import com.example.agent.memory.MemoryTool;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文构建器 — 从多个来源汇集候选信息包。
 *
 * 来源优先级：系统指令 > 记忆检索 > RAG 检索 > 对话历史 > 自定义包。
 * 后续阶段会对这些候选包进行过滤、排序和压缩。
 */
public class ContextBuilder {

    private final MemoryTool memoryTool;
    private final RAGTool ragTool;
    private final ContextConfig config;

    public ContextBuilder(MemoryTool memoryTool, RAGTool ragTool) {
        this(memoryTool, ragTool, ContextConfig.defaults());
    }

    public ContextBuilder(MemoryTool memoryTool, RAGTool ragTool, ContextConfig config) {
        this.memoryTool = memoryTool;
        this.ragTool = ragTool;
        this.config = config != null ? config : ContextConfig.defaults();
    }

    // ==================== 阶段4: 压缩 (compress) ====================

    /**
     * 压缩超限上下文。按分区（section）优先级依次保留，最后一个可容纳的分区做截断。
     *
     * @param context    原始结构化上下文
     * @param maxTokens  硬限制 token 数
     * @return 压缩后的上下文
     */
    public String compress(String context, int maxTokens) {
        int currentTokens = countTokens(context);

        if (currentTokens <= maxTokens) {
            return context; // 无需压缩
        }

        System.out.println("[ContextBuilder] 上下文超限 (" + currentTokens
                + " > " + maxTokens + ")，执行压缩");

        String[] sections = context.split("\n\n");
        List<String> kept = new ArrayList<>();
        int total = 0;

        for (String section : sections) {
            int sectionTokens = countTokens(section);

            if (total + sectionTokens <= maxTokens) {
                // 完整保留
                kept.add(section);
                total += sectionTokens;
            } else {
                // 部分保留：最后一个能塞进去的 section 做截断
                int remaining = maxTokens - total;
                if (remaining > 50) {
                    String truncated = truncateText(section, remaining);
                    kept.add(truncated + "\n[... 内容已压缩 ...]");
                }
                break;
            }
        }

        String result = String.join("\n\n", kept);
        System.out.println("[ContextBuilder] 压缩完成: " + currentTokens
                + " → " + countTokens(result) + " tokens");
        return result;
    }

    /**
     * 按估算的 chars-per-token 比例截断文本。
     */
    String truncateText(String text, int maxTokens) {
        int tokenCount = countTokens(text);
        double charPerToken = tokenCount > 0 ? (double) text.length() / tokenCount : 4.0;
        int maxChars = (int) (maxTokens * charPerToken);
        return text.substring(0, Math.min(maxChars, text.length()));
    }

    // ==================== 全流水线便捷入口 ====================

    /**
     * 一键执行完整流水线：gather → select → structure。
     *
     * @return 可直接送入 LLM 的结构化上下文字符串
     */
    public String build(String userQuery, List<Message> conversationHistory,
                        String systemInstructions, List<ContextPacket> customPackets) {
        // 阶段1: 汇集
        List<ContextPacket> all = gather(userQuery, conversationHistory, systemInstructions, customPackets);

        // 阶段2: 评分与选择
        int tokenBudget = config.effectiveMaxTokens();
        List<ContextPacket> selected = select(all, userQuery, tokenBudget);

        // 阶段3: 结构化
        String structured = structure(selected, userQuery);

        // 阶段4: 压缩（如需要）
        if (config.enableCompression()) {
            structured = compress(structured, tokenBudget);
        }

        return structured;
    }

    // ==================== 阶段1: 汇集 (gather) ====================

    /**
     * 从多个来源汇集候选 ContextPacket。
     *
     * @param userQuery           用户查询
     * @param conversationHistory 对话历史（可选）
     * @param systemInstructions  系统指令（可选，最高优先级）
     * @param customPackets       自定义信息包（可选）
     * @return 候选信息包列表
     */
    public List<ContextPacket> gather(
            String userQuery,
            List<Message> conversationHistory,
            String systemInstructions,
            List<ContextPacket> customPackets) {

        List<ContextPacket> packets = new ArrayList<>();

        // 1. 系统指令（最高优先级，不参与后续评分过滤）
        if (systemInstructions != null && !systemInstructions.isBlank()) {
            packets.add(new ContextPacket(
                    systemInstructions,
                    LocalDateTime.now(),
                    countTokens(systemInstructions),
                    1.0,  // 始终保留
                    mapOf("type", "system_instruction", "priority", "high")
            ));
        }

        // 2. 记忆系统检索
        if (memoryTool != null) {
            try {
                String memResult = memoryTool.execute("search",
                        "query", userQuery,
                        "limit", 10,
                        "min_importance", 0.3);
                packets.addAll(parseMemoryResults(memResult));
            } catch (Exception e) {
                System.out.println("[ContextBuilder] 记忆检索失败: " + e.getMessage());
            }
        }

        // 3. RAG 系统检索
        if (ragTool != null) {
            try {
                String ragResult = ragTool.run(Map.of(
                        "action", "search",
                        "query", userQuery,
                        "top_k", 15));
                packets.addAll(parseRagResults(ragResult));
            } catch (Exception e) {
                System.out.println("[ContextBuilder] RAG 检索失败: " + e.getMessage());
            }
        }

        // 4. 对话历史（最近 5 条）
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            int start = Math.max(0, conversationHistory.size() - 5);
            for (int i = start; i < conversationHistory.size(); i++) {
                Message msg = conversationHistory.get(i);
                packets.add(new ContextPacket(
                        msg.role() + ": " + msg.content(),
                        msg.timestamp(),
                        countTokens(msg.content()),
                        0.6,  // 历史消息基础相关性
                        mapOf("type", "conversation_history", "role", msg.role())
                ));
            }
        }

        // 5. 自定义信息包
        if (customPackets != null) {
            packets.addAll(customPackets);
        }

        System.out.println("[ContextBuilder] 汇集了 " + packets.size() + " 个候选信息包");
        return packets;
    }

    // ==================== 阶段2: 评分与选择 (score & select) ====================

    /**
     * 从候选信息包中选择最相关的内容，直到填满 token 预算。
     *
     * 流程：分离系统指令 → 计算综合分数(相关性×权重 + 新近性×权重)
     *      → 过滤低分 → 按分降序 → 贪心填充
     */
    public List<ContextPacket> select(List<ContextPacket> packets, String userQuery, int availableTokens) {
        // 1. 分离系统指令和其他信息
        List<ContextPacket> systemPackets = new ArrayList<>();
        List<ContextPacket> otherPackets = new ArrayList<>();
        for (ContextPacket p : packets) {
            if ("system_instruction".equals(p.metadata().get("type"))) {
                systemPackets.add(p);
            } else {
                otherPackets.add(p);
            }
        }

        // 2. 系统指令占用的 token
        int systemTokens = systemPackets.stream().mapToInt(ContextPacket::tokenCount).sum();
        int remainingTokens = availableTokens - systemTokens;

        if (remainingTokens <= 0) {
            System.out.println("[ContextBuilder] ⚠️ 系统指令已占满所有 token 预算");
            return systemPackets;
        }

        // 3. 为其他信息计算综合分数 (combined, packet) 并过滤
        List<ScoredPacket> scored = new ArrayList<>();
        for (ContextPacket packet : otherPackets) {
            // 默认分需要重新计算相关性
            double relevance = packet.relevanceScore();
            if (Math.abs(relevance - 0.5) < 0.001 || packet.metadata().containsKey("force_recalc")) {
                relevance = calculateRelevance(packet.content(), userQuery);
            }
            double recency = calculateRecency(packet.timestamp());

            double combinedScore = config.relevanceWeight() * relevance
                                 + config.recencyWeight() * recency;

            if (relevance >= config.minRelevance()) {
                scored.add(new ScoredPacket(combinedScore, packet));
            }
        }

        // 4. 按综合分数降序排序
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // 5. 贪心选择直到 token 预算用尽
        List<ContextPacket> selected = new ArrayList<>(systemPackets);
        int currentTokens = systemTokens;

        for (ScoredPacket sp : scored) {
            int need = sp.packet.tokenCount();
            if (currentTokens + need <= availableTokens) {
                selected.add(sp.packet);
                currentTokens += need;
            }
        }

        System.out.println("[ContextBuilder] 选择了 " + selected.size()
                + " 个信息包, 共 " + currentTokens + " tokens");
        return selected;
    }

    /**
     * Jaccard 相似度：内容词与查询词的交集/并集。
     * 中文走 jieba 分词，英文走空格分词。
     */
    double calculateRelevance(String content, String query) {
        Set<String> contentWords = tokenizeToSet(content.toLowerCase());
        Set<String> queryWords = tokenizeToSet(query.toLowerCase());

        if (queryWords.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(contentWords);
        intersection.retainAll(queryWords);

        Set<String> union = new HashSet<>(contentWords);
        union.addAll(queryWords);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * 指数衰减新近性：24 小时内高分，之后逐渐衰减，保底 0.1。
     */
    double calculateRecency(LocalDateTime timestamp) {
        if (timestamp == null) return 0.1;

        double ageHours = java.time.Duration.between(timestamp, LocalDateTime.now()).toSeconds() / 3600.0;
        double score = Math.exp(-0.1 * ageHours / 24.0);
        return Math.max(0.1, Math.min(1.0, score));
    }

    // ==================== 阶段3: 结构化组织 (structure) ====================

    /**
     * 将选中的信息包组织成结构化的上下文模板。
     *
     * 模板结构：
     *   [Role & Policies] — 系统指令（最高优先级）
     *   [Task]           — 用户查询
     *   [Evidence]       — RAG 检索到的文档证据
     *   [Context]        — 记忆、历史等辅助上下文
     *   [Output]         — 回答指令
     */
    public String structure(List<ContextPacket> selectedPackets, String userQuery) {
        List<String> systemInstructions = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        List<String> context = new ArrayList<>();

        for (ContextPacket packet : selectedPackets) {
            String type = packet.metadata().getOrDefault("type", "general").toString();

            switch (type) {
                case "system_instruction":
                    systemInstructions.add(packet.content());
                    break;
                case "rag":
                case "knowledge":
                    evidence.add(packet.content());
                    break;
                default:
                    context.add(packet.content());
                    break;
            }
        }

        List<String> sections = new ArrayList<>();

        // [Role & Policies]
        if (!systemInstructions.isEmpty()) {
            sections.add("[Role & Policies]\n" + String.join("\n", systemInstructions));
        }

        // [Task]
        sections.add("[Task]\n" + userQuery);

        // [Evidence]
        if (!evidence.isEmpty()) {
            sections.add("[Evidence]\n" + String.join("\n---\n", evidence));
        }

        // [Context]
        if (!context.isEmpty()) {
            sections.add("[Context]\n" + String.join("\n", context));
        }

        // [Output]
        sections.add("[Output]\n请基于以上信息，提供准确、有据的回答。");

        return String.join("\n\n", sections);
    }

    // ==================== 结果解析 ====================

    /**
     * 解析 MemoryTool search 的文本输出，转换为 ContextPacket 列表。
     * 输出格式: "🔍 找到 N 条相关记忆:\n1. [工作记忆] preview... (重要性: 0.90)\n..."
     */
    private List<ContextPacket> parseMemoryResults(String raw) {
        List<ContextPacket> packets = new ArrayList<>();
        if (raw == null || raw.isBlank()) return packets;

        // 匹配每行: "数字. [标签] 内容预览 (重要性: 0.XX)"
        Pattern p = Pattern.compile("^\\d+\\.\\s*\\[([^]]+)]\\s*(.+?)\\s*\\(重要性:\\s*(\\d+\\.?\\d*)\\)",
                Pattern.MULTILINE);
        Matcher m = p.matcher(raw);

        while (m.find()) {
            String type = m.group(1).strip();
            String content = m.group(2).strip();
            double importance = Double.parseDouble(m.group(3));

            packets.add(new ContextPacket(
                    "[" + type + "] " + content,
                    LocalDateTime.now(),
                    countTokens(content),
                    importance,  // 以记忆重要性作为相关性分数
                    mapOf("type", "memory", "memory_type", type)
            ));
        }

        // 兜底：如果正则没匹配到，整段作为一个包
        if (packets.isEmpty() && raw.contains("🔍 找到")) {
            packets.add(new ContextPacket(raw, LocalDateTime.now(), countTokens(raw), 0.5,
                    mapOf("type", "memory_raw")));
        }

        return packets;
    }

    /**
     * 解析 RAGTool search 的文本输出，转换为 ContextPacket 列表。
     * 输出格式: "--- 片段 N | 文档: xxx | 章节: yyy | 相关度: 0.8523 ---\ncontent..."
     */
    private List<ContextPacket> parseRagResults(String raw) {
        List<ContextPacket> packets = new ArrayList<>();
        if (raw == null || raw.isBlank()) return packets;

        // 按 "--- 片段" 分割
        String[] parts = raw.split("---\\s*片段\\s*\\d+");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].strip();

            // 提取元数据行: " | 文档: xxx | 章节: yyy | 相关度: 0.8523 ---"
            double score = 0.5;
            String heading = null;
            Matcher scoreM = Pattern.compile("相关度:\\s*(\\d+\\.?\\d*)").matcher(part);
            if (scoreM.find()) {
                score = Double.parseDouble(scoreM.group(1));
            }
            Matcher headingM = Pattern.compile("章节:\\s*([^|]+)").matcher(part);
            if (headingM.find()) {
                heading = headingM.group(1).strip();
            }

            // 提取正文: 元数据行之后的换行内容
            int contentStart = part.indexOf("---\n");
            if (contentStart < 0) contentStart = part.indexOf("---\r\n");
            String content = contentStart >= 0
                    ? part.substring(contentStart).replaceFirst("^---\\s*", "").strip()
                    : part.replaceFirst("^[^\\n]*\\n?", "").strip();

            if (!content.isBlank()) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("type", "rag");
                if (heading != null) meta.put("heading", heading);

                packets.add(new ContextPacket(content, LocalDateTime.now(),
                        countTokens(content), score, meta));
            }
        }

        return packets;
    }

    // ==================== 内部类型 ====================

    private static class ScoredPacket {
        final double score;
        final ContextPacket packet;
        ScoredPacket(double score, ContextPacket packet) { this.score = score; this.packet = packet; }
    }

    // ==================== 工具方法 ====================

    /** 简易 token 计数（中英文混合估算） */
    private static int countTokens(String text) {
        return MarkdownChunker.approxTokenLen(text);
    }

    /** 分词并去重，中文用 jieba，英文按空格/标点切分 */
    private static Set<String> tokenizeToSet(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return words;

        // 英文词（长度 ≥ 2）
        for (String w : text.split("[^a-z0-9]+")) {
            if (w.length() >= 2) words.add(w);
        }

        // 中文词：用 jieba
        try {
            Map<String, Integer> cn = com.example.agent.nlp.ChineseTokenizer.segmentWithFreq(text);
            for (String w : cn.keySet()) {
                if (w.length() >= 2) words.add(w.toLowerCase());
            }
        } catch (Exception ignored) {}

        return words;
    }

    private static <V> Map<String, V> mapOf(String k1, V v1, String k2, V v2) {
        Map<String, V> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static Map<String, Object> mapOf(String k1, Object v1) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        return map;
    }
}
