package com.example.agent.memory;

import com.example.agent.store.DocumentStore;
import com.example.agent.store.VectorStore;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 感知记忆实现 — 使用 VectorStore + DocumentStore 作为存储后端。
 * 支持多模态（文本/图像/音频），按模态分离向量存储。
 */
public class PerceptualMemory implements BaseMemory {

    public static final String MOD_TEXT = "text";
    public static final String MOD_IMAGE = "image";
    public static final String MOD_AUDIO = "audio";
    public static final List<String> ALL_MODALITIES = List.of(MOD_TEXT, MOD_IMAGE, MOD_AUDIO);

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final double DECAY_FACTOR = 0.1;
    private static final double MIN_RECENCY = 0.1;

    // ==================== 存储层 ====================

    private final Map<String, VectorStore> vectorStores = new LinkedHashMap<>();
    private final DocumentStore docStore;
    private final boolean ownDocStore;
    private final EpisodicMemory.TextEmbedder textEmbedder;
    private final int imageDim;
    private final int audioDim;

    // ==================== 构造 ====================

    public PerceptualMemory() {
        this(256, 512, 512);
    }

    public PerceptualMemory(int textDim, int imageDim, int audioDim) {
        this(textDim, imageDim, audioDim, null, null, null, null);
    }

    public PerceptualMemory(MemoryConfig config) {
        this(config.perceptualTextDim, config.perceptualImageDim, config.perceptualAudioDim,
                null, null, null, null);
    }

    /** 使用外部存储后端（持久化）。传 null 则使用默认内存存储 */
    public PerceptualMemory(int textDim, int imageDim, int audioDim,
                            DocumentStore docStore,
                            VectorStore textVecStore, VectorStore imageVecStore, VectorStore audioVecStore) {
        this.textEmbedder = new EpisodicMemory.TextEmbedder(textDim);
        this.imageDim = imageDim;
        this.audioDim = audioDim;

        this.docStore = docStore != null ? docStore : new DocumentStore(":memory:");
        this.ownDocStore = docStore == null;

        this.vectorStores.put(MOD_TEXT, textVecStore != null ? textVecStore : new VectorStore(textDim));
        this.vectorStores.put(MOD_IMAGE, imageVecStore != null ? imageVecStore : new VectorStore(imageDim));
        this.vectorStores.put(MOD_AUDIO, audioVecStore != null ? audioVecStore : new VectorStore(audioDim));
    }

    // ==================== 添加 ====================

    public String add(MemoryManager.MemoryItem item) {
        String modality = item.metadata.getOrDefault("modality", MOD_TEXT);
        String filePath = item.metadata.getOrDefault("raw_data", null);

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

        vectorStores.get(modality).add(item.id, vector);
        docStore.insertMemoryItem(item);
        return item.id;
    }

    // ==================== 检索 ====================

    public List<MemoryManager.MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs) {
        String userId = kwargs != null ? (String) kwargs.get("user_id") : null;
        String targetModality = kwargs != null ? (String) kwargs.get("target_modality") : null;
        String queryModality = kwargs != null ? (String) kwargs.get("query_modality") : null;
        if (queryModality == null) queryModality = targetModality != null ? targetModality : MOD_TEXT;

        String searchModality = targetModality != null ? targetModality : queryModality;
        VectorStore store = vectorStores.getOrDefault(searchModality, vectorStores.get(MOD_TEXT));

        float[] queryVector = encodeData(query, queryModality);
        List<VectorStore.VectorHit> hits = store.search(queryVector, limit * 5);

        // 过滤
        List<VectorStore.VectorHit> filteredHits = new ArrayList<>();
        for (VectorStore.VectorHit hit : hits) {
            MemoryManager.MemoryItem item = docStore.getMemoryItem(hit.id);
            if (item == null) continue;

            if (userId != null && !userId.equals(item.metadata.get("user_id"))) continue;

            if (targetModality != null) {
                String itemModality = item.metadata.getOrDefault("modality", MOD_TEXT);
                if (!targetModality.equals(itemModality)) continue;
            }

            filteredHits.add(hit);
        }

        // 融合排序
        List<ScoredItem> results = new ArrayList<>();
        for (VectorStore.VectorHit hit : filteredHits) {
            MemoryManager.MemoryItem item = docStore.getMemoryItem(hit.id);
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

    private float[] encodeImage(String input) {
        float[] vec = new float[imageDim];
        fillFromHash(vec, "img:" + input);
        return normalize(vec);
    }

    private float[] encodeAudio(String input) {
        float[] vec = new float[audioDim];
        fillFromHash(vec, "aud:" + input);
        return normalize(vec);
    }

    private static void fillFromHash(float[] vec, String seed) {
        int h = seed.hashCode();
        Random rng = new Random(h);
        for (int i = 0; i < vec.length; i++) {
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

    // ==================== 公开访问器 ====================

    public int size() { return docStore.countMemoryItems(); }

    public int sizeByModality(String modality) {
        VectorStore store = vectorStores.get(modality);
        return store != null ? store.size() : 0;
    }

    public Map<String, Integer> modalityCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String mod : ALL_MODALITIES) {
            int c = vectorStores.getOrDefault(mod, new VectorStore(0)).size();
            if (c > 0) counts.put(mod, c);
        }
        return counts;
    }

    public void clear() {
        for (VectorStore store : vectorStores.values()) store.clear();
        docStore.clearAll();
        textEmbedder.reset();
    }

    /** 持久化所有向量存储 */
    public void save() {
        for (VectorStore vs : vectorStores.values()) vs.save();
    }

    public void close() {
        if (ownDocStore) docStore.close();
    }

    // ==================== 内部类型 ====================

    private static class ScoredItem {
        final double score;
        final MemoryManager.MemoryItem item;
        ScoredItem(double score, MemoryManager.MemoryItem item) { this.score = score; this.item = item; }
    }
}
