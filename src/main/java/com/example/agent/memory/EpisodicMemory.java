package com.example.agent.memory;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class EpisodicMemory {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    // maxAgeDays=0 表示不限时间
    private static final int DEFAULT_MAX_AGE_DAYS = 0;

    // ==================== 内部类型 ====================

    public static class Episode {
        public final String episodeId;
        public final String sessionId;
        public final String timestamp;
        public final String content;
        public final Map<String, String> context;

        public Episode(String episodeId, String sessionId, String timestamp,
                       String content, Map<String, String> context) {
            this.episodeId = episodeId;
            this.sessionId = sessionId != null ? sessionId : "default";
            this.timestamp = timestamp;
            this.content = content;
            this.context = context != null ? new LinkedHashMap<>(context) : new LinkedHashMap<>();
        }
    }

    // ==================== 存储层 ====================

    // 模拟 SQLite 文档存储
    private final Map<String, Episode> docStore = new LinkedHashMap<>();

    // 会话索引: sessionId → [episodeId, ...]
    private final Map<String, List<String>> sessions = new LinkedHashMap<>();

    // 模拟 Qdrant 向量存储: episodeId → 向量
    private final Map<String, float[]> vectorStore = new LinkedHashMap<>();

    // 文本嵌入器
    private final TextEmbedder embedder;

    // ==================== 构造 ====================

    public EpisodicMemory() {
        this.embedder = new TextEmbedder(256);
    }

    public EpisodicMemory(int vectorDim) {
        this.embedder = new TextEmbedder(vectorDim);
    }

    /** 允许注入自定义嵌入器 */
    public EpisodicMemory(TextEmbedder embedder) {
        this.embedder = embedder != null ? embedder : new TextEmbedder(256);
    }

    // ==================== 添加 ====================

    public String add(MemoryManager.MemoryItem item) {
        // 创建情景对象
        Episode episode = new Episode(
                item.id,
                item.metadata.getOrDefault("session_id", "default"),
                item.createdAt,
                item.content,
                item.metadata
        );

        // 更新会话索引
        sessions.computeIfAbsent(episode.sessionId, k -> new ArrayList<>())
                .add(episode.episodeId);

        // 持久化存储
        docStore.put(episode.episodeId, episode);

        // 注册到向量器的词汇表（更新 DF 统计），再生成向量
        embedder.register(item.content);
        float[] vector = embedder.encode(item.content);
        vectorStore.put(item.id, vector);

        return item.id;
    }

    // ==================== 检索 ====================

    public List<MemoryManager.MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs) {
        // 1. 结构化预过滤
        Set<String> candidateIds = structuredFilter(kwargs);

        // 2. 向量语义检索
        List<VectorHit> hits = vectorSearch(query, limit * 5, kwargs);

        // 3. 综合评分与排序
        List<ScoredItem> results = new ArrayList<>();
        for (VectorHit hit : hits) {
            if (shouldInclude(hit, candidateIds, kwargs)) {
                double score = calculateEpisodeScore(hit);
                Episode ep = docStore.get(hit.episodeId);
                if (ep != null) {
                    results.add(new ScoredItem(score, toMemoryItem(ep)));
                }
            }
        }

        results.sort((a, b) -> Double.compare(b.score, a.score));

        int resultLimit = Math.min(limit > 0 ? limit : 5, results.size());
        return results.subList(0, resultLimit).stream()
                .map(s -> s.item)
                .collect(Collectors.toList());
    }

    public List<MemoryManager.MemoryItem> retrieve(String query, int limit) {
        return retrieve(query, limit, Collections.emptyMap());
    }

    // ==================== 结构化过滤 ====================

    private Set<String> structuredFilter(Map<String, Object> kwargs) {
        Set<String> ids = new LinkedHashSet<>(docStore.keySet());
        if (kwargs == null || kwargs.isEmpty()) return ids;

        // 时间范围过滤
        if (kwargs.containsKey("start_time")) {
            String start = kwargs.get("start_time").toString();
            ids.removeIf(id -> {
                Episode ep = docStore.get(id);
                return ep != null && ep.timestamp.compareTo(start) < 0;
            });
        }
        if (kwargs.containsKey("end_time")) {
            String end = kwargs.get("end_time").toString();
            ids.removeIf(id -> {
                Episode ep = docStore.get(id);
                return ep != null && ep.timestamp.compareTo(end) > 0;
            });
        }

        // 会话过滤
        if (kwargs.containsKey("session_id")) {
            String sid = kwargs.get("session_id").toString();
            List<String> sessionEpisodeIds = sessions.getOrDefault(sid, Collections.emptyList());
            ids.retainAll(sessionEpisodeIds);
        }

        // 重要性过滤
        if (kwargs.containsKey("min_importance")) {
            double minImp = toDouble(kwargs.get("min_importance"));
            ids.removeIf(id -> {
                Episode ep = docStore.get(id);
                if (ep == null) return true;
                double imp = toDouble(ep.context.getOrDefault("importance", "0.5"));
                return imp < minImp;
            });
        }

        return ids;
    }

    // ==================== 向量检索 ====================

    private List<VectorHit> vectorSearch(String query, int topK, Map<String, Object> kwargs) {
        float[] queryVec = embedder.encode(query);

        List<VectorHit> hits = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : vectorStore.entrySet()) {
            // 可选的 user_id 过滤（从 context 中）
            if (kwargs.containsKey("user_id")) {
                Episode ep = docStore.get(entry.getKey());
                if (ep == null || !kwargs.get("user_id").toString()
                        .equals(ep.context.get("user_id"))) {
                    continue;
                }
            }

            double sim = cosineSimilarity(queryVec, entry.getValue());
            if (sim > 0) {
                hits.add(new VectorHit(entry.getKey(), sim));
            }
        }

        hits.sort((a, b) -> Double.compare(b.score, a.score));
        int n = Math.min(topK, hits.size());
        return hits.subList(0, n);
    }

    // ==================== 评分 ====================

    private double calculateEpisodeScore(VectorHit hit) {
        double vecScore = hit.score;
        Episode ep = docStore.get(hit.episodeId);
        double recencyScore = calculateRecency(ep != null ? ep.timestamp : null);
        double importance = ep != null
                ? toDouble(ep.context.getOrDefault("importance", "0.5"))
                : 0.5;

        // (向量相似度 × 0.8 + 时间近因性 × 0.2) × 重要性权重
        double baseRelevance = vecScore * 0.8 + recencyScore * 0.2;
        double importanceWeight = 0.8 + (importance * 0.4);

        return baseRelevance * importanceWeight;
    }

    private double calculateRecency(String timestamp) {
        try {
            if (timestamp == null) return 0.5;
            LocalDateTime t = LocalDateTime.parse(timestamp, ISO);
            long ageHours = ChronoUnit.HOURS.between(t, LocalDateTime.now());
            // 与 WorkingMemory 近似的指数衰减，最低 0.1
            double score = Math.exp(-0.1 * ageHours / 24.0);
            return Math.max(0.1, score);
        } catch (Exception e) {
            return 0.5;
        }
    }

    // ==================== 过滤判断 ====================

    private boolean shouldInclude(VectorHit hit, Set<String> candidateIds,
                                   Map<String, Object> kwargs) {
        // 如果在结构化过滤中没有通过，排除
        if (!candidateIds.contains(hit.episodeId)) return false;

        Episode ep = docStore.get(hit.episodeId);
        if (ep == null) return false;

        // 额外过滤条件
        if (kwargs.containsKey("event_type")) {
            String wanted = kwargs.get("event_type").toString();
            String actual = ep.context.get("event_type");
            if (!wanted.equals(actual)) return false;
        }

        return true;
    }

    // ==================== 辅助 ====================

    private MemoryManager.MemoryItem toMemoryItem(Episode ep) {
        double importance = toDouble(ep.context.getOrDefault("importance", "0.5"));
        return new MemoryManager.MemoryItem(
                ep.episodeId, ep.content, "episodic", importance,
                new LinkedHashMap<>(ep.context), ep.timestamp
        );
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException e) {}
        }
        return 0.5;
    }

    // ==================== 公开访问器 ====================

    public int size() { return docStore.size(); }

    public List<Episode> getBySession(String sessionId) {
        List<String> episodeIds = sessions.getOrDefault(sessionId, Collections.emptyList());
        return episodeIds.stream().map(docStore::get).filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<String> listSessions() {
        return new ArrayList<>(sessions.keySet());
    }

    public void clear() {
        docStore.clear();
        sessions.clear();
        vectorStore.clear();
        embedder.reset();
    }

    // ==================== 文本嵌入器 ====================

    /**
     * 轻量级文本嵌入器，基于词汇表 + TF-IDF 权重 + 哈希压缩到固定维度。
     * 零外部依赖，适合 Java 纯内存场景。
     */
    public static class TextEmbedder {
        private final int dim;
        private final Map<String, Integer> documentFreq = new HashMap<>();
        private int docCount;

        public TextEmbedder(int dim) {
            this.dim = dim;
        }

        /** 对文本编码为固定维度向量 */
        public float[] encode(String text) {
            float[] vec = new float[dim];
            Map<String, Integer> tf = tokenize(text);
            if (tf.isEmpty()) return vec;

            int totalDocs = Math.max(docCount, 1);
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String term = e.getKey();
                int freq = e.getValue();
                int df = documentFreq.getOrDefault(term, 1);
                // ln(1 + totalDocs/(1+df)) 保证平滑：即使所有文档都有该词，IDF 也 > 0
                double idf = Math.log(1.0 + (double) totalDocs / (1 + df));
                double weight = freq * idf;

                // 哈希到固定维度（多槽位累加，减少碰撞）
                int slot = hashToSlot(term) % dim;
                vec[slot] += (float) weight;
                // 第二槽位
                int slot2 = ((slot * 31) + term.length()) % dim;
                vec[slot2] += (float) weight * 0.5f;
            }

            // L2 归一化
            double norm = 0;
            for (float v : vec) norm += v * v;
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dim; i++) vec[i] /= norm;
            }

            return vec;
        }

        /** 注册文档到词汇表（添加记忆时调用） */
        public void register(String text) {
            for (String term : tokenize(text).keySet()) {
                documentFreq.merge(term, 1, Integer::sum);
            }
            docCount++;
        }

        public void reset() {
            documentFreq.clear();
            docCount = 0;
        }

        private int hashToSlot(String term) {
            // 简单但分布均匀的哈希
            int h = 0;
            for (int i = 0; i < term.length(); i++) {
                h = h * 31 + term.charAt(i);
            }
            return Math.abs(h);
        }

        // ---- 分词（与 WorkingMemory 一致） ----

        private Map<String, Integer> tokenize(String text) {
            Map<String, Integer> tf = new HashMap<>();
            if (text == null || text.isBlank()) return tf;

            String lower = text.toLowerCase();

            // 英文词
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    buf.append(c);
                } else {
                    if (buf.length() > 0) {
                        tf.merge(buf.toString(), 1, Integer::sum);
                        buf.setLength(0);
                    }
                }
            }
            if (buf.length() > 0) tf.merge(buf.toString(), 1, Integer::sum);

            // 中文 bigram
            StringBuilder cn = new StringBuilder();
            for (char c : text.toCharArray()) {
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    cn.append(c);
                }
            }
            String ch = cn.toString();
            for (int i = 0; i < ch.length() - 1; i++) {
                tf.merge(ch.substring(i, i + 2), 1, Integer::sum);
            }

            return tf;
        }
    }

    // ==================== 内部类型 ====================

    private static class VectorHit {
        final String episodeId;
        final double score;
        VectorHit(String episodeId, double score) {
            this.episodeId = episodeId;
            this.score = score;
        }
    }

    private static class ScoredItem {
        final double score;
        final MemoryManager.MemoryItem item;
        ScoredItem(double score, MemoryManager.MemoryItem item) {
            this.score = score;
            this.item = item;
        }
    }
}
