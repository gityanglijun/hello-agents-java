package com.example.agent.memory;

import com.example.agent.embedding.EmbedderProvider;
import com.example.agent.store.DocumentStore;
import com.example.agent.store.GraphStore;
import com.example.agent.store.InMemoryGraphStore;
import com.example.agent.store.InMemoryVectorStore;
import com.example.agent.store.StoreFactory;
import com.example.agent.store.VectorStore;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆管理器 — 四种记忆类的统一调度中心。
 *
 * 存储架构:
 *   DocumentStore  → 主记录（元数据、统计、遗忘策略）
 *   WorkingMemory  → 短期工作记忆（TF-IDF + 时间衰减）
 *   EpisodicMemory → 情景记忆（向量 + 结构化过滤）
 *   SemanticMemory → 语义记忆（向量 + 知识图谱）
 *   PerceptualMemory → 感知记忆（多模态）
 *
 * addMemory()    → 写入 DocumentStore + 分发到对应记忆类
 * retrieve()     → 按类型分发，合并各记忆类的检索结果
 */
public class MemoryManager {

    public static final String TYPE_WORKING = "working";
    public static final String TYPE_EPISODIC = "episodic";
    public static final String TYPE_SEMANTIC = "semantic";
    public static final String TYPE_PERCEPTUAL = "perceptual";

    public static final String STRATEGY_IMPORTANCE_BASED = "importance_based";
    public static final String STRATEGY_TIME_BASED = "time_based";
    public static final String STRATEGY_CAPACITY_BASED = "capacity_based";

    public static class MemoryItem {
        public final String id;
        public String content;
        public String memoryType;
        public double importance;
        public final Map<String, String> metadata;
        public final String createdAt;
        public String updatedAt;

        public MemoryItem(String id, String content, String memoryType, double importance,
                          Map<String, String> metadata, String createdAt) {
            this.id = id;
            this.content = content;
            this.memoryType = memoryType != null ? memoryType : TYPE_WORKING;
            this.importance = Math.max(0.0, Math.min(1.0, importance));
            this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }
    }

    // ==================== 存储层 ====================

    private final DocumentStore docStore;          // 主记录
    private final boolean ownDocStore;
    private final WorkingMemory workingMemory;
    private final EpisodicMemory episodicMemory;
    private final SemanticMemory semanticMemory;
    private final PerceptualMemory perceptualMemory;

    // ==================== 构造 ====================

    public MemoryManager() {
        this(new DocumentStore(":memory:"));
    }

    public MemoryManager(DocumentStore docStore) {
        this.docStore = docStore != null ? docStore : new DocumentStore(":memory:");
        this.ownDocStore = docStore == null;
        this.workingMemory = new WorkingMemory();
        this.episodicMemory = new EpisodicMemory();
        this.semanticMemory = new SemanticMemory();
        this.perceptualMemory = new PerceptualMemory();
    }

    /** 使用文件持久化 */
    public MemoryManager(String dbPath) {
        this(new DocumentStore(dbPath != null ? dbPath : ":memory:"));
    }

    /** 使用 StoreFactory 创建存储后端（从环境变量选择 InMemory / Qdrant / Neo4j） */
    public MemoryManager(DocumentStore docStore, StoreFactory storeFactory) {
        this.docStore = docStore != null ? docStore : new DocumentStore(":memory:");
        this.ownDocStore = docStore == null;
        StoreFactory sf = storeFactory != null ? storeFactory : new StoreFactory();

        int episodicDim = 256;
        int semanticDim = 256;
        int textDim = 256, imageDim = 512, audioDim = 512;

        this.workingMemory = new WorkingMemory();
        this.episodicMemory = new EpisodicMemory(
            new EmbedderProvider(episodicDim), this.docStore,
            sf.createVectorStore(episodicDim, "episodic"));
        this.semanticMemory = new SemanticMemory(
            new EmbedderProvider(semanticDim), this.docStore,
            sf.createVectorStore(semanticDim, "semantic"),
            sf.createGraphStore());
        this.perceptualMemory = new PerceptualMemory(
            textDim, imageDim, audioDim, this.docStore,
            sf.createVectorStore(textDim, "perceptual_text"),
            sf.createVectorStore(imageDim, "perceptual_image"),
            sf.createVectorStore(audioDim, "perceptual_audio"));
    }

