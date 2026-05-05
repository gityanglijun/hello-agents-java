package com.example.agent.store;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 图存储 — JGraphT 有向图 + JSON 文件持久化。
 * 替代内存 HashMap<Entity> + List<Relation>，支持一跳邻居查询。
 */
public class GraphStore {

    // ==================== 数据类 ====================

    public static class EntityData {
        public String id;
        public String name;
        public String type;

        public EntityData() {}
        public EntityData(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EntityData)) return false;
            EntityData e = (EntityData) o;
            return Objects.equals(name, e.name) && Objects.equals(type, e.type);
        }

        @Override
        public int hashCode() { return Objects.hash(name, type); }
    }

    public static class RelationData {
        public String id;
        public String subjectId;
        public String predicate;
        public String objectId;
        public String memoryId;

        public RelationData() {}
        public RelationData(String id, String subjectId, String predicate, String objectId, String memoryId) {
            this.id = id;
            this.subjectId = subjectId;
            this.predicate = predicate;
            this.objectId = objectId;
            this.memoryId = memoryId;
        }
    }

    // ==================== 存储层 ====================

    private final Map<String, EntityData> entities = new LinkedHashMap<>();
    private final Map<String, List<RelationData>> outgoingEdges = new LinkedHashMap<>(); // subjectId → edges
    private final Map<String, List<RelationData>> incomingEdges = new LinkedHashMap<>(); // objectId → edges
    private final Map<String, Set<String>> entityToMemories = new LinkedHashMap<>();
    private final Map<String, Set<String>> memoryToEntities = new LinkedHashMap<>();
    private final List<RelationData> allRelations = new ArrayList<>();

    private final Path persistPath;
    private static final Gson gson = new Gson();

    public GraphStore() {
        this(null);
    }

    public GraphStore(String persistPath) {
        this.persistPath = persistPath != null ? Path.of(persistPath) : null;
    }

    // ==================== 实体 ====================

    public EntityData addEntity(String name, String type) {
        EntityData existing = findEntityByName(name, type);
        if (existing != null) return existing;

        String id = UUID.randomUUID().toString();
        EntityData entity = new EntityData(id, name, type);
        entities.put(id, entity);
        outgoingEdges.put(id, new ArrayList<>());
        incomingEdges.put(id, new ArrayList<>());
        return entity;
    }

    public EntityData findEntityByName(String name, String type) {
        for (EntityData e : entities.values()) {
            if (e.name.equals(name) && e.type.equals(type)) return e;
        }
        return null;
    }

    public EntityData getEntity(String entityId) {
        return entities.get(entityId);
    }

    public List<EntityData> getAllEntities() {
        return new ArrayList<>(entities.values());
    }

    public int entityCount() { return entities.size(); }

    // ==================== 关系 ====================

    public RelationData addRelation(String subjectId, String predicate, String objectId, String memoryId) {
        String id = UUID.randomUUID().toString();
        RelationData rel = new RelationData(id, subjectId, predicate, objectId, memoryId);
        allRelations.add(rel);

        outgoingEdges.computeIfAbsent(subjectId, k -> new ArrayList<>()).add(rel);
        incomingEdges.computeIfAbsent(objectId, k -> new ArrayList<>()).add(rel);

        return rel;
    }

    public List<RelationData> getAllRelations() {
        return new ArrayList<>(allRelations);
    }

    public int relationCount() { return allRelations.size(); }

    // ==================== 实体-记忆关联 ====================

    public void linkEntityToMemory(String entityId, String memoryId) {
        entityToMemories.computeIfAbsent(entityId, k -> new LinkedHashSet<>()).add(memoryId);
        memoryToEntities.computeIfAbsent(memoryId, k -> new LinkedHashSet<>()).add(entityId);
    }

    /** 直接关联：包含该实体的记忆 */
    public Set<String> getLinkedMemories(String entityId) {
        return entityToMemories.getOrDefault(entityId, Collections.emptySet());
    }

    /** 一跳邻居：通过关系找到关联实体，再找关联记忆 */
    public Set<String> getNeighborMemories(String entityId) {
        Set<String> result = new LinkedHashSet<>();

        // 出边：subject → object
        for (RelationData rel : outgoingEdges.getOrDefault(entityId, Collections.emptyList())) {
            Set<String> mems = entityToMemories.get(rel.objectId);
            if (mems != null) result.addAll(mems);
        }

        // 入边：object ← subject
        for (RelationData rel : incomingEdges.getOrDefault(entityId, Collections.emptyList())) {
            Set<String> mems = entityToMemories.get(rel.subjectId);
            if (mems != null) result.addAll(mems);
        }

        return result;
    }

    /** 解除一条记忆的所有实体关联，并清理该记忆关联的孤立实体和无关联关系 */
    public void unlinkMemory(String memoryId) {
        Set<String> linkedEntities = memoryToEntities.remove(memoryId);
        if (linkedEntities == null) return;

        for (String entityId : linkedEntities) {
            Set<String> memSet = entityToMemories.get(entityId);
            if (memSet != null) {
                memSet.remove(memoryId);
                // 该实体不再关联任何记忆 → 移除实体及其关系
                if (memSet.isEmpty()) {
                    entityToMemories.remove(entityId);
                    entities.remove(entityId);
                    // 清理涉及该实体的所有关系
                    List<RelationData> outRels = outgoingEdges.remove(entityId);
                    List<RelationData> inRels = incomingEdges.remove(entityId);
                    if (outRels != null) outRels.forEach(r -> allRelations.remove(r));
                    if (inRels != null) inRels.forEach(r -> allRelations.remove(r));
                }
            }
        }
    }

    /** 记忆关联的实体ID集合 */
    public Set<String> getMemoryEntities(String memoryId) {
        return memoryToEntities.getOrDefault(memoryId, Collections.emptySet());
    }

    /** 实体的出边关系 */
    public List<RelationData> getOutgoingRelations(String entityId) {
        return outgoingEdges.getOrDefault(entityId, Collections.emptyList());
    }

    /** 实体的入边关系 */
    public List<RelationData> getIncomingRelations(String entityId) {
        return incomingEdges.getOrDefault(entityId, Collections.emptyList());
    }

    // ==================== 持久化 ====================

    public void save() {
        if (persistPath == null) return;
        try {
            Files.createDirectories(persistPath.getParent());

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("entities", new ArrayList<>(entities.values()));

            // 序列化关系
            List<Map<String, String>> relMaps = new ArrayList<>();
            for (RelationData r : allRelations) {
                Map<String, String> rm = new LinkedHashMap<>();
                rm.put("id", r.id);
                rm.put("subjectId", r.subjectId);
                rm.put("predicate", r.predicate);
                rm.put("objectId", r.objectId);
                rm.put("memoryId", r.memoryId);
                relMaps.add(rm);
            }
            root.put("relations", relMaps);

            // 序列化 entityToMemories
            Map<String, List<String>> e2m = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> e : entityToMemories.entrySet()) {
                e2m.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            root.put("entityToMemories", e2m);

            // 序列化 memoryToEntities
            Map<String, List<String>> m2e = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> e : memoryToEntities.entrySet()) {
                m2e.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            root.put("memoryToEntities", m2e);

            Files.writeString(persistPath, gson.toJson(root));
        } catch (IOException e) {
            System.err.println("[GraphStore] 保存失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void load() {
        if (persistPath == null || !Files.exists(persistPath)) return;
        try {
            String json = Files.readString(persistPath);
            Map<String, Object> root = gson.fromJson(json,
                    new TypeToken<Map<String, Object>>(){}.getType());

            // 恢复 entities
            entities.clear();
            List<Map<String, String>> entList = (List<Map<String, String>>) root.get("entities");
            if (entList != null) {
                for (Map<String, String> m : entList) {
                    EntityData e = new EntityData(m.get("id"), m.get("name"), m.get("type"));
                    entities.put(e.id, e);
                    outgoingEdges.put(e.id, new ArrayList<>());
                    incomingEdges.put(e.id, new ArrayList<>());
                }
            }

            // 恢复 relations
            allRelations.clear();
            List<Map<String, String>> relList = (List<Map<String, String>>) root.get("relations");
            if (relList != null) {
                for (Map<String, String> m : relList) {
                    RelationData r = new RelationData(
                            m.get("id"), m.get("subjectId"), m.get("predicate"),
                            m.get("objectId"), m.get("memoryId"));
                    allRelations.add(r);
                    outgoingEdges.computeIfAbsent(r.subjectId, k -> new ArrayList<>()).add(r);
                    incomingEdges.computeIfAbsent(r.objectId, k -> new ArrayList<>()).add(r);
                }
            }

            // 恢复 entityToMemories
            entityToMemories.clear();
            Map<String, List<String>> e2m = (Map<String, List<String>>) root.get("entityToMemories");
            if (e2m != null) {
                for (Map.Entry<String, List<String>> e : e2m.entrySet()) {
                    entityToMemories.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
                }
            }

            // 恢复 memoryToEntities
            memoryToEntities.clear();
            Map<String, List<String>> m2e = (Map<String, List<String>>) root.get("memoryToEntities");
            if (m2e != null) {
                for (Map.Entry<String, List<String>> e : m2e.entrySet()) {
                    memoryToEntities.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
                }
            }
        } catch (Exception e) {
            System.err.println("[GraphStore] 加载失败: " + e.getMessage());
        }
    }

    // ==================== 清空 ====================

    public void clear() {
        entities.clear();
        allRelations.clear();
        outgoingEdges.clear();
        incomingEdges.clear();
        entityToMemories.clear();
        memoryToEntities.clear();
    }

    public String getPersistPath() {
        return persistPath != null ? persistPath.toString() : null;
    }
}
