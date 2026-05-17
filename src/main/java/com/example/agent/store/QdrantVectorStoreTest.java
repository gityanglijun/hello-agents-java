package com.example.agent.store;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * QdrantVectorStore 集成测试。
 * 需要 Qdrant 服务运行在 localhost:6333。
 * 启动方式：docker run -p 6333:6333 qdrant/qdrant
 */
public class QdrantVectorStoreTest {

    private static int failures = 0;

    // Qdrant 只接受 UUID 或正整数作为 point ID
    private static final String ID1 = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String ID2 = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String ID3 = "aaaaaaaa-0000-0000-0000-000000000003";

    public static void main(String[] args) {
        System.out.println("=== QdrantVectorStore 集成测试 ===\n");

        boolean qdrantAvailable;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:6333/collections"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            qdrantAvailable = resp.statusCode() == 200;
        } catch (Exception e) {
            System.out.println("[SKIP] Qdrant 服务不可用 (localhost:6333)，跳过集成测试。");
            System.out.println("[HELP] docker run -p 6333:6333 qdrant/qdrant");
            return;
        }

        if (!qdrantAvailable) {
            System.out.println("[SKIP] Qdrant 响应异常");
            return;
        }

        testCrud();
        testSearch();
        testClear();

        if (failures == 0) {
            System.out.println("\n=== 全部测试通过 ===");
        } else {
            System.out.println("\n=== " + failures + " 个测试失败 ===");
            System.exit(1);
        }
    }

    static void check(boolean cond, String msg) {
        if (!cond) {
            System.out.println("  FAIL: " + msg);
            failures++;
        }
    }

    static void testCrud() {
        System.out.println("--- CRUD 测试 ---");
        int prev = failures;
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", 4, "test_crud");

        float[] v1 = {1.0f, 0, 0, 0};
        float[] v2 = {0, 1.0f, 0, 0};

        store.add(ID1, v1);
        store.add(ID2, v2);

        check(store.size() >= 2, "应该有至少2条向量");

        float[] got = store.get(ID1);
        check(got != null, "应该能获取到 ID1");
        check(got != null && got.length == 4, "向量维度应为4");

        store.remove(ID1);
        check(store.get(ID1) == null, "删除后获取应为 null");

        store.clear();
        System.out.println("  CRUD: " + (failures == prev ? "PASS" : "FAIL"));
    }

    static void testSearch() {
        System.out.println("--- 搜索测试 ---");
        int prev = failures;
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", 4, "test_search");
        store.clear();

        float[] v1 = {1.0f, 0, 0, 0};
        float[] v2 = {0, 1.0f, 0, 0};
        float[] v3 = {0.7f, 0.7f, 0, 0};

        store.add(ID1, v1);
        store.add(ID2, v2);
        store.add(ID3, v3);

        List<VectorStore.VectorHit> hits = store.search(v1, 3);
        check(!hits.isEmpty(), "搜索应有结果");
        if (!hits.isEmpty()) {
            check(hits.get(0).id.equals(ID1), "最相似应为 ID1，实际: " + hits.get(0).id);
            check(hits.get(0).score > 0.9, "ID1 相似度应接近1，实际: " + hits.get(0).score);
        }

        System.out.println("  搜索结果: " + hits);
        System.out.println("  搜索: " + (failures == prev ? "PASS" : "FAIL"));

        store.clear();
    }

    static void testClear() {
        System.out.println("--- 清空测试 ---");
        int prev = failures;
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", 4, "test_clear");
        store.add(ID1, new float[]{1, 0, 0, 0});
        store.clear();
        check(store.size() == 0, "清空后大小应为0");
        System.out.println("  清空: " + (failures == prev ? "PASS" : "FAIL"));
    }
}
