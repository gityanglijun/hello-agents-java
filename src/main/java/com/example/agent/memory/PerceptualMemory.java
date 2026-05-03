package com.example.agent.memory;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 感知记忆实现
 *
 * 特点：
 * - 支持多模态（文本/图像/音频），按模态分离向量存储
 * - 同模态向量检索 + 时间/重要性融合排序
 * - 评分: (vec×0.8 + recency×0.2) × importanceWeight
 */
public class PerceptualMemory {

    // ==================== 模态常量 ====================

    public static final String MOD_TEXT = "text";
    public static final String MOD_IMAGE = "image";
    public static final String MOD_AUDIO = "audio";
    public static final List<String> ALL_MODALITIES = List.of(MOD_TEXT, MOD_IMAGE, MOD_AUDIO);

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final double DECAY_FACTOR = 0.1;
    private static final double MIN_RECENCY = 0.1;

    // ==================== 存储层 ====================

    // 按模态分离的向量存储: modality → (memoryId → vector)
    private final Map<String, Map<String, float[]>> vectorStores = new LinkedHashMap<>();

    // 记忆缓存
    private final Map<String, MemoryManager.MemoryItem> memoryStore = new LinkedHashMap<>();

    // 文本嵌入器
    private final EpisodicMemory.TextEmbedder textEmbedder;

    private final int imageDim;
    private final int audioDim;

    // ==================== 构造 ====================

    public PerceptualMemory() {
        this(256, 512, 512);
    }

    public PerceptualMemory(int textDim, int imageDim, int audioDim) {
        this.textEmbedder = new EpisodicMemory.TextEmbedder(textDim);
        this.imageDim = imageDim;
        this.audioDim = audioDim;
        for (String mod : ALL_MODALITIES) {
            vectorStores.put(mod, new LinkedHashMap<>());
        }
    }

    // ==================== 添加 ====================

    public String add(MemoryManager.MemoryItem item) {
        String modality = item.metadata.getOrDefault("modality", MOD_TEXT);
        String filePath = item.metadata.getOrDefault("raw_data", null);

        // 如果指定了 filePath 但未指定 modality，从文件扩展名推断
        if (filePath != null && !item.metadata.containsKey("modality")) {
            modality = inferModality(filePath);
        }

        float[] vector;
        switch (modality) {
            case MOD_IMAGE:
                vector = encodeImage(filePath != null ? filePath : item.content);
                break;
            case MOD_AUDIO:
                vector = encodeAudio(filePath != null ? filePath : item.content);
                break;
            case MOD_TEXT:
            default:
                textEmbedder.register(item.content);
                vector = textEmbedder.encode(item.content);
                break;
        }

        vectorStores.get(modality).put(item.id, vector);
        memoryStore.put(item.id, item);
        return item.id;
    }

    // ==================== 检索 ====================

