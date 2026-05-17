package com.example.agent.store;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.*;

/**
 * Neo4j 图存储实现。
 * 通过 Neo4j Java Driver (Bolt 协议) 进行实体和关系的增删查改。
 *
 * 环境要求：Neo4j 服务运行中（默认 bolt://localhost:7687）
 * 启动方式：docker run -p 7474:7474 -p 7687:7687 -e NEO4J_AUTH=neo4j/password neo4j:5
 *
 * 图模型：
 *   (:Entity {id, name, type})                      — 实体节点
 *   (:Memory {id})                                    — 记忆节点（内容存储在 DocumentStore）
 *   (e1)-[:RELATES_TO {id, predicate, memoryId}]->(e2) — 实体间关系
 *   (e)-[:MENTIONED_IN]->(m)                           — 实体-记忆关联
 */
public class Neo4jGraphStore implements GraphStore, AutoCloseable {

    private final Driver driver;
    private volatile boolean constraintsEnsured;

    public Neo4jGraphStore(String uri, String user, String password) {
        String actualUri = uri != null ? uri : "bolt://localhost:7687";
        String actualUser = user != null ? user : "neo4j";
        String actualPassword = password != null ? password : "password";
        this.driver = GraphDatabase.driver(actualUri, AuthTokens.basic(actualUser, actualPassword));
        this.constraintsEnsured = false;
    }

    // ==================== 约束与索引 ====================

    private void ensureConstraints() {
        if (constraintsEnsured) return;
        synchronized (this) {
            if (constraintsEnsured) return;
            try (Session session = driver.session()) {
                session.run("CREATE CONSTRAINT unique_entity_id IF NOT EXISTS FOR (e:Entity) REQUIRE e.id IS UNIQUE");
                session.run("CREATE INDEX entity_name_type IF NOT EXISTS FOR (e:Entity) ON (e.name, e.type)");
                constraintsEnsured = true;
            } catch (Exception e) {
                System.err.println("[Neo4jGraphStore] 创建约束/索引失败: " + e.getMessage());
            }
        }
    }

    // ==================== 实体 ====================

