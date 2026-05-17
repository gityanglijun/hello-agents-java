package com.example.agent.store;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Qdrant 向量存储实现。
 * 通过 Qdrant REST API 进行向量的增删查改和相似度搜索。
 *
 * 环境要求：Qdrant 服务运行中（默认 http://localhost:6333）
 * 启动方式：docker run -p 6333:6333 qdrant/qdrant
 */
public class QdrantVectorStore implements VectorStore {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String collectionName;
    private final int dimension;
    private final Gson gson;
    private volatile boolean collectionEnsured;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public QdrantVectorStore(String qdrantUrl, int dimension, String collectionName) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.baseUrl = qdrantUrl != null ? qdrantUrl.replaceAll("/+$", "") : "http://localhost:6333";
        this.dimension = dimension;
        this.collectionName = collectionName != null ? collectionName : "default";
        this.gson = new Gson();
        this.collectionEnsured = false;
    }

    // ==================== Collection 管理 ====================

    private void ensureCollection() {
        if (collectionEnsured) return;
        synchronized (this) {
            if (collectionEnsured) return;
            try {
                // 检查 collection 是否存在
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/collections/" + collectionName))
                        .GET()
                        .timeout(TIMEOUT)
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 404) {
                    // 创建 collection
                    String body = String.format(
                        "{\"vectors\":{\"size\":%d,\"distance\":\"Cosine\"}}", dimension);
                    HttpRequest createReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/collections/" + collectionName))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(body))
                            .timeout(TIMEOUT)
                            .build();
                    HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
                    if (createResp.statusCode() == 200 || createResp.statusCode() == 201) {
                        System.out.println("[QdrantVectorStore] 创建 collection: " + collectionName + " (维度=" + dimension + ")");
                    } else {
                        System.err.println("[QdrantVectorStore] 创建 collection 失败: HTTP " + createResp.statusCode() + " " + createResp.body());
                    }
                }
                collectionEnsured = true;
            } catch (Exception e) {
                System.err.println("[QdrantVectorStore] 初始化 collection 失败: " + e.getMessage());
            }
        }
    }

    // ==================== CRUD ====================

    @Override
    public void add(String id, float[] vector) {
        ensureCollection();
        try {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", id);
            point.put("vector", floatsToList(vector));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("points", List.of(point));

            sendRequest("PUT", "/collections/" + collectionName + "/points", payload);
        } catch (Exception e) {
            System.err.println("[QdrantVectorStore] add 失败: " + e.getMessage());
        }
    }

    @Override
    public float[] get(String id) {
        ensureCollection();
        try {
            String json = sendGet("/collections/" + collectionName + "/points/" + id);
            if (json == null) return null;
            Map<String, Object> resp = gson.fromJson(json, Map.class);
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            if (result != null) {
                List<Double> vecList = (List<Double>) result.get("vector");
                if (vecList != null) return doublesToFloats(vecList);
            }
        } catch (Exception e) {
            System.err.println("[QdrantVectorStore] get 失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void remove(String id) {
        ensureCollection();
        try {
            Map<String, Object> payload = Map.of("points", List.of(id));
            sendRequest("POST", "/collections/" + collectionName + "/points/delete", payload);
        } catch (Exception e) {
            System.err.println("[QdrantVectorStore] remove 失败: " + e.getMessage());
        }
    }

    @Override
    public void clear() {
        try {
            sendRequest("DELETE", "/collections/" + collectionName, null);
            collectionEnsured = false;
        } catch (Exception e) {
            System.err.println("[QdrantVectorStore] clear 失败: " + e.getMessage());
        }
    }

    // ==================== 检索 ====================

    @Override
    public List<VectorHit> search(float[] queryVec, int topK) {
        ensureCollection();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("vector", floatsToList(queryVec));
            payload.put("limit", topK > 0 ? topK : 5);
            payload.put("with_payload", false);
            payload.put("with_vector", false);

            String json = sendRequest("POST", "/collections/" + collectionName + "/points/search", payload);
            if (json == null) return Collections.emptyList();

            Map<String, Object> resp = gson.fromJson(json, Map.class);
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("result");
            if (results == null) return Collections.emptyList();

            List<VectorHit> hits = new ArrayList<>();
            for (Map<String, Object> r : results) {
                Object idObj = r.get("id");
                String hitId = idObj != null ? idObj.toString() : null;
                Double score = r.get("score") instanceof Number n ? n.doubleValue() : 0.0;
                if (hitId != null && score > 0) {
                    hits.add(new VectorHit(hitId, score));
                }
            }
            return hits;
        } catch (Exception e) {
            System.err.println("[QdrantVectorStore] search 失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<VectorHit> searchAll(float[] queryVec) {
        return search(queryVec, 1000);
    }

    // ==================== 访问器 ====================

    @Override
    public int size() {
        ensureCollection();
        try {
            // /collections/{name} 返回 result.points_count
            String json = sendGet("/collections/" + collectionName);
            if (json == null) return 0;
            Map<String, Object> resp = gson.fromJson(json, Map.class);
            Map<String, Object> result = (Map<String, Object>) resp.get("result");
            if (result != null && result.get("points_count") instanceof Number n) {
                return n.intValue();
            }
        } catch (Exception e) {
            System.err.println("[QdrantVectorStore] size 失败: " + e.getMessage());
        }
        return 0;
    }

    @Override
    public int dimension() { return dimension; }

    @Override
    public Set<String> keys() {
        ensureCollection();
        Set<String> allKeys = new LinkedHashSet<>();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("limit", 10000);
            payload.put("with_payload", false);
            payload.put("with_vector", false);

            String json = sendRequest("POST", "/collections/" + collectionName + "/points/scroll", payload);
            if (json != null) {
                Map<String, Object> resp = gson.fromJson(json, Map.class);
                Map<String, Object> result = (Map<String, Object>) resp.get("result");
                if (result != null) {
                    List<Map<String, Object>> points = (List<Map<String, Object>>) result.get("points");
                    if (points != null) {
                        for (Map<String, Object> p : points) {
                            Object idObj = p.get("id");
                            if (idObj != null) allKeys.add(idObj.toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[QdrantVectorStore] keys 失败: " + e.getMessage());
        }
        return allKeys;
    }

    @Override
    public Set<Map.Entry<String, float[]>> entrySet() {
        throw new UnsupportedOperationException(
            "QdrantVectorStore 不支持 entrySet()。外部向量数据库不适合全量加载到内存。");
    }

    @Override
    public boolean isEmpty() { return size() == 0; }

    // ==================== HTTP 工具 ====================

    private String sendGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .timeout(TIMEOUT)
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    private String sendRequest(String method, String path, Map<String, Object> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(TIMEOUT);

        if (payload != null) {
            String body = gson.toJson(payload);
            builder.header("Content-Type", "application/json")
                   .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            System.err.println("[QdrantVectorStore] HTTP " + method + " " + path + " → " + resp.statusCode());
            return null;
        }
        return resp.body();
    }

    private static List<Double> floatsToList(float[] vec) {
        List<Double> list = new ArrayList<>(vec.length);
        for (float v : vec) list.add((double) v);
        return list;
    }

    private static float[] doublesToFloats(List<Double> list) {
        float[] vec = new float[list.size()];
        for (int i = 0; i < vec.length; i++) vec[i] = list.get(i).floatValue();
        return vec;
    }
}
