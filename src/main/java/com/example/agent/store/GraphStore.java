package com.example.agent.store;

import java.util.*;

/**
 * 图存储抽象。实现：InMemoryGraphStore（默认）、Neo4jGraphStore。
 */
public interface GraphStore {

    // --- 内部类型 ---
    final class EntityData {
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

    final class RelationData {
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

    // --- 实体 ---
    EntityData addEntity(String name, String type);
    EntityData findEntityByName(String name, String type);
    EntityData getEntity(String entityId);
    List<EntityData> getAllEntities();
    int entityCount();

    // --- 关系 ---
    RelationData addRelation(String subjectId, String predicate, String objectId, String memoryId);
    List<RelationData> getAllRelations();
    int relationCount();

    // --- 实体-记忆关联 ---
    void linkEntityToMemory(String entityId, String memoryId);
    Set<String> getLinkedMemories(String entityId);
    Set<String> getNeighborMemories(String entityId);
    Set<String> getMemoryEntities(String memoryId);
    void unlinkMemory(String memoryId);

    // --- 遍历 ---
    List<RelationData> getOutgoingRelations(String entityId);
    List<RelationData> getIncomingRelations(String entityId);

    // --- 持久化 / 生命周期 ---
    default void save() {}
    default void load() {}
    default String getPersistPath() { return null; }
    void clear();
}
