package com.example.agent.store;

import org.neo4j.driver.*;
import java.util.*;

/**
 * Neo4jGraphStore 集成测试。
 * 需要 Neo4j 服务运行在 localhost:7687。
 * 启动方式：docker run -p 7687:7687 -p 7474:7474 -e NEO4J_AUTH=neo4j/password neo4j:5
 */
public class Neo4jGraphStoreTest {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== Neo4jGraphStore 集成测试 ===\n");

        // 检测 Neo4j 可用性
        boolean neo4jAvailable;
        try {
            Driver testDriver = GraphDatabase.driver("bolt://localhost:7687",
                    AuthTokens.basic("neo4j", "password"));
            try (Session s = testDriver.session()) {
                s.run("RETURN 1");
                neo4jAvailable = true;
            }
            testDriver.close();
        } catch (Exception e) {
            System.out.println("[SKIP] Neo4j 服务不可用 (bolt://localhost:7687)，跳过集成测试。");
            System.out.println("[HELP] docker run -p 7687:7687 -p 7474:7474 -e NEO4J_AUTH=neo4j/password neo4j:5");
            return;
        }

        testEntityCrud();
        testRelations();
        testEntityMemoryLinking();
        testNeighborTraversal();
        testUnlinkCascade();
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

    static void testEntityCrud() {
        System.out.println("--- 实体 CRUD 测试 ---");
        int prev = failures;
        Neo4jGraphStore store = new Neo4jGraphStore("bolt://localhost:7687", "neo4j", "password");
        store.clear();

        GraphStore.EntityData e1 = store.addEntity("Python", "language");
        check(e1 != null && e1.name.equals("Python"), "应返回 Python 实体");

        GraphStore.EntityData e2 = store.addEntity("Python", "language");
        check(e2.id.equals(e1.id), "同名同类型应去重");

        GraphStore.EntityData found = store.findEntityByName("Python", "language");
        check(found != null, "应该能按名查找");

        GraphStore.EntityData byId = store.getEntity(e1.id);
        check(byId != null && byId.name.equals("Python"), "应该能按 ID 获取");

        check(store.entityCount() >= 1, "至少应有 1 个实体");

        store.close();
        System.out.println("  CRUD: " + (failures == prev ? "PASS" : "FAIL"));
    }

    static void testRelations() {
        System.out.println("--- 关系测试 ---");
        int prev = failures;
        Neo4jGraphStore store = new Neo4jGraphStore("bolt://localhost:7687", "neo4j", "password");
        store.clear();

        GraphStore.EntityData python = store.addEntity("Python", "language");
        GraphStore.EntityData flask = store.addEntity("Flask", "framework");

        GraphStore.RelationData rel = store.addRelation(python.id, "HAS_FRAMEWORK", flask.id, "mem1");
        check(rel != null, "关系不应为 null");

        List<GraphStore.RelationData> outRels = store.getOutgoingRelations(python.id);
        check(outRels.size() == 1, "应有 1 条出边");

        List<GraphStore.RelationData> inRels = store.getIncomingRelations(flask.id);
        check(inRels.size() == 1, "应有 1 条入边");

        store.close();
        System.out.println("  关系: " + (failures == prev ? "PASS" : "FAIL"));
    }

    static void testEntityMemoryLinking() {
        System.out.println("--- 实体-记忆关联测试 ---");
        int prev = failures;
        Neo4jGraphStore store = new Neo4jGraphStore("bolt://localhost:7687", "neo4j", "password");
        store.clear();

        GraphStore.EntityData java = store.addEntity("Java", "language");
        store.linkEntityToMemory(java.id, "mem_java_1");
        store.linkEntityToMemory(java.id, "mem_java_2");

        Set<String> mems = store.getLinkedMemories(java.id);
        check(mems.size() == 2, "应关联 2 条记忆");
        check(mems.contains("mem_java_1"), "应包含 mem_java_1");

        Set<String> entities = store.getMemoryEntities("mem_java_1");
        check(entities.contains(java.id), "反向查找应包含该实体");

        store.close();
        System.out.println("  关联: " + (failures == prev ? "PASS" : "FAIL"));
    }

    static void testNeighborTraversal() {
        System.out.println("--- 邻居遍历测试 ---");
        int prev = failures;
        Neo4jGraphStore store = new Neo4jGraphStore("bolt://localhost:7687", "neo4j", "password");
        store.clear();

        GraphStore.EntityData java = store.addEntity("Java", "language");
        GraphStore.EntityData spring = store.addEntity("Spring", "framework");
        GraphStore.EntityData python = store.addEntity("Python", "language");

        store.addRelation(java.id, "HAS_FRAMEWORK", spring.id, "mem_rel");
        store.linkEntityToMemory(spring.id, "mem_spring_1");
        store.linkEntityToMemory(python.id, "mem_python_1");

        Set<String> neighbors = store.getNeighborMemories(java.id);
        check(neighbors.contains("mem_spring_1"), "应通过邻居找到 Spring 的记忆");

        store.close();
        System.out.println("  邻居遍历: " + (failures == prev ? "PASS" : "FAIL"));
    }

    static void testUnlinkCascade() {
        System.out.println("--- 级联清理测试 ---");
        int prev = failures;
        Neo4jGraphStore store = new Neo4jGraphStore("bolt://localhost:7687", "neo4j", "password");
        store.clear();

        GraphStore.EntityData go = store.addEntity("Go", "language");
        store.linkEntityToMemory(go.id, "mem_go");
        store.addRelation(go.id, "CREATED_BY", go.id, "mem_go");

        store.unlinkMemory("mem_go");

        check(store.getEntity(go.id) == null, "孤立实体应被级联删除");
        check(store.relationCount() == 0, "孤儿关系应被清理");

        store.close();
        System.out.println("  级联清理: " + (failures == prev ? "PASS" : "FAIL"));
    }

    static void testClear() {
        System.out.println("--- 清空测试 ---");
        int prev = failures;
        Neo4jGraphStore store = new Neo4jGraphStore("bolt://localhost:7687", "neo4j", "password");
        store.addEntity("Test", "type");
        store.clear();
        check(store.entityCount() == 0, "清空后实体应为0");
        store.close();
        System.out.println("  清空: " + (failures == prev ? "PASS" : "FAIL"));
    }
}