    /** 直接注入所有存储实例（手动控制每个后端） */
    public MemoryManager(DocumentStore docStore,
                         VectorStore episodicVecStore,
                         VectorStore semanticVecStore,
                         GraphStore semanticGraphStore,
                         VectorStore perceptualTextStore,
                         VectorStore perceptualImageStore,
                         VectorStore perceptualAudioStore) {
        this.docStore = docStore != null ? docStore : new DocumentStore(":memory:");
        this.ownDocStore = docStore == null;
        this.workingMemory = new WorkingMemory();
        this.episodicMemory = new EpisodicMemory(
            new EmbedderProvider(256), this.docStore, episodicVecStore);
        this.semanticMemory = new SemanticMemory(
            new EmbedderProvider(256), this.docStore, semanticVecStore, semanticGraphStore);
        this.perceptualMemory = new PerceptualMemory(
            256, 512, 512, this.docStore,
            perceptualTextStore, perceptualImageStore, perceptualAudioStore);
    }

    // ==================== 添加记忆 ====================

    public String addMemory(String content, String memoryType, double importance,
                            Map<String, String> metadata, boolean autoClassify) {
        String id = UUID.randomUUID().toString();
        String now = now();

        if (autoClassify) {
            memoryType = autoClassify(content, memoryType);
        }

        MemoryItem item = new MemoryItem(id, content, memoryType, importance, metadata, now);

        // 1. 写入主记录
        docStore.insertMemoryItem(item);

        // 2. 分发到对应记忆类
        dispatchAdd(item);

        return id;
    }

    /** 将 MemoryItem 分发到对应的记忆类 */
    private void dispatchAdd(MemoryItem item) {
        try {
            switch (item.memoryType) {
                case TYPE_WORKING:
                    workingMemory.add(item);
                    break;
                case TYPE_EPISODIC:
                    episodicMemory.add(item);
                    break;
                case TYPE_SEMANTIC:
                    semanticMemory.add(item);
                    break;
                case TYPE_PERCEPTUAL:
                    perceptualMemory.add(item);
                    break;
                default:
                    workingMemory.add(item); // 兜底
            }
        } catch (Exception e) {
            System.err.println("[MemoryManager] 分发到 " + item.memoryType + " 失败: " + e.getMessage());
        }
    }

    private String autoClassify(String content, String currentType) {
        String lower = content.toLowerCase();
        if (lower.contains("知识") || lower.contains("定义") || lower.contains("概念") || lower.contains("原理")) {
            return TYPE_SEMANTIC;
        }
        if (lower.contains("事件") || lower.contains("经历") || lower.contains("完成") || lower.contains("发生")) {
            return TYPE_EPISODIC;
        }
        if (lower.contains("图片") || lower.contains("截图") || lower.contains("音频") || lower.contains("视频")) {
            return TYPE_PERCEPTUAL;
        }
        return currentType != null ? currentType : TYPE_WORKING;
    }

    // ==================== 检索记忆 ====================

    public List<MemoryItem> retrieveMemories(String query, int limit,
                                             List<String> memoryTypes, double minImportance) {
        // 确定要查询的类型
        List<String> types = resolveTypes(memoryTypes);
        boolean hasQuery = query != null && !query.isBlank();

        // 对每种类型调用其专有检索逻辑，合并结果
        List<MemoryItem> results = new ArrayList<>();
        for (String type : types) {
            List<MemoryItem> typeResults;
            if (hasQuery) {
                typeResults = dispatchRetrieve(type, query, Math.max(limit, 10),
                        Map.of("min_importance", minImportance));
            } else {
                // 无查询词：从 DocumentStore 按重要性返回
                typeResults = docStore.getMemoryItemsByType(type).stream()
                        .filter(m -> m.importance >= minImportance)
                        .limit(limit)
                        .collect(Collectors.toList());
            }
            results.addAll(typeResults);
        }

        // 按重要性降序排列（不同记忆类的分数已各自归一化）
        results.sort(Comparator.comparingDouble((MemoryItem m) -> m.importance).reversed());
        int resultLimit = limit > 0 ? Math.min(limit, results.size()) : results.size();
        return results.subList(0, resultLimit);
    }

    /** 确定要查询的记忆类型列表 */
    private List<String> resolveTypes(List<String> requested) {
        if (requested != null && !requested.isEmpty()) return new ArrayList<>(requested);
        return List.of(TYPE_WORKING, TYPE_EPISODIC, TYPE_SEMANTIC, TYPE_PERCEPTUAL);
    }

