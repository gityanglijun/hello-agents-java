import java.util.*;
import java.util.stream.Collectors;

/**
 * 语义记忆实现
 *
 * 特点：
 * - 向量 + 知识图谱混合检索
 * - 实体和关系提取
 * - 混合排序：vec_score×0.7 + graph_score×0.3
 */
public class SemanticMemory {

    // ==================== 内部数据类 ====================

    public static class Entity {
        public final String entityId;
        public final String name;
        public final String type;       // person, concept, tool, language, etc.

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
        public final String subjectId;   // 主体实体ID
        public final String predicate;   // 关系类型
        public final String objectId;    // 客体实体ID
        public final String memoryId;    // 来源记忆ID

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

    // 向量存储: memoryId → 向量
    private final Map<String, float[]> vectorStore = new LinkedHashMap<>();

    // 图存储
    private final Map<String, Entity> entities = new LinkedHashMap<>();          // entityId → Entity
    private final List<Relation> relations = new ArrayList<>();
    private final Map<String, Set<String>> entityToMemories = new HashMap<>();  // entityId → memoryIds
    private final Map<String, Set<String>> memoryToEntities = new HashMap<>();  // memoryId → entityIds

    // 记忆内容（用于返回检索结果）
    private final Map<String, MemoryManager.MemoryItem> memoryStore = new LinkedHashMap<>();

    // 嵌入器
    private final EpisodicMemory.TextEmbedder embedder;

    // 实体/关系提取器
    private final EntityRelationExtractor extractor = new EntityRelationExtractor();

    // ==================== 构造 ====================

    public SemanticMemory() {
        this.embedder = new EpisodicMemory.TextEmbedder(256);
    }

    public SemanticMemory(int vectorDim) {
        this.embedder = new EpisodicMemory.TextEmbedder(vectorDim);
    }

    public SemanticMemory(EpisodicMemory.TextEmbedder embedder) {
        this.embedder = embedder != null ? embedder : new EpisodicMemory.TextEmbedder(256);
    }

    // ==================== 添加 ====================

    public String add(MemoryManager.MemoryItem item) {
        // 1. 生成文本嵌入
        embedder.register(item.content);
        float[] embedding = embedder.encode(item.content);
        vectorStore.put(item.id, embedding);

        // 2. 提取实体和关系
        List<Entity> extractedEntities = extractEntities(item.content);
        List<Relation> extractedRelations = extractRelations(item.content, extractedEntities, item.id);

        // 3. 存储到图
        memoryToEntities.put(item.id, new HashSet<>());
        for (Entity entity : extractedEntities) {
            // 实体去重（同名同类型复用）
            Entity existing = findEntityByName(entity.name, entity.type);
            if (existing == null) {
                entities.put(entity.entityId, entity);
                existing = entity;
            }
            entityToMemories.computeIfAbsent(existing.entityId, k -> new HashSet<>()).add(item.id);
            memoryToEntities.get(item.id).add(existing.entityId);
        }

        for (Relation rel : extractedRelations) {
            relations.add(rel);
        }

        // 4. 缓存记忆
        memoryStore.put(item.id, item);

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
        List<VectorHit> hits = new ArrayList<>();

        for (Map.Entry<String, float[]> entry : vectorStore.entrySet()) {
            // userId 过滤
            if (userId != null) {
                MemoryManager.MemoryItem item = memoryStore.get(entry.getKey());
                if (item == null || !userId.equals(item.metadata.get("user_id"))) continue;
            }

            double sim = cosine(queryVec, entry.getValue());
            if (sim > 0) {
                hits.add(new VectorHit(entry.getKey(), sim));
            }
        }

        hits.sort((a, b) -> Double.compare(b.score, a.score));
        return hits.subList(0, Math.min(topK, hits.size()));
    }

    // ==================== 图检索 ====================