    @Override
    public EntityData addEntity(String name, String type) {
        EntityData existing = findEntityByName(name, type);
        if (existing != null) return existing;

        ensureConstraints();
        String id = UUID.randomUUID().toString();
        try (Session session = driver.session()) {
            Result result = session.run(
                "MERGE (e:Entity {name: $name, type: $type}) " +
                "ON CREATE SET e.id = $id " +
                "RETURN e.id AS id, e.name AS name, e.type AS type",
                Values.parameters("name", name, "type", type, "id", id));
            if (result.hasNext()) {
                Record r = result.next();
                return new EntityData(r.get("id").asString(),
                                      r.get("name").asString(),
                                      r.get("type").asString());
            }
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] addEntity 失败: " + e.getMessage());
        }
        return new EntityData(id, name, type);
    }

    @Override
    public EntityData findEntityByName(String name, String type) {
        try (Session session = driver.session()) {
            Result result = session.run(
                "MATCH (e:Entity {name: $name, type: $type}) RETURN e.id AS id, e.name AS name, e.type AS type",
                Values.parameters("name", name, "type", type));
            if (result.hasNext()) {
                Record r = result.next();
                return new EntityData(r.get("id").asString(),
                                      r.get("name").asString(),
                                      r.get("type").asString());
            }
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] findEntityByName 失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public EntityData getEntity(String entityId) {
        try (Session session = driver.session()) {
            Result result = session.run(
                "MATCH (e:Entity {id: $id}) RETURN e.id AS id, e.name AS name, e.type AS type",
                Values.parameters("id", entityId));
            if (result.hasNext()) {
                Record r = result.next();
                return new EntityData(r.get("id").asString(),
                                      r.get("name").asString(),
                                      r.get("type").asString());
            }
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] getEntity 失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<EntityData> getAllEntities() {
        List<EntityData> list = new ArrayList<>();
        try (Session session = driver.session()) {
            Result result = session.run(
                "MATCH (e:Entity) RETURN e.id AS id, e.name AS name, e.type AS type LIMIT 10000");
            while (result.hasNext()) {
                Record r = result.next();
                list.add(new EntityData(r.get("id").asString(),
                                        r.get("name").asString(),
                                        r.get("type").asString()));
            }
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] getAllEntities 失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public int entityCount() {
        try (Session session = driver.session()) {
            Result result = session.run("MATCH (e:Entity) RETURN count(e) AS cnt");
            if (result.hasNext()) return result.next().get("cnt").asInt();
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] entityCount 失败: " + e.getMessage());
        }
        return 0;
    }

    // ==================== 关系 ====================

    @Override
    public RelationData addRelation(String subjectId, String predicate, String objectId, String memoryId) {
        String id = UUID.randomUUID().toString();
        try (Session session = driver.session()) {
            session.run(
                "MATCH (s:Entity {id: $subjectId}), (o:Entity {id: $objectId}) " +
                "CREATE (s)-[r:RELATES_TO {id: $relId, predicate: $predicate, memoryId: $memoryId}]->(o)",
                Values.parameters(
                    "subjectId", subjectId, "objectId", objectId,
                    "relId", id, "predicate", predicate, "memoryId", memoryId != null ? memoryId : ""));
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] addRelation 失败: " + e.getMessage());
        }
        return new RelationData(id, subjectId, predicate, objectId, memoryId);
    }

    @Override
    public List<RelationData> getAllRelations() {
        List<RelationData> list = new ArrayList<>();
        try (Session session = driver.session()) {
            Result result = session.run(
                "MATCH (s:Entity)-[r:RELATES_TO]->(o:Entity) " +
                "RETURN r.id AS id, s.id AS subjectId, r.predicate AS predicate, o.id AS objectId, r.memoryId AS memoryId " +
                "LIMIT 10000");
            while (result.hasNext()) {
                Record rec = result.next();
                list.add(new RelationData(
                    rec.get("id").asString(),
                    rec.get("subjectId").asString(),
                    rec.get("predicate").asString(),
                    rec.get("objectId").asString(),
                    rec.containsKey("memoryId") ? rec.get("memoryId").asString("") : ""));
            }
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] getAllRelations 失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public int relationCount() {
        try (Session session = driver.session()) {
            Result result = session.run("MATCH ()-[r:RELATES_TO]->() RETURN count(r) AS cnt");
            if (result.hasNext()) return result.next().get("cnt").asInt();
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] relationCount 失败: " + e.getMessage());
        }
        return 0;
    }

    // ==================== 实体-记忆关联 ====================

    @Override
    public void linkEntityToMemory(String entityId, String memoryId) {
        try (Session session = driver.session()) {
            session.run(
                "MERGE (m:Memory {id: $memoryId}) " +
                "WITH m MATCH (e:Entity {id: $entityId}) " +
                "MERGE (e)-[:MENTIONED_IN]->(m)",
                Values.parameters("entityId", entityId, "memoryId", memoryId));
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] linkEntityToMemory 失败: " + e.getMessage());
        }
    }

    @Override
    public Set<String> getLinkedMemories(String entityId) {
        Set<String> result = new LinkedHashSet<>();
        try (Session session = driver.session()) {
            Result r = session.run(
                "MATCH (e:Entity {id: $entityId})-[:MENTIONED_IN]->(m:Memory) RETURN m.id AS id",
                Values.parameters("entityId", entityId));
            while (r.hasNext()) result.add(r.next().get("id").asString());
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] getLinkedMemories 失败: " + e.getMessage());
        }
        return result;
    }

    @Override
    public Set<String> getNeighborMemories(String entityId) {
        Set<String> result = new LinkedHashSet<>();
        try (Session session = driver.session()) {
            Result r = session.run(
                "MATCH (e:Entity {id: $entityId})-[:RELATES_TO]-(neighbor:Entity)-[:MENTIONED_IN]->(m:Memory) " +
                "RETURN DISTINCT m.id AS id",
                Values.parameters("entityId", entityId));
            while (r.hasNext()) result.add(r.next().get("id").asString());
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] getNeighborMemories 失败: " + e.getMessage());
        }
        return result;
    }

    @Override
    public Set<String> getMemoryEntities(String memoryId) {
        Set<String> result = new LinkedHashSet<>();
        try (Session session = driver.session()) {
            Result r = session.run(
                "MATCH (m:Memory {id: $memoryId})<-[:MENTIONED_IN]-(e:Entity) RETURN e.id AS id",
                Values.parameters("memoryId", memoryId));
            while (r.hasNext()) result.add(r.next().get("id").asString());
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] getMemoryEntities 失败: " + e.getMessage());
        }
        return result;
    }

    @Override
    public void unlinkMemory(String memoryId) {
        try (Session session = driver.session()) {
            // 删除记忆节点及其所有关系
            session.run("MATCH (m:Memory {id: $id}) DETACH DELETE m",
                        Values.parameters("id", memoryId));
            // 清理孤立实体（不再关联任何记忆的实体）
            session.run("MATCH (e:Entity) WHERE NOT (e)-[:MENTIONED_IN]->(:Memory) DETACH DELETE e");
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] unlinkMemory 失败: " + e.getMessage());
        }
    }

    // ==================== 遍历 ====================

    @Override
    public List<RelationData> getOutgoingRelations(String entityId) {
        List<RelationData> list = new ArrayList<>();
        try (Session session = driver.session()) {
            Result r = session.run(
                "MATCH (e:Entity {id: $id})-[rel:RELATES_TO]->(o:Entity) " +
                "RETURN rel.id AS id, e.id AS subjectId, rel.predicate AS predicate, o.id AS objectId, rel.memoryId AS memoryId",
                Values.parameters("id", entityId));
            while (r.hasNext()) {
                Record rec = r.next();
                list.add(new RelationData(
                    rec.get("id").asString(), rec.get("subjectId").asString(),
                    rec.get("predicate").asString(), rec.get("objectId").asString(),
                    rec.containsKey("memoryId") ? rec.get("memoryId").asString("") : ""));
            }
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] getOutgoingRelations 失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<RelationData> getIncomingRelations(String entityId) {
        List<RelationData> list = new ArrayList<>();
        try (Session session = driver.session()) {
            Result r = session.run(
                "MATCH (s:Entity)-[rel:RELATES_TO]->(e:Entity {id: $id}) " +
                "RETURN rel.id AS id, s.id AS subjectId, rel.predicate AS predicate, e.id AS objectId, rel.memoryId AS memoryId",
                Values.parameters("id", entityId));
            while (r.hasNext()) {
                Record rec = r.next();
                list.add(new RelationData(
                    rec.get("id").asString(), rec.get("subjectId").asString(),
                    rec.get("predicate").asString(), rec.get("objectId").asString(),
                    rec.containsKey("memoryId") ? rec.get("memoryId").asString("") : ""));
            }
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] getIncomingRelations 失败: " + e.getMessage());
        }
        return list;
    }

    // ==================== 生命周期 ====================

    @Override
    public void clear() {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] clear 失败: " + e.getMessage());
        }
        constraintsEnsured = false;
    }

    @Override
    public void close() {
        if (driver != null) {
            try { driver.close(); } catch (Exception ignored) {}
        }
    }
}