    /** 将检索分发到指定的记忆类 */
    private List<MemoryItem> dispatchRetrieve(String type, String query, int limit,
                                               Map<String, Object> kwargs) {
        try {
            switch (type) {
                case TYPE_WORKING:
                    return workingMemory.retrieve(query, limit);
                case TYPE_EPISODIC:
                    return episodicMemory.retrieve(query, limit, kwargs);
                case TYPE_SEMANTIC:
                    return semanticMemory.retrieve(query, limit, kwargs);
                case TYPE_PERCEPTUAL:
                    return perceptualMemory.retrieve(query, limit, kwargs);
                default:
                    return Collections.emptyList();
            }
        } catch (Exception e) {
            System.err.println("[MemoryManager] 检索 " + type + " 失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 单条操作 ====================

    public MemoryItem getMemory(String id) {
        return docStore.getMemoryItem(id);
    }

    public boolean updateMemory(String id, String content, Double importance, Map<String, String> metadata) {
        return docStore.updateMemoryItem(id, content, importance, metadata);
    }

    public boolean removeMemory(String id) {
        return docStore.deleteMemoryItem(id);
    }

    // ==================== 遗忘策略 ====================

    public int forgetMemories(String strategy, double threshold, int maxAgeDays) {
        // 1. 先查出待删除的 ID 列表
        List<String> toDelete = docStore.getMemoryItemIdsByStrategy(strategy, threshold, maxAgeDays);
        if (toDelete.isEmpty()) return 0;

        // 2. 同步清理各记忆类的内部存储（向量/图谱）
        for (String id : toDelete) {
            MemoryItem item = docStore.getMemoryItem(id);
            if (item != null) {
                switch (item.memoryType) {
                    case TYPE_WORKING:    workingMemory.remove(id);    break;
                    case TYPE_EPISODIC:   episodicMemory.remove(id);   break;
                    case TYPE_SEMANTIC:   semanticMemory.remove(id);   break;
                    case TYPE_PERCEPTUAL: perceptualMemory.remove(id); break;
                }
            }
        }

        // 3. 最后从 DocumentStore 主表中删除
        return docStore.deleteMemoryItems(strategy, threshold, maxAgeDays);
    }

    // ==================== 整合记忆 ====================

    /** 将重要程度达标的工作记忆提升为情景记忆，或情景记忆提升为语义记忆 */
    public int consolidateMemories(String fromType, String toType, double importanceThreshold) {
        int count = 0;
        String now = now();
        List<MemoryItem> all = docStore.getAllMemoryItems();

        for (MemoryItem item : all) {
            if (item.memoryType.equals(fromType) && item.importance >= importanceThreshold) {
                // 更新类型
                item.memoryType = toType;
                item.updatedAt = now;
                docStore.insertMemoryItem(item); // INSERT OR REPLACE

                // 写入目标记忆类
                dispatchAdd(item);
                count++;
            }
        }
        return count;
    }

    // ==================== 清空与统计 ====================

    public int clearAll() {
        int count = docStore.countMemoryItems();
        docStore.clearAll();
        workingMemory.clear();
        episodicMemory.clear();
        semanticMemory.clear();
        perceptualMemory.clear();
        return count;
    }

    public int totalCount() {
        return docStore.countMemoryItems();
    }

    public Map<String, Long> countByType() {
        return docStore.countMemoryItemsByType();
    }

    public double averageImportance() {
        return docStore.averageImportance();
    }

    // ==================== 摘要 ====================

    public String getSummary() {
        List<MemoryItem> all = docStore.getAllMemoryItems();
        int total = all.size();
        if (total == 0) return "📝 暂无记忆";

        Map<String, Long> byType = countByType();
        double avgImp = averageImportance();
        Map<String, String> labels = typeLabels();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 记忆总览:\n");
        sb.append("  总数: ").append(total).append(" 条\n");
        sb.append("  平均重要性: ").append(String.format("%.2f", avgImp)).append("\n");
        sb.append("  类型分布:\n");

        for (Map.Entry<String, Long> entry : byType.entrySet()) {
            String label = labels.getOrDefault(entry.getKey(), entry.getKey());
            sb.append("    - ").append(label).append(": ").append(entry.getValue()).append(" 条\n");
        }

        sb.append("  最近记忆:\n");
        all.stream()
                .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
                .limit(3)
                .forEach(m -> {
                    String preview = m.content.length() > 60 ? m.content.substring(0, 60) + "..." : m.content;
                    String label = labels.getOrDefault(m.memoryType, m.memoryType);
                    sb.append("    - [").append(label).append("] ").append(preview).append("\n");
                });

        return sb.toString().trim();
    }

    public static Map<String, String> typeLabels() {
        return Map.of(
                TYPE_WORKING, "工作记忆",
                TYPE_EPISODIC, "情景记忆",
                TYPE_SEMANTIC, "语义记忆",
                TYPE_PERCEPTUAL, "感知记忆"
        );
    }

    // ==================== 访问器 ====================

    public DocumentStore getDocumentStore() { return docStore; }
    public WorkingMemory getWorkingMemory() { return workingMemory; }
    public EpisodicMemory getEpisodicMemory() { return episodicMemory; }
    public SemanticMemory getSemanticMemory() { return semanticMemory; }
    public PerceptualMemory getPerceptualMemory() { return perceptualMemory; }

    // ==================== 辅助 ====================

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public void close() {
        if (ownDocStore) docStore.close();
    }
}
