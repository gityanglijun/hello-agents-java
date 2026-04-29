import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class MemoryManager {

    // 记忆类型
    public static final String TYPE_WORKING = "working";
    public static final String TYPE_EPISODIC = "episodic";
    public static final String TYPE_SEMANTIC = "semantic";
    public static final String TYPE_PERCEPTUAL = "perceptual";

    // 遗忘策略
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

    private final List<MemoryItem> memories = new ArrayList<>();

    // ==================== 添加记忆 ====================

    public String addMemory(String content, String memoryType, double importance,
                            Map<String, String> metadata, boolean autoClassify) {
        String id = UUID.randomUUID().toString();
        String now = now();

        if (autoClassify) {
            memoryType = autoClassify(content, memoryType);
        }

        MemoryItem item = new MemoryItem(id, content, memoryType, importance, metadata, now);
        memories.add(item);
        return id;
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
        // 类型过滤条件
        boolean typeFilterActive = memoryTypes != null && !memoryTypes.isEmpty();

        if (query == null || query.isBlank()) {
            return memories.stream()
                    .filter(m -> m.importance >= minImportance)
                    .filter(m -> !typeFilterActive || memoryTypes.contains(m.memoryType))
                    .sorted(Comparator.comparingDouble((MemoryItem m) -> m.importance).reversed())
                    .limit(limit > 0 ? limit : 5)
                    .collect(Collectors.toList());
        }

        String[] keywords = query.toLowerCase().split("\\s+");

        return memories.stream()
                .filter(m -> m.importance >= minImportance)
                .filter(m -> !typeFilterActive || memoryTypes.contains(m.memoryType))
                .map(m -> new AbstractMap.SimpleEntry<>(m, keywordScore(m, keywords)))
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit > 0 ? limit : 5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private double keywordScore(MemoryItem item, String[] keywords) {
        String content = item.content.toLowerCase();
        int matches = 0;
        for (String kw : keywords) {
            if (content.contains(kw)) matches++;
        }
        double keywordRatio = (double) matches / keywords.length;
        return keywordRatio * 0.7 + item.importance * 0.3;
    }

    // ==================== 单条操作 ====================

    public MemoryItem getMemory(String id) {
        // 先精确匹配，再前缀匹配
        MemoryItem exact = memories.stream().filter(m -> m.id.equals(id)).findFirst().orElse(null);
        if (exact != null) return exact;
        return memories.stream().filter(m -> m.id.startsWith(id)).findFirst().orElse(null);
    }

    public boolean updateMemory(String id, String content, Double importance, Map<String, String> metadata) {
        MemoryItem item = getMemory(id);
        if (item == null) return false;
        if (content != null) item.content = content;
        if (importance != null) item.importance = Math.max(0.0, Math.min(1.0, importance));
        if (metadata != null) item.metadata.putAll(metadata);
        item.updatedAt = now();
        return true;
    }

    public boolean removeMemory(String id) {
        // 先精确匹配，再前缀匹配
        boolean removed = memories.removeIf(m -> m.id.equals(id));
        if (!removed) {
            removed = memories.removeIf(m -> m.id.startsWith(id));
        }
        return removed;
    }

    // ==================== 遗忘策略 ====================

    public int forgetMemories(String strategy, double threshold, int maxAgeDays) {
        int before = memories.size();

        switch (strategy) {
            case STRATEGY_IMPORTANCE_BASED:
                memories.removeIf(m -> m.importance < threshold);
                break;

            case STRATEGY_TIME_BASED:
                LocalDateTime cutoff = LocalDateTime.now().minus(maxAgeDays, ChronoUnit.DAYS);
                memories.removeIf(m -> {
                    try {
                        LocalDateTime created = LocalDateTime.parse(m.createdAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                        return created.isBefore(cutoff);
                    } catch (Exception e) {
                        return false;
                    }
                });
                break;

            case STRATEGY_CAPACITY_BASED:
                int maxCapacity = (int) threshold;
                if (maxCapacity > 0 && memories.size() > maxCapacity) {
                    memories.sort(Comparator.comparingDouble(m -> m.importance));
                    int toRemove = memories.size() - maxCapacity;
                    memories.subList(0, toRemove).clear();
                }
                break;

            default:
                return 0;
        }

        return before - memories.size();
    }

    // ==================== 整合记忆 ====================

    public int consolidateMemories(String fromType, String toType, double importanceThreshold) {
        int count = 0;
        for (MemoryItem item : memories) {
            if (item.memoryType.equals(fromType) && item.importance >= importanceThreshold) {
                item.memoryType = toType;
                item.updatedAt = now();
                count++;
            }
        }
        return count;
    }

    // ==================== 清空与统计 ====================

    public int clearAll() {
        int count = memories.size();
        memories.clear();
        return count;
    }

    public int totalCount() {
        return memories.size();
    }

    public Map<String, Long> countByType() {
        return memories.stream()
                .collect(Collectors.groupingBy(m -> m.memoryType, LinkedHashMap::new, Collectors.counting()));
    }

    public double averageImportance() {
        return memories.stream().mapToDouble(m -> m.importance).average().orElse(0);
    }

    // ==================== 摘要 ====================

    public String getSummary() {
        int total = memories.size();
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
        memories.stream()
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

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