    public List<MemoryManager.MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs) {
        String userId = kwargs != null ? (String) kwargs.get("user_id") : null;
        String targetModality = kwargs != null ? (String) kwargs.get("target_modality") : null;
        String queryModality = kwargs != null ? (String) kwargs.get("query_modality") : null;
        if (queryModality == null) queryModality = targetModality != null ? targetModality : MOD_TEXT;

        // 确定查询向量所属的存储
        String searchModality = targetModality != null ? targetModality : queryModality;
        Map<String, float[]> store = vectorStores.getOrDefault(searchModality, Collections.emptyMap());

        // 同模态向量检索
        float[] queryVector = encodeData(query, queryModality);
        List<VectorHit> hits = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : store.entrySet()) {
            MemoryManager.MemoryItem item = memoryStore.get(entry.getKey());
            if (item == null) continue;

            // userId 过滤
            if (userId != null && !userId.equals(item.metadata.get("user_id"))) continue;

            // target_modality 二次过滤
            if (targetModality != null) {
                String itemModality = item.metadata.getOrDefault("modality", MOD_TEXT);
                if (!targetModality.equals(itemModality)) continue;
            }

            double sim = cosine(queryVector, entry.getValue());
            if (sim > 0) {
                hits.add(new VectorHit(entry.getKey(), sim));
            }
        }

        hits.sort((a, b) -> Double.compare(b.score, a.score));
        int topK = Math.min(limit * 5, hits.size());
        if (topK == 0) return Collections.emptyList();
        hits = hits.subList(0, topK);

        // 融合排序
        List<ScoredItem> results = new ArrayList<>();
        for (VectorHit hit : hits) {
            MemoryManager.MemoryItem item = memoryStore.get(hit.memoryId);
            if (item == null) continue;

            double vectorScore = hit.score;
            double recencyScore = calculateRecency(item.createdAt);
            double importance = item.importance;

            double baseRelevance = vectorScore * 0.8 + recencyScore * 0.2;
            double importanceWeight = 0.8 + (importance * 0.4);
            double combinedScore = baseRelevance * importanceWeight;

            results.add(new ScoredItem(combinedScore, item));
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

    // ==================== 编码器 ====================

    private float[] encodeData(String data, String modality) {
        switch (modality) {
            case MOD_IMAGE:  return encodeImage(data);
            case MOD_AUDIO:  return encodeAudio(data);
            case MOD_TEXT:
            default:         return textEmbedder.encode(data);
        }
    }

    /**
     * 图像编码（占位实现：从路径/内容生成确定性向量）。
     * 生产环境可替换为 CLIP 模型调用。
     */
    private float[] encodeImage(String input) {
        float[] vec = new float[imageDim];
        fillFromHash(vec, "img:" + input);
        return normalize(vec);
    }

    /**
     * 音频编码（占位实现：从路径/内容生成确定性向量）。
     * 生产环境可替换为 CLAP 模型调用。
     */
    private float[] encodeAudio(String input) {
        float[] vec = new float[audioDim];
        fillFromHash(vec, "aud:" + input);
        return normalize(vec);
    }

    /** 从字符串哈希生成向量的各维值（确定性，相同输入总产出相同向量） */
    private static void fillFromHash(float[] vec, String seed) {
        int h = seed.hashCode();
        Random rng = new Random(h);
        for (int i = 0; i < vec.length; i++) {
            // 多轮哈希减少相邻维度的相关性
            rng = new Random(rng.nextLong());
            vec[i] = (float) (rng.nextGaussian());
        }
    }

    private static float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        }
        return vec;
    }

    // ==================== 时间衰减 ====================

    private double calculateRecency(String timestamp) {
        try {
            if (timestamp == null) return MIN_RECENCY;
            LocalDateTime t = LocalDateTime.parse(timestamp, ISO);
            long ageHours = ChronoUnit.HOURS.between(t, LocalDateTime.now());
            double score = Math.exp(-DECAY_FACTOR * ageHours / 24.0);
            return Math.max(MIN_RECENCY, score);
        } catch (Exception e) {
            return 0.5;
        }
    }

    // ==================== 模态推断 ====================

    public static String inferModality(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.matches(".*\\.(png|jpg|jpeg|gif|bmp|webp|svg|tiff)$")) return MOD_IMAGE;
        if (lower.matches(".*\\.(mp4|avi|mov|mkv|webm|flv)$")) return MOD_IMAGE;
        if (lower.matches(".*\\.(mp3|wav|ogg|flac|aac|m4a)$")) return MOD_AUDIO;
        return MOD_TEXT;
    }

    // ==================== 向量工具 ====================

    private double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    // ==================== 公开访问器 ====================

    public int size() { return memoryStore.size(); }

    public int sizeByModality(String modality) {
        Map<String, float[]> store = vectorStores.get(modality);
        return store != null ? store.size() : 0;
    }

    public Map<String, Integer> modalityCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String mod : ALL_MODALITIES) {
            int c = vectorStores.getOrDefault(mod, Collections.emptyMap()).size();
            if (c > 0) counts.put(mod, c);
        }
        return counts;
    }

    public void clear() {
        for (Map<String, float[]> store : vectorStores.values()) store.clear();
        memoryStore.clear();
        textEmbedder.reset();
    }

    // ==================== 内部类型 ====================

    private static class VectorHit {
        final String memoryId;
        final double score;
        VectorHit(String memoryId, double score) { this.memoryId = memoryId; this.score = score; }
    }

    private static class ScoredItem {
        final double score;
        final MemoryManager.MemoryItem item;
        ScoredItem(double score, MemoryManager.MemoryItem item) { this.score = score; this.item = item; }
    }
}
