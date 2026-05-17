package com.example.agent.store;

import java.util.*;

/**
 * 向量存储抽象。实现：InMemoryVectorStore（默认）、QdrantVectorStore。
 */
public interface VectorStore {

    // --- CRUD ---
    void add(String id, float[] vector);
    float[] get(String id);
    void remove(String id);
    void clear();

    // --- 检索 ---
    List<VectorHit> search(float[] queryVec, int topK);
    List<VectorHit> searchAll(float[] queryVec);

    // --- 访问器 ---
    int size();
    int dimension();
    Set<String> keys();
    Set<Map.Entry<String, float[]>> entrySet();
    boolean isEmpty();

    // --- 持久化（外部后端为 no-op） ---
    default void save() {}
    default void load() {}
    default String getPersistPath() { return null; }

    // --- 内部类型 ---
    final class VectorHit {
        public final String id;
        public final double score;
        public VectorHit(String id, double score) {
            this.id = id;
            this.score = score;
        }
    }

    // --- 向量工具 ---
    static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }
}
