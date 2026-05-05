package com.example.agent.memory;

import com.example.agent.embedding.EmbedderProvider;
import com.example.agent.store.DocumentStore;
import com.example.agent.store.VectorStore;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class EpisodicMemory implements BaseMemory {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int DEFAULT_VECTOR_DIM = 256;

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

    private final DocumentStore docStore;
    private final boolean ownDocStore;
    private final VectorStore vectorStore;
    private final EmbedderProvider embedder;

    // ==================== 构造 ====================

    public EpisodicMemory() {
        this(new EmbedderProvider(DEFAULT_VECTOR_DIM), null, null);
    }

    public EpisodicMemory(int tfidfDim) {
        this(new EmbedderProvider(tfidfDim), null, null);
    }

    public EpisodicMemory(EmbedderProvider embedder) {
        this(embedder, null, null);
    }

    public EpisodicMemory(MemoryConfig config) {
        this(new EmbedderProvider(config.embedderFallbackDim), null, null);
    }

    /** 使用外部存储后端（持久化）。传 null 则使用默认内存存储 */
    public EpisodicMemory(EmbedderProvider embedder, DocumentStore docStore, VectorStore vectorStore) {
        this.embedder = embedder != null ? embedder : new EmbedderProvider(DEFAULT_VECTOR_DIM);
        this.docStore = docStore != null ? docStore : new DocumentStore(":memory:");
        this.ownDocStore = docStore == null;
        this.vectorStore = vectorStore != null ? vectorStore : new VectorStore(DEFAULT_VECTOR_DIM);
    }

    // ==================== 添加 ====================

    public String add(MemoryManager.MemoryItem item) {
        Episode episode = new Episode(
                item.id,
                item.metadata.getOrDefault("session_id", "default"),
                item.createdAt,
                item.content,
                item.metadata
        );

        // 文档存储
        docStore.insertEpisode(episode.episodeId, episode.sessionId,
                episode.timestamp, episode.content, episode.context);

        // 向量存储
        embedder.register(item.content);
        float[] vector = embedder.encode(item.content);
        vectorStore.add(item.id, vector);

        return item.id;
    }

    // ==================== 检索 ====================

    public List<MemoryManager.MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs) {
        // 1. 结构化预过滤
        Set<String> candidateIds = structuredFilter(kwargs);

        // 2. 向量语义检索
        List<VectorStore.VectorHit> hits = vectorSearch(query, limit * 5, kwargs);

        // 3. 综合评分与排序
        List<ScoredItem> results = new ArrayList<>();
        for (VectorStore.VectorHit hit : hits) {
            if (shouldInclude(hit.id, candidateIds, kwargs)) {
                double score = calculateEpisodeScore(hit);
                Map<String, String> raw = docStore.getEpisodeRaw(hit.id);
                if (raw != null) {
                    results.add(new ScoredItem(score, episodeToMemoryItem(raw)));
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
        List<Map<String, String>> all = docStore.getAllEpisodes();
        Set<String> ids = all.stream()
                .map(m -> m.get("episodeId"))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (kwargs == null || kwargs.isEmpty()) return ids;

        // 时间范围过滤
        if (kwargs.containsKey("start_time")) {
            String start = kwargs.get("start_time").toString();
            ids.removeIf(id -> {
                Map<String, String> raw = docStore.getEpisodeRaw(id);
                return raw != null && raw.get("timestamp").compareTo(start) < 0;
            });
        }
        if (kwargs.containsKey("end_time")) {
            String end = kwargs.get("end_time").toString();
            ids.removeIf(id -> {
                Map<String, String> raw = docStore.getEpisodeRaw(id);
                return raw != null && raw.get("timestamp").compareTo(end) > 0;
            });
        }

        // 会话过滤
        if (kwargs.containsKey("session_id")) {
            String sid = kwargs.get("session_id").toString();
            List<Map<String, String>> sessionEps = docStore.getEpisodesBySession(sid);
            Set<String> sessionIds = sessionEps.stream()
                    .map(m -> m.get("episodeId"))
                    .collect(Collectors.toSet());
            ids.retainAll(sessionIds);
        }

        // 重要性过滤
        if (kwargs.containsKey("min_importance")) {
            double minImp = toDouble(kwargs.get("min_importance"));
            ids.removeIf(id -> {
                Map<String, String> raw = docStore.getEpisodeRaw(id);
                if (raw == null) return true;
                Map<String, String> ctx = parseContext(raw.get("context"));
                double imp = toDouble(ctx.getOrDefault("importance", "0.5"));
                return imp < minImp;
            });
        }

        return ids;
    }

    // ==================== 向量检索 ====================

    private List<VectorStore.VectorHit> vectorSearch(String query, int topK, Map<String, Object> kwargs) {
        float[] queryVec = embedder.encode(query);
        List<VectorStore.VectorHit> allHits = vectorStore.search(queryVec, Math.max(topK, 50));

        // user_id 过滤
        if (kwargs.containsKey("user_id")) {
            String wantedUser = kwargs.get("user_id").toString();
            allHits.removeIf(hit -> {
                Map<String, String> raw = docStore.getEpisodeRaw(hit.id);
                if (raw == null) return true;
                Map<String, String> ctx = parseContext(raw.get("context"));
                return !wantedUser.equals(ctx.get("user_id"));
            });
        }

        int n = Math.min(topK, allHits.size());
        return allHits.subList(0, n);
    }

    // ==================== 评分 ====================

    private double calculateEpisodeScore(VectorStore.VectorHit hit) {
        double vecScore = hit.score;
        Map<String, String> raw = docStore.getEpisodeRaw(hit.id);
        String ts = raw != null ? raw.get("timestamp") : null;
        Map<String, String> ctx = raw != null ? parseContext(raw.get("context")) : Collections.emptyMap();
        double recencyScore = calculateRecency(ts);
        double importance = toDouble(ctx.getOrDefault("importance", "0.5"));

        double baseRelevance = vecScore * 0.8 + recencyScore * 0.2;
        double importanceWeight = 0.8 + (importance * 0.4);

        return baseRelevance * importanceWeight;
    }

    private double calculateRecency(String timestamp) {
        try {
            if (timestamp == null) return 0.5;
            LocalDateTime t = LocalDateTime.parse(timestamp, ISO);
            long ageHours = ChronoUnit.HOURS.between(t, LocalDateTime.now());
            double score = Math.exp(-0.1 * ageHours / 24.0);
            return Math.max(0.1, score);
        } catch (Exception e) {
            return 0.5;
        }
    }

    // ==================== 过滤判断 ====================

    private boolean shouldInclude(String episodeId, Set<String> candidateIds,
                                   Map<String, Object> kwargs) {
        if (!candidateIds.contains(episodeId)) return false;

        Map<String, String> raw = docStore.getEpisodeRaw(episodeId);
        if (raw == null) return false;

        if (kwargs.containsKey("event_type")) {
            Map<String, String> ctx = parseContext(raw.get("context"));
            String wanted = kwargs.get("event_type").toString();
            String actual = ctx.get("event_type");
            if (!wanted.equals(actual)) return false;
        }

        return true;
    }

    // ==================== 辅助 ====================

    private MemoryManager.MemoryItem episodeToMemoryItem(Map<String, String> raw) {
        Map<String, String> ctx = parseContext(raw.get("context"));
        double importance = toDouble(ctx.getOrDefault("importance", "0.5"));
        return new MemoryManager.MemoryItem(
                raw.get("episodeId"), raw.get("content"), "episodic", importance,
                new LinkedHashMap<>(ctx), raw.get("timestamp")
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseContext(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return new LinkedHashMap<>();
        try {
            return new com.google.gson.Gson().fromJson(json,
                    new com.google.gson.reflect.TypeToken<Map<String, String>>(){}.getType());
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException e) {}
        }
        return 0.5;
    }

    // ==================== 公开访问器 ====================

    public int size() { return docStore.countEpisodes(); }

    public List<Episode> getBySession(String sessionId) {
        List<Map<String, String>> raws = docStore.getEpisodesBySession(sessionId);
        return raws.stream().map(this::rawToEpisode).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public List<String> listSessions() {
        return docStore.listEpisodeSessions();
    }

    public void clear() {
        docStore.clearAll();
        vectorStore.clear();
    }

    /** 持久化向量存储 */
    public void saveVectors() { vectorStore.save(); }

    /** 关闭并释放资源 */
    public void close() {
        if (ownDocStore) docStore.close();
    }

    // ==================== Episode 转换 ====================

    private Episode rawToEpisode(Map<String, String> raw) {
        if (raw == null) return null;
        return new Episode(
                raw.get("episodeId"),
                raw.get("sessionId"),
                raw.get("timestamp"),
                raw.get("content"),
                parseContext(raw.get("context"))
        );
    }

    // ==================== 文本嵌入器 ====================

    public static class TextEmbedder {
        private final int dim;
        private final Map<String, Integer> documentFreq = new HashMap<>();
        private int docCount;

        public TextEmbedder(int dim) {
            this.dim = dim;
        }

        public int getDim() { return dim; }

        public float[] encode(String text) {
            float[] vec = new float[dim];
            Map<String, Integer> tf = tokenize(text);
            if (tf.isEmpty()) return vec;

            int totalDocs = Math.max(docCount, 1);
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String term = e.getKey();
                int freq = e.getValue();
                int df = documentFreq.getOrDefault(term, 1);
                double idf = Math.log(1.0 + (double) totalDocs / (1 + df));
                double weight = freq * idf;

                int slot = hashToSlot(term) % dim;
                vec[slot] += (float) weight;
                int slot2 = ((slot * 31) + term.length()) % dim;
                vec[slot2] += (float) weight * 0.5f;
            }

            double norm = 0;
            for (float v : vec) norm += v * v;
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dim; i++) vec[i] /= norm;
            }

            return vec;
        }

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
            int h = 0;
            for (int i = 0; i < term.length(); i++) {
                h = h * 31 + term.charAt(i);
            }
            return Math.abs(h);
        }

        private Map<String, Integer> tokenize(String text) {
            Map<String, Integer> tf = new LinkedHashMap<>();
            if (text == null || text.isBlank()) return tf;

            String lower = text.toLowerCase();

            // 英文词：连续字母/数字序列
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

            // 中文：jieba 分词（替代原有 bigram）
            try {
                java.util.Map<String, Integer> cnFreq = com.example.agent.nlp.ChineseTokenizer.segmentWithFreq(text);
                for (java.util.Map.Entry<String, Integer> e : cnFreq.entrySet()) {
                    tf.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            } catch (Exception e) {
                // jieba 不可用时降级为 unigram + bigram 混合
                StringBuilder cn = new StringBuilder();
                for (char c : text.toCharArray()) {
                    if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                        cn.append(c);
                    }
                }
                String ch = cn.toString();
                for (int i = 0; i < ch.length(); i++) {
                    tf.merge(String.valueOf(ch.charAt(i)), 1, Integer::sum);
                }
                for (int i = 0; i < ch.length() - 1; i++) {
                    tf.merge(ch.substring(i, i + 2), 1, Integer::sum);
                }
            }

            return tf;
        }
    }

    // ==================== 内部类型 ====================

    private static class ScoredItem {
        final double score;
        final MemoryManager.MemoryItem item;
        ScoredItem(double score, MemoryManager.MemoryItem item) {
            this.score = score;
            this.item = item;
        }
    }
}