    private List<GraphHit> graphSearch(String query, int topK, String userId) {
        // 从查询中提取实体作为图查询的锚点
        List<Entity> queryEntities = extractEntities(query);
        if (queryEntities.isEmpty()) return Collections.emptyList();

        // 找到包含这些实体的记忆
        Map<String, GraphHit> memoryScores = new LinkedHashMap<>();
        for (Entity qe : queryEntities) {
            Entity match = findEntityByName(qe.name, qe.type);
            if (match == null) continue;

            // 直接关联：记忆包含该实体
            Set<String> linkedMemories = entityToMemories.getOrDefault(match.entityId, Collections.emptySet());
            for (String memId : linkedMemories) {
                if (userId != null) {
                    MemoryManager.MemoryItem item = memoryStore.get(memId);
                    if (item == null || !userId.equals(item.metadata.get("user_id"))) continue;
                }
                memoryScores.merge(memId, new GraphHit(memId, 1.0, 1), (a, b) -> {
                    a.score += 1.0;
                    a.sharedEntities += 1;
                    return a;
                });
            }

            // 一跳邻居：通过关系找到关联实体，再找关联记忆
            for (Relation rel : relations) {
                String neighborEntityId = null;
                if (rel.subjectId.equals(match.entityId)) neighborEntityId = rel.objectId;
                else if (rel.objectId.equals(match.entityId)) neighborEntityId = rel.subjectId;
                if (neighborEntityId == null) continue;

                Set<String> neighborMemories = entityToMemories.getOrDefault(neighborEntityId, Collections.emptySet());
                for (String memId : neighborMemories) {
                    if (linkedMemories.contains(memId)) continue; // 已计入直接关联
                    if (userId != null) {
                        MemoryManager.MemoryItem item = memoryStore.get(memId);
                        if (item == null || !userId.equals(item.metadata.get("user_id"))) continue;
                    }
                    memoryScores.merge(memId, new GraphHit(memId, 0.5, 0), (a, b) -> {
                        a.score = Math.max(a.score, 0.5); // 一跳取 0.5
                        return a;
                    });
                }
            }
        }

        // 归一化：Jaccard 相似度 × 基准分
        for (GraphHit hit : memoryScores.values()) {
            Set<String> memEntities = memoryToEntities.getOrDefault(hit.memoryId, Collections.emptySet());
            double jaccard = queryEntities.size() + memEntities.size() - hit.sharedEntities;
            hit.score = hit.score * (hit.sharedEntities / Math.max(jaccard, 1.0));
        }

        List<GraphHit> sorted = new ArrayList<>(memoryScores.values());
        sorted.sort((a, b) -> Double.compare(b.score, a.score));
        return sorted.subList(0, Math.min(topK, sorted.size()));
    }

    // ==================== 混合排序 ====================

    private List<MemoryManager.MemoryItem> combineAndRank(
            List<VectorHit> vectorResults, List<GraphHit> graphResults, int limit) {

        Map<String, CombinedScore> combined = new LinkedHashMap<>();

        // 合并向量结果
        for (VectorHit vh : vectorResults) {
            CombinedScore cs = new CombinedScore();
            cs.memoryId = vh.memoryId;
            cs.vectorScore = vh.score;
            combined.put(vh.memoryId, cs);
        }

        // 合并图结果
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

        // 计算最终分数: (vec×0.7 + graph×0.3) × importanceWeight
        for (CombinedScore cs : combined.values()) {
            double baseRelevance = cs.vectorScore * 0.7 + cs.graphScore * 0.3;

            MemoryManager.MemoryItem item = memoryStore.get(cs.memoryId);
            double importance = item != null ? item.importance : 0.5;
            double importanceWeight = 0.8 + (importance * 0.4);

            cs.finalScore = baseRelevance * importanceWeight;
        }

        // 排序
        List<CombinedScore> sorted = new ArrayList<>(combined.values());
        sorted.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));

        int n = Math.min(limit > 0 ? limit : 5, sorted.size());
        List<MemoryManager.MemoryItem> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MemoryManager.MemoryItem item = memoryStore.get(sorted.get(i).memoryId);
            if (item != null) results.add(item);
        }
        return results;
    }

    // ==================== 实体提取 ====================

    /** 实体提取（委托给 EntityRelationExtractor） */
    private List<Entity> extractEntities(String text) {
        return extractor.extractEntities(text);
    }

    // ==================== 关系提取 ====================

    /** 关系提取（委托给 EntityRelationExtractor） */
    private List<Relation> extractRelations(String text, List<Entity> textEntities, String memoryId) {
        return extractor.extractRelations(text, textEntities, memoryId);
    }

    // ==================== 实体查找 ====================

    private Entity findEntityByName(String name, String type) {
        for (Entity e : entities.values()) {
            if (e.name.equals(name) && e.type.equals(type)) return e;
        }
        return null;
    }

    // ==================== 向量工具 ====================

    private double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    // ==================== 公开访问器 ====================

    public int size() { return memoryStore.size(); }
    public int entityCount() { return entities.size(); }
    public int relationCount() { return relations.size(); }

    public Entity getEntity(String entityId) { return entities.get(entityId); }
    public List<Entity> getAllEntities() { return new ArrayList<>(entities.values()); }

    public List<MemoryManager.MemoryItem> getByEntity(String entityId) {
        Set<String> memIds = entityToMemories.getOrDefault(entityId, Collections.emptySet());
        return memIds.stream().map(memoryStore::get).filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void clear() {
        vectorStore.clear();
        entities.clear();
        relations.clear();
        entityToMemories.clear();
        memoryToEntities.clear();
        memoryStore.clear();
        embedder.reset();
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
