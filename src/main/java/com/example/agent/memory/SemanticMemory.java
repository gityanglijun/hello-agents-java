package com.example.agent.memory;

import com.example.agent.embedding.EmbedderProvider;
import com.example.agent.nlp.EntityRelationExtractor;
import com.example.agent.store.DocumentStore;
import com.example.agent.store.GraphStore;
import com.example.agent.store.VectorStore;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 语义记忆实现 — 向量 + 知识图谱混合检索。
 * 嵌入使用 EmbedderProvider 降级链（BGE → LLM API → 百炼 API → TF-IDF）。
 * 存储使用 VectorStore / GraphStore / DocumentStore。
 */
public class SemanticMemory implements BaseMemory {

    // ==================== 内部类型（兼容 EntityRelationExtractor） ====================

    public static class Entity {
        public final String entityId;
        public final String name;
        public final String type;

        public Entity(String entityId, String name, String type) {
            this.entityId = entityId;
            this.name = name;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Entity)) return false;
            return ((Entity) o).name.equals(name) && ((Entity) o).type.equals(type);
        }

        @Override
        public int hashCode() { return Objects.hash(name, type); }
    }

    public static class Relation {
        public final String relationId;
        public final String subjectId;
        public final String predicate;
        public final String objectId;
        public final String memoryId;

        public Relation(String relationId, String subjectId, String predicate,
                        String objectId, String memoryId) {
            this.relationId = relationId;
            this.subjectId = subjectId;
            this.predicate = predicate;
            this.objectId = objectId;
            this.memoryId = memoryId;
        }
    }

    // ==================== 存储层 ====================

    private final VectorStore vectorStore;
    private final GraphStore graphStore;
    private final DocumentStore docStore;
    private final boolean ownDocStore;
    private final EmbedderProvider embedder;
    private final EntityRelationExtractor extractor = new EntityRelationExtractor();

    // ==================== 构造 ====================

    /** 使用默认嵌入后端（自动降级链 BGE → LLM API → 百炼 API → TF-IDF） */
    public SemanticMemory() {
        this(new EmbedderProvider(256), null, null, null);
    }

    /** @param vectorDim TF-IDF 降级时的向量维度（BGE/百炼可用时自动使用更高维度） */
    public SemanticMemory(int vectorDim) {
        this(new EmbedderProvider(vectorDim), null, null, null);
    }

    /** 使用自定义 EmbedderProvider */
    public SemanticMemory(EmbedderProvider embedder) {
        this(embedder, null, null, null);
    }

    /** 使用 MemoryConfig 统一配置 */
    public SemanticMemory(MemoryConfig config) {
        this(new EmbedderProvider(config.embedderFallbackDim), null, null, null);
    }

    /** 使用外部存储后端（持久化）。传 null 则使用默认内存存储 */
    public SemanticMemory(EmbedderProvider embedder,
                          DocumentStore docStore, VectorStore vectorStore, GraphStore graphStore) {
        this.embedder = embedder != null ? embedder : new EmbedderProvider(256);
        this.docStore = docStore != null ? docStore : new DocumentStore(":memory:");
        this.ownDocStore = docStore == null;
        this.vectorStore = vectorStore != null ? vectorStore : new VectorStore(this.embedder.getDimension());
        this.graphStore = graphStore != null ? graphStore : new GraphStore();
    }

    // ==================== 添加 ====================

    public String add(MemoryManager.MemoryItem item) {
        // 1. 生成文本嵌入
        embedder.register(item.content);
        float[] embedding = embedder.encode(item.content);
        vectorStore.add(item.id, embedding);

        // 2. 提取实体和关系
        List<GraphStore.EntityData> extractedEntities = extractAndStoreEntities(item.content);
        extractAndStoreRelations(item.content, extractedEntities, item.id);

        // 3. 关联记忆到实体
        for (GraphStore.EntityData entity : extractedEntities) {
            graphStore.linkEntityToMemory(entity.id, item.id);
        }

        // 4. 缓存记忆
        docStore.insertMemoryItem(item);

        return item.id;
    }

    // ==================== 检索 ====================

    public List<MemoryManager.MemoryItem> retrieve(String query, int limit, Map<String, Object> kwargs) {
        String userId = kwargs != null ? (String) kwargs.get("user_id") : null;

        // 1. 向量检索
        List<VectorHit> vectorResults = vectorSearch(query, limit * 2, userId);

        // 2. 图检索
        List<GraphHit> graphResults = graphSearch(query, limit * 2, userId);

        // 3. 混合排序
        return combineAndRank(vectorResults, graphResults, limit);
    }

    public List<MemoryManager.MemoryItem> retrieve(String query, int limit) {
        return retrieve(query, limit, Collections.emptyMap());
    }

    // ==================== 向量检索 ====================

    private List<VectorHit> vectorSearch(String query, int topK, String userId) {
        float[] queryVec = embedder.encode(query);
        List<VectorStore.VectorHit> hits = vectorStore.search(queryVec, topK);

        List<VectorHit> results = new ArrayList<>();
        for (VectorStore.VectorHit hit : hits) {
            // userId 过滤
            if (userId != null) {
                MemoryManager.MemoryItem item = docStore.getMemoryItem(hit.id);
                if (item == null || !userId.equals(item.metadata.get("user_id"))) continue;
            }
            results.add(new VectorHit(hit.id, hit.score));
        }
        return results;
    }

    // ==================== 图检索 ====================

    private List<GraphHit> graphSearch(String query, int topK, String userId) {
        List<Entity> queryEntities = extractEntities(query);
        if (queryEntities.isEmpty()) return Collections.emptyList();

        Map<String, GraphHit> memoryScores = new LinkedHashMap<>();
        for (Entity qe : queryEntities) {
            GraphStore.EntityData match = graphStore.findEntityByName(qe.name, qe.type);
            if (match == null) continue;

            // 直接关联：记忆包含该实体
            Set<String> linkedMemories = graphStore.getLinkedMemories(match.id);
            for (String memId : linkedMemories) {
                if (userId != null) {
                    MemoryManager.MemoryItem item = docStore.getMemoryItem(memId);
                    if (item == null || !userId.equals(item.metadata.get("user_id"))) continue;
                }
                memoryScores.merge(memId, new GraphHit(memId, 1.0, 1), (a, b) -> {
                    a.score += 1.0;
                    a.sharedEntities += 1;
                    return a;
                });
            }

            // 一跳邻居
            Set<String> neighborMemories = graphStore.getNeighborMemories(match.id);
            for (String memId : neighborMemories) {
                if (linkedMemories.contains(memId)) continue;
                if (userId != null) {
                    MemoryManager.MemoryItem item = docStore.getMemoryItem(memId);
                    if (item == null || !userId.equals(item.metadata.get("user_id"))) continue;
                }
                memoryScores.merge(memId, new GraphHit(memId, 0.5, 0), (a, b) -> {
                    a.score = Math.max(a.score, 0.5);
                    return a;
                });
            }
        }

        // 归一化：Jaccard 相似度 × 基准分
        for (GraphHit hit : memoryScores.values()) {
            Set<String> memEntities = graphStore.getMemoryEntities(hit.memoryId);
            double jaccardDenom = queryEntities.size() + memEntities.size() - hit.sharedEntities;
            hit.score = hit.score * (hit.sharedEntities / Math.max(jaccardDenom, 1.0));
        }

        List<GraphHit> sorted = new ArrayList<>(memoryScores.values());
        sorted.sort((a, b) -> Double.compare(b.score, a.score));
        return sorted.subList(0, Math.min(topK, sorted.size()));
    }

    // ==================== 混合排序 ====================

    private List<MemoryManager.MemoryItem> combineAndRank(
            List<VectorHit> vectorResults, List<GraphHit> graphResults, int limit) {

        Map<String, CombinedScore> combined = new LinkedHashMap<>();

        for (VectorHit vh : vectorResults) {
            CombinedScore cs = new CombinedScore();
            cs.memoryId = vh.memoryId;
            cs.vectorScore = vh.score;
            combined.put(vh.memoryId, cs);
        }

        for (GraphHit gh : graphResults) {
            CombinedScore cs = combined.get(gh.memoryId);
            if (cs != null) {
                cs.graphScore = gh.score;
            } else {
                cs = new CombinedScore();
                cs.memoryId = gh.memoryId;
                cs.graphScore = gh.score;
                combined.put(gh.memoryId, cs);
            }
        }

        for (CombinedScore cs : combined.values()) {
            double baseRelevance = cs.vectorScore * 0.7 + cs.graphScore * 0.3;

            MemoryManager.MemoryItem item = docStore.getMemoryItem(cs.memoryId);
            double importance = item != null ? item.importance : 0.5;
            double importanceWeight = 0.8 + (importance * 0.4);

            cs.finalScore = baseRelevance * importanceWeight;
        }

        List<CombinedScore> sorted = new ArrayList<>(combined.values());
        sorted.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));

        int n = Math.min(limit > 0 ? limit : 5, sorted.size());
        List<MemoryManager.MemoryItem> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MemoryManager.MemoryItem item = docStore.getMemoryItem(sorted.get(i).memoryId);
            if (item != null) results.add(item);
        }
        return results;
    }

    // ==================== 实体提取 ====================

    /** 仅提取实体，不存储（用于检索时从查询中提取实体） */
    private List<Entity> extractEntities(String text) {
        return extractor.extractEntities(text);
    }

    /** 提取实体并存储到 GraphStore，返回已存储的实体 */
    private List<GraphStore.EntityData> extractAndStoreEntities(String text) {
        List<Entity> ents = extractor.extractEntities(text);
        List<GraphStore.EntityData> result = new ArrayList<>();
        for (Entity ent : ents) {
            GraphStore.EntityData stored = graphStore.addEntity(ent.name, ent.type);
            result.add(stored);
        }
        return result;
    }

    /** 提取关系并存储到 GraphStore */
    private void extractAndStoreRelations(String text, List<GraphStore.EntityData> textEntities, String memoryId) {
        List<Entity> entities = new ArrayList<>();
        for (GraphStore.EntityData ge : textEntities) {
            entities.add(new Entity(ge.id, ge.name, ge.type));
        }
        List<Relation> rels = extractor.extractRelations(text, entities, memoryId);
        for (Relation rel : rels) {
            graphStore.addRelation(rel.subjectId, rel.predicate, rel.objectId, rel.memoryId);
        }
    }

    // ==================== 公开访问器 ====================

    public int size() { return docStore.countMemoryItems(); }
    public int entityCount() { return graphStore.entityCount(); }
    public int relationCount() { return graphStore.relationCount(); }

    public Entity getEntity(String entityId) {
        GraphStore.EntityData ge = graphStore.getEntity(entityId);
        return ge != null ? new Entity(ge.id, ge.name, ge.type) : null;
    }

    public List<Entity> getAllEntities() {
        List<Entity> result = new ArrayList<>();
        for (GraphStore.EntityData ge : graphStore.getAllEntities()) {
            result.add(new Entity(ge.id, ge.name, ge.type));
        }
        return result;
    }

    public List<MemoryManager.MemoryItem> getByEntity(String entityId) {
        Set<String> memIds = graphStore.getLinkedMemories(entityId);
        return memIds.stream().map(docStore::getMemoryItem)
                .filter(Objects::nonNull).collect(Collectors.toList());
    }

    /** 获取记忆条目 */
    public MemoryManager.MemoryItem getMemory(String id) {
        return docStore.getMemoryItem(id);
    }

    @Override
    public void remove(String id) {
        if (id == null) return;
        vectorStore.remove(id);
        graphStore.unlinkMemory(id);
        docStore.deleteMemoryItem(id);
    }

    public void clear() {
        vectorStore.clear();
        graphStore.clear();
        docStore.clearAll();
    }

    /** 持久化向量和图存储 */
    public void save() {
        vectorStore.save();
        graphStore.save();
    }

    public void close() {
        if (ownDocStore) docStore.close();
    }

    // ==================== 内部类型 ====================

    private static class VectorHit {
        final String memoryId;
        final double score;
        VectorHit(String memoryId, double score) {
            this.memoryId = memoryId;
            this.score = score;
        }
    }

    private static class GraphHit {
        final String memoryId;
        double score;
        int sharedEntities;
        GraphHit(String memoryId, double score, int sharedEntities) {
            this.memoryId = memoryId;
            this.score = score;
            this.sharedEntities = sharedEntities;
        }
    }

    private static class CombinedScore {
        String memoryId;
        double vectorScore;
        double graphScore;
        double finalScore;
    }
}
