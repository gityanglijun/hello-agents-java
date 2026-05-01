import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class WorkingMemory {

    // 配置
    private final int maxCapacity;
    private final int maxAgeMinutes;

    // 存储
    private final List<MemoryManager.MemoryItem> memories = new ArrayList<>();

    // TF-IDF 缓存（添加/删除后标记为脏）
    private Map<String, Integer> documentFrequency = new HashMap<>();
    private List<TfidfEntry> tfidfCache = new ArrayList<>();
    private boolean vectorsDirty = true;

    private static final double DECAY_FACTOR = 0.1;
    private static final double MIN_RECENCY_SCORE = 0.1;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public WorkingMemory() {
        this(50, 60);
    }

    public WorkingMemory(int maxCapacity, int maxAgeMinutes) {
        this.maxCapacity = maxCapacity;
        this.maxAgeMinutes = maxAgeMinutes;
    }

    // ==================== 添加 ====================

    public String add(MemoryManager.MemoryItem item) {
        expireOldMemories();

        if (memories.size() >= maxCapacity) {
            removeLowestPriorityMemory();
        }

        memories.add(item);
        vectorsDirty = true;
        return item.id;
    }

    // ==================== 检索 ====================

    public List<MemoryManager.MemoryItem> retrieve(String query, int limit) {
        expireOldMemories();

        if (memories.isEmpty()) return Collections.emptyList();

        // 尝试TF-IDF向量检索
        Map<String, Double> vectorScores = tryTfidfSearch(query);

        // 计算综合分数
        List<ScoredItem> scored = new ArrayList<>();
        for (MemoryManager.MemoryItem mem : memories) {
            double vectorScore = vectorScores.getOrDefault(mem.id, 0.0);
            double keywordScore = calculateKeywordScore(query, mem.content);

            double baseRelevance = vectorScore > 0
                    ? vectorScore * 0.7 + keywordScore * 0.3
                    : keywordScore;

            double timeDecay = calculateTimeDecay(mem.createdAt);
            double importanceWeight = 0.8 + (mem.importance * 0.4);
            double finalScore = baseRelevance * timeDecay * importanceWeight;

            if (finalScore > 0) {
                scored.add(new ScoredItem(finalScore, mem));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        int resultLimit = Math.min(limit > 0 ? limit : 5, scored.size());
        return scored.subList(0, resultLimit).stream()
                .map(s -> s.item)
                .collect(Collectors.toList());
    }

    // ==================== 过期 / 容量管理 ====================

    private void expireOldMemories() {
        LocalDateTime cutoff = LocalDateTime.now().minus(maxAgeMinutes, ChronoUnit.MINUTES);
        int before = memories.size();
        memories.removeIf(m -> {
            try {
                LocalDateTime created = LocalDateTime.parse(m.createdAt, ISO);
                return created.isBefore(cutoff);
            } catch (Exception e) {
                return false;
            }
        });
        if (memories.size() < before) {
            vectorsDirty = true;
        }
    }

    private void removeLowestPriorityMemory() {
        // 优先级 = importance（同分时优先删旧的）
        memories.sort(Comparator
                .comparingDouble((MemoryManager.MemoryItem m) -> m.importance)
                .thenComparing(m -> m.createdAt));
        memories.remove(0);
        vectorsDirty = true;
    }

    // ==================== TF-IDF 向量检索 ====================

    private Map<String, Double> tryTfidfSearch(String query) {
        try {
            if (vectorsDirty) rebuildTfidfVectors();

            Map<String, Double> queryVec = tfidfVector(query);
            if (queryVec.isEmpty()) return Collections.emptyMap();

            Map<String, Double> scores = new LinkedHashMap<>();
            for (TfidfEntry entry : tfidfCache) {
                double sim = cosineSimilarity(queryVec, entry.vector);
                if (sim > 0) {
                    scores.put(entry.docId, sim);
                }
            }
            return scores;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private void rebuildTfidfVectors() {
        documentFrequency.clear();
        tfidfCache.clear();

        // 第一遍：计算文档频率（DF）
        List<Map<String, Integer>> termFreqs = new ArrayList<>();
        for (MemoryManager.MemoryItem mem : memories) {
            Map<String, Integer> tf = termFrequency(mem.content);
            termFreqs.add(tf);
            for (String term : tf.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        // 第二遍：计算 TF-IDF 向量
        int totalDocs = memories.size();
        for (int i = 0; i < memories.size(); i++) {
            Map<String, Integer> tf = termFreqs.get(i);
            Map<String, Double> tfidf = new HashMap<>();
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String term = e.getKey();
                double tfVal = e.getValue();
                int df = documentFrequency.getOrDefault(term, 1);
                double idf = Math.log((double) totalDocs / (1 + df));
                tfidf.put(term, tfVal * idf);
            }
            tfidfCache.add(new TfidfEntry(memories.get(i).id, tfidf));
        }

        vectorsDirty = false;
    }

    private Map<String, Double> tfidfVector(String text) {
        Map<String, Integer> tf = termFrequency(text);
        if (tf.isEmpty()) return Collections.emptyMap();

        int totalDocs = Math.max(memories.size(), 1);
        Map<String, Double> vec = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            String term = e.getKey();
            double tfVal = e.getValue();
            int df = documentFrequency.getOrDefault(term, 1);
            double idf = Math.log((double) totalDocs / (1 + df));
            vec.put(term, tfVal * idf);
        }
        return vec;
    }

    /** 分词：混合中英文（空格切词 + 中文2-gram） */
    private Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> tf = new HashMap<>();
        if (text == null || text.isBlank()) return tf;

        String lower = text.toLowerCase();

        // 英文：提取连续字母/数字序列作为词
        StringBuilder wordBuf = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isAsciiWordChar(c)) {
                wordBuf.append(c);
            } else {
                if (wordBuf.length() > 0) {
                    tf.merge(wordBuf.toString(), 1, Integer::sum);
                    wordBuf.setLength(0);
                }
            }
        }
        if (wordBuf.length() > 0) {
            tf.merge(wordBuf.toString(), 1, Integer::sum);
        }

        // 中文：字符级 bigram
        StringBuilder chinese = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chinese.append(c);
            }
        }
        String ch = chinese.toString();
        for (int i = 0; i < ch.length() - 1; i++) {
            tf.merge(ch.substring(i, i + 2), 1, Integer::sum);
        }

        return tf;
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0, normA = 0, normB = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            double va = e.getValue();
            double vb = b.getOrDefault(e.getKey(), 0.0);
            dot += va * vb;
            normA += va * va;
        }
        for (double vb : b.values()) {
            normB += vb * vb;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    // ==================== 关键词匹配 ====================

    private double calculateKeywordScore(String query, String content) {
        if (query == null || query.isBlank()) return 0;
        String[] keywords = query.toLowerCase().split("\\s+");
        int matches = 0;
        String lower = content.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw)) matches++;
        }
        return (double) matches / keywords.length;
    }

    // ==================== 时间衰减 ====================

    private double calculateTimeDecay(String timestamp) {
        try {
            LocalDateTime memoryTime = LocalDateTime.parse(timestamp, ISO);
            long ageMinutes = ChronoUnit.MINUTES.between(memoryTime, LocalDateTime.now());
            double ageHours = ageMinutes / 60.0;
            double score = Math.exp(-DECAY_FACTOR * ageHours / 24);
            return Math.max(MIN_RECENCY_SCORE, score);
        } catch (Exception e) {
            return 0.5;
        }
    }

    // ==================== 辅助 ====================

    public int size() { return memories.size(); }
    public int getMaxCapacity() { return maxCapacity; }
    public int getMaxAgeMinutes() { return maxAgeMinutes; }

    public List<MemoryManager.MemoryItem> getAll() {
        expireOldMemories();
        return new ArrayList<>(memories);
    }

    public void clear() {
        memories.clear();
        documentFrequency.clear();
        tfidfCache.clear();
        vectorsDirty = true;
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

    private static class TfidfEntry {
        final String docId;
        final Map<String, Double> vector;
        TfidfEntry(String docId, Map<String, Double> vector) {
            this.docId = docId;
            this.vector = vector;
        }
    }
}
