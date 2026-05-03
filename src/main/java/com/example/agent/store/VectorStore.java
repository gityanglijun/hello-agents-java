package com.example.agent.store;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 向量存储 — 内存检索 + JSON 文件持久化。
 * 替代 HashMap<String, float[]>，支持余弦相似度搜索。
 */
public class VectorStore {

    private final Map<String, float[]> vectors = new LinkedHashMap<>();
    private final int dimension;
    private final Path persistPath;
    private static final Gson gson = new Gson();

    public VectorStore(int dimension) {
        this(dimension, null);
    }

    public VectorStore(int dimension, String persistPath) {
        this.dimension = dimension;
        this.persistPath = persistPath != null ? Path.of(persistPath) : null;
    }

    // ==================== CRUD ====================

    public void add(String id, float[] vector) {
        vectors.put(id, vector);
    }

    public float[] get(String id) {
        return vectors.get(id);
    }

    public void remove(String id) {
        vectors.remove(id);
    }

    // ==================== 检索 ====================

    public List<VectorHit> search(float[] queryVec, int topK) {
        List<VectorHit> hits = new ArrayList<>();
        for (Map.Entry<String, float[]> e : vectors.entrySet()) {
            double sim = cosine(queryVec, e.getValue());
            if (sim > 0) {
                hits.add(new VectorHit(e.getKey(), sim));
            }
        }

        hits.sort((a, b) -> Double.compare(b.score, a.score));
        int n = Math.min(topK > 0 ? topK : 5, hits.size());
        return hits.subList(0, n);
    }

    /** 返回所有结果（不做截断），由调用方自行过滤和排序 */
    public List<VectorHit> searchAll(float[] queryVec) {
        return search(queryVec, Integer.MAX_VALUE);
    }

    // ==================== 访问器 ====================

    public int size() { return vectors.size(); }
    public int dimension() { return dimension; }
    public Set<String> keys() { return new LinkedHashSet<>(vectors.keySet()); }
    public Set<Map.Entry<String, float[]>> entrySet() { return vectors.entrySet(); }
    public boolean isEmpty() { return vectors.isEmpty(); }

    public void clear() {
        vectors.clear();
    }

    // ==================== 持久化 ====================

    public void save() {
        if (persistPath == null) return;
        try {
            Files.createDirectories(persistPath.getParent());

            Map<String, List<Double>> data = new LinkedHashMap<>();
            for (Map.Entry<String, float[]> e : vectors.entrySet()) {
                List<Double> list = new ArrayList<>(e.getValue().length);
                for (float v : e.getValue()) list.add((double) v);
                data.put(e.getKey(), list);
            }

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("dimension", dimension);
            root.put("vectors", data);

            Files.writeString(persistPath, gson.toJson(root));
        } catch (IOException e) {
            System.err.println("[VectorStore] 保存失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void load() {
        if (persistPath == null || !Files.exists(persistPath)) return;
        try {
            String json = Files.readString(persistPath);
            Map<String, Object> root = gson.fromJson(json,
                    new TypeToken<Map<String, Object>>(){}.getType());

            Map<String, List<Double>> data = (Map<String, List<Double>>) root.get("vectors");
            if (data != null) {
                vectors.clear();
                for (Map.Entry<String, List<Double>> e : data.entrySet()) {
                    float[] vec = new float[e.getValue().size()];
                    for (int i = 0; i < vec.length; i++) {
                        vec[i] = e.getValue().get(i).floatValue();
                    }
                    vectors.put(e.getKey(), vec);
                }
            }
        } catch (Exception e) {
            System.err.println("[VectorStore] 加载失败: " + e.getMessage());
        }
    }

    /** 如果有持久化路径则返回路径字符串，否则返回 null */
    public String getPersistPath() {
        return persistPath != null ? persistPath.toString() : null;
    }

    // ==================== 向量工具 ====================

    public static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    // ==================== 内部类型 ====================

    public static class VectorHit {
        public final String id;
        public final double score;
        public VectorHit(String id, double score) {
            this.id = id;
            this.score = score;
        }
    }
}
