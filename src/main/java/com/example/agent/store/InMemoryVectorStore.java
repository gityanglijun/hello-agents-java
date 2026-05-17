package com.example.agent.store;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 向量存储 — 内存检索 + JSON 文件持久化。
 * 默认实现，适合原型开发和小规模数据。
 */
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, float[]> vectors = new LinkedHashMap<>();
    private final int dimension;
    private final Path persistPath;
    private static final Gson gson = new Gson();

    public InMemoryVectorStore(int dimension) {
        this(dimension, null);
    }

    public InMemoryVectorStore(int dimension, String persistPath) {
        this.dimension = dimension;
        this.persistPath = persistPath != null ? Path.of(persistPath) : null;
    }

    // ==================== CRUD ====================

    @Override
    public void add(String id, float[] vector) {
        vectors.put(id, vector);
    }

    @Override
    public float[] get(String id) {
        return vectors.get(id);
    }

    @Override
    public void remove(String id) {
        vectors.remove(id);
    }

    // ==================== 检索 ====================

    @Override
    public List<VectorHit> search(float[] queryVec, int topK) {
        List<VectorHit> hits = new ArrayList<>();
        for (Map.Entry<String, float[]> e : vectors.entrySet()) {
            double sim = VectorStore.cosine(queryVec, e.getValue());
            if (sim > 0) {
                hits.add(new VectorHit(e.getKey(), sim));
            }
        }

        hits.sort((a, b) -> Double.compare(b.score, a.score));
        int n = Math.min(topK > 0 ? topK : 5, hits.size());
        return hits.subList(0, n);
    }

    @Override
    public List<VectorHit> searchAll(float[] queryVec) {
        return search(queryVec, Integer.MAX_VALUE);
    }

    // ==================== 访问器 ====================

    @Override
    public int size() { return vectors.size(); }
    @Override
    public int dimension() { return dimension; }
    @Override
    public Set<String> keys() { return new LinkedHashSet<>(vectors.keySet()); }
    @Override
    public Set<Map.Entry<String, float[]>> entrySet() { return vectors.entrySet(); }
    @Override
    public boolean isEmpty() { return vectors.isEmpty(); }

    @Override
    public void clear() {
        vectors.clear();
    }

    // ==================== 持久化 ====================

    @Override
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
            System.err.println("[InMemoryVectorStore] 保存失败: " + e.getMessage());
        }
    }

    @Override
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
            System.err.println("[InMemoryVectorStore] 加载失败: " + e.getMessage());
        }
    }

    @Override
    public String getPersistPath() {
        return persistPath != null ? persistPath.toString() : null;
    }
}
