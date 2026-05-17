package com.example.agent.store;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 图存储 — 内存 LinkedHashMap + JSON 文件持久化。
 * 默认实现，支持实体-关系管理、实体-记忆关联和一跳邻居查询。
 */
public class InMemoryGraphStore implements GraphStore {

    // ==================== 存储层 ====================

    private final Map<String, EntityData> entities = new LinkedHashMap<>();
    private final Map<String, List<RelationData>> outgoingEdges = new LinkedHashMap<>();
    private final Map<String, List<RelationData>> incomingEdges = new LinkedHashMap<>();
    private final Map<String, Set<String>> entityToMemories = new LinkedHashMap<>();
    private final Map<String, Set<String>> memoryToEntities = new LinkedHashMap<>();
    private final List<RelationData> allRelations = new ArrayList<>();

    private final Path persistPath;
    private static final Gson gson = new Gson();

    public InMemoryGraphStore() {
        this(null);
    }

    public InMemoryGraphStore(String persistPath) {
        this.persistPath = persistPath != null ? Path.of(persistPath) : null;
    }

    // ==================== 实体 ====================

    @Override
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

    @Override
    public EntityData findEntityByName(String name, String type) {
        for (EntityData e : entities.values()) {
            if (e.name.equals(name) && e.type.equals(type)) return e;
        }
        return null;
    }

    @Override
    public EntityData getEntity(String entityId) {
        return entities.get(entityId);
    }

    @Override
    public List<EntityData> getAllEntities() {
        return new ArrayList<>(entities.values());
    }

    @Override
    public int entityCount() { return entities.size(); }

    // ==================== 关系 ====================

    @Override
    public RelationData addRelation(String subjectId, String predicate, String objectId, String memoryId) {
        String id = UUID.randomUUID().toString();
        RelationData rel = new RelationData(id, subjectId, predicate, objectId, memoryId);
        allRelations.add(rel);

        outgoingEdges.computeIfAbsent(subjectId, k -> new ArrayList<>()).add(rel);
        incomingEdges.computeIfAbsent(objectId, k -> new ArrayList<>()).add(rel);

        return rel;
    }

    @Override
    public List<RelationData> getAllRelations() {
        return new ArrayList<>(allRelations);
    }

    @Override
    public int relationCount() { return allRelations.size(); }

    // ==================== 实体-记忆关联 ====================

    @Override
    public void linkEntityToMemory(String entityId, String memoryId) {
        entityToMemories.computeIfAbsent(entityId, k -> new LinkedHashSet<>()).add(memoryId);
        memoryToEntities.computeIfAbsent(memoryId, k -> new LinkedHashSet<>()).add(entityId);
    }

    @Override
    public Set<String> getLinkedMemories(String entityId) {
        return entityToMemories.getOrDefault(entityId, Collections.emptySet());
    }

    @Override
    public Set<String> getNeighborMemories(String entityId) {
        Set<String> result = new LinkedHashSet<>();

        for (RelationData rel : outgoingEdges.getOrDefault(entityId, Collections.emptyList())) {
            Set<String> mems = entityToMemories.get(rel.objectId);
            if (mems != null) result.addAll(mems);
        }

        for (RelationData rel : incomingEdges.getOrDefault(entityId, Collections.emptyList())) {
            Set<String> mems = entityToMemories.get(rel.subjectId);
            if (mems != null) result.addAll(mems);
        }

        return result;
    }

    @Override
    public void unlinkMemory(String memoryId) {
        Set<String> linkedEntities = memoryToEntities.remove(memoryId);
        if (linkedEntities == null) return;

        for (String entityId : linkedEntities) {
            Set<String> memSet = entityToMemories.get(entityId);
            if (memSet != null) {
                memSet.remove(memoryId);
                if (memSet.isEmpty()) {
                    entityToMemories.remove(entityId);
                    entities.remove(entityId);
                    List<RelationData> outRels = outgoingEdges.remove(entityId);
                    List<RelationData> inRels = incomingEdges.remove(entityId);
                    if (outRels != null) outRels.forEach(r -> allRelations.remove(r));
                    if (inRels != null) inRels.forEach(r -> allRelations.remove(r));
                }
            }
        }
    }

    @Override
    public Set<String> getMemoryEntities(String memoryId) {
        return memoryToEntities.getOrDefault(memoryId, Collections.emptySet());
    }

    @Override
    public List<RelationData> getOutgoingRelations(String entityId) {
        return outgoingEdges.getOrDefault(entityId, Collections.emptyList());
    }

    @Override
    public List<RelationData> getIncomingRelations(String entityId) {
        return incomingEdges.getOrDefault(entityId, Collections.emptyList());
    }

    // ==================== 持久化 ====================

    @Override
    public void save() {
        if (persistPath == null) return;
        try {
            Files.createDirectories(persistPath.getParent());

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("entities", new ArrayList<>(entities.values()));

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

            Map<String, List<String>> e2m = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> e : entityToMemories.entrySet()) {
                e2m.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            root.put("entityToMemories", e2m);

            Map<String, List<String>> m2e = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> e : memoryToEntities.entrySet()) {
                m2e.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            root.put("memoryToEntities", m2e);

            Files.writeString(persistPath, gson.toJson(root));
        } catch (IOException e) {
            System.err.println("[InMemoryGraphStore] 保存失败: " + e.getMessage());
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

            entityToMemories.clear();
            Map<String, List<String>> e2m = (Map<String, List<String>>) root.get("entityToMemories");
            if (e2m != null) {
                for (Map.Entry<String, List<String>> e : e2m.entrySet()) {
                    entityToMemories.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
                }
            }

            memoryToEntities.clear();
            Map<String, List<String>> m2e = (Map<String, List<String>>) root.get("memoryToEntities");
            if (m2e != null) {
                for (Map.Entry<String, List<String>> e : m2e.entrySet()) {
                    memoryToEntities.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
                }
            }
        } catch (Exception e) {
            System.err.println("[InMemoryGraphStore] 加载失败: " + e.getMessage());
        }
    }

    // ==================== 清空 ====================

    @Override
    public void clear() {
        entities.clear();
        allRelations.clear();
        outgoingEdges.clear();
        incomingEdges.clear();
        entityToMemories.clear();
        memoryToEntities.clear();
    }

    @Override
    public String getPersistPath() {
        return persistPath != null ? persistPath.toString() : null;
    }
}
