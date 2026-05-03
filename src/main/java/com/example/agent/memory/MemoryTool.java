package com.example.agent.memory;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolParameter;

public class MemoryTool extends Tool {

    // ==================== 操作名称 ====================
    private static final String OP_ADD = "add";
    private static final String OP_SEARCH = "search";
    private static final String OP_SUMMARY = "summary";
    private static final String OP_STATS = "stats";
    private static final String OP_UPDATE = "update";
    private static final String OP_REMOVE = "remove";
    private static final String OP_FORGET = "forget";
    private static final String OP_CONSOLIDATE = "consolidate";
    private static final String OP_CLEAR_ALL = "clear_all";
    private static final String ALL_OPS = "add, search, summary, stats, update, remove, forget, consolidate, clear_all";

    // ==================== 参数名称 ====================
    private static final String P_ACTION = "action";
    private static final String P_CONTENT = "content";
    private static final String P_MEMORY_TYPE = "memory_type";
    private static final String P_MEMORY_TYPES = "memory_types";
    private static final String P_IMPORTANCE = "importance";
    private static final String P_MIN_IMPORTANCE = "min_importance";
    private static final String P_QUERY = "query";
    private static final String P_LIMIT = "limit";
    private static final String P_STRATEGY = "strategy";
    private static final String P_THRESHOLD = "threshold";
    private static final String P_MAX_AGE_DAYS = "max_age_days";
    private static final String P_FROM_TYPE = "from_type";
    private static final String P_TO_TYPE = "to_type";
    private static final String P_ID = "id";
    private static final String P_FILE_PATH = "file_path";
    private static final String P_MODALITY = "modality";

    // ==================== 记忆类型 ====================
    private static final String T_WORKING = "working";
    private static final String T_EPISODIC = "episodic";
    private static final String T_SEMANTIC = "semantic";
    private static final String T_PERCEPTUAL = "perceptual";

    // ==================== 遗忘策略 ====================
    private static final String S_IMPORTANCE_BASED = "importance_based";
    private static final String S_TIME_BASED = "time_based";
    private static final String S_CAPACITY_BASED = "capacity_based";
    private static final Map<String, String> STRATEGY_LABELS = Map.of(
            S_IMPORTANCE_BASED, "基于重要性",
            S_TIME_BASED, "基于时间",
            S_CAPACITY_BASED, "基于容量"
    );

    // ==================== 模态 ====================
    private static final String M_IMAGE = "image";
    private static final String M_VIDEO = "video";
    private static final String M_AUDIO = "audio";
    private static final String M_TEXT = "text";

    // ==================== 元数据 key ====================
    private static final String META_SESSION_ID = "session_id";
    private static final String META_TIMESTAMP = "timestamp";
    private static final String META_MODALITY = "modality";
    private static final String META_RAW_DATA = "raw_data";

    // ==================== 默认值 ====================
    private static final double DEFAULT_IMPORTANCE = 0.5;
    private static final double DEFAULT_MIN_IMPORTANCE = 0.1;
    private static final double DEFAULT_CONSOLIDATE_THRESHOLD = 0.7;
    private static final int DEFAULT_LIMIT = 5;
    private static final int DEFAULT_MAX_AGE_DAYS = 30;

    private final MemoryManager memoryManager;
    private String currentSessionId;

    public MemoryTool() {
        super("memory", "统一记忆管理工具。支持添加、搜索、更新、删除、遗忘、整合等多种记忆操作。");
        this.memoryManager = new MemoryManager();
        this.currentSessionId = null;
    }

    public MemoryTool(MemoryManager memoryManager) {
        super("memory", "统一记忆管理工具。支持添加、搜索、更新、删除、遗忘、整合等多种记忆操作。");
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager();
        this.currentSessionId = null;
    }

    // ==================== 统一入口 ====================

    /** 分发处理 */
    public String execute(String action, Map<String, Object> kwargs) {
        Map<String, Object> params = kwargs != null ? new HashMap<>(kwargs) : new HashMap<>();
        params.put(P_ACTION, action);
        return run(params);
    }

    /** 便捷入口：键值对交替传参 */
    public String execute(String action, Object... keyValues) {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            kwargs.put((String) keyValues[i], keyValues[i + 1]);
        }
        return execute(action, kwargs);
    }

    @Override
    public String run(Map<String, Object> parameters) {
        String action = (String) parameters.getOrDefault(P_ACTION, OP_ADD);

        switch (action) {
            case OP_ADD:         return addMemory(parameters);
            case OP_SEARCH:      return searchMemory(parameters);
            case OP_SUMMARY:     return getSummary();
            case OP_STATS:       return getStats();
            case OP_UPDATE:      return updateMemory(parameters);
            case OP_REMOVE:      return removeMemory(parameters);
            case OP_FORGET:      return forget(parameters);
            case OP_CONSOLIDATE: return consolidate(parameters);
            case OP_CLEAR_ALL:   return clearAll();
            default:
                return "❌ 不支持的操作: " + action + "\n支持的操作: " + ALL_OPS;
        }
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter(P_ACTION, "string", "操作类型: " + ALL_OPS),
                new ToolParameter(P_CONTENT, "string", "记忆内容"),
                new ToolParameter(P_MEMORY_TYPE, "string", "记忆类型: working(工作记忆), episodic(情景记忆), semantic(语义记忆), perceptual(感知记忆)"),
                new ToolParameter(P_IMPORTANCE, "number", "重要性 (0.0~1.0)"),
                new ToolParameter(P_QUERY, "string", "搜索关键词"),
                new ToolParameter(P_LIMIT, "integer", "返回结果数量上限"),
                new ToolParameter(P_MEMORY_TYPES, "array", "按记忆类型过滤（可多选）"),
                new ToolParameter(P_MIN_IMPORTANCE, "number", "最低重要性过滤"),
                new ToolParameter(P_STRATEGY, "string", "遗忘策略: importance_based, time_based, capacity_based"),
                new ToolParameter(P_THRESHOLD, "number", "阈值（遗忘/整合共用）"),
                new ToolParameter(P_MAX_AGE_DAYS, "integer", "基于时间的遗忘：最大保留天数"),
                new ToolParameter(P_FROM_TYPE, "string", "整合：源记忆类型"),
                new ToolParameter(P_TO_TYPE, "string", "整合：目标记忆类型"),
                new ToolParameter(P_ID, "string", "记忆ID（update/remove 操作使用）"),
                new ToolParameter(P_FILE_PATH, "string", "感知记忆文件路径"),
                new ToolParameter(P_MODALITY, "string", "感知模态: image, audio, video, text")
        );
    }

    // ==================== 操作1: add ====================

    private String addMemory(Map<String, Object> params) {
        try {
            ensureSession();

            String content = (String) params.getOrDefault(P_CONTENT, "");
            String memoryType = (String) params.getOrDefault(P_MEMORY_TYPE, T_WORKING);
            double importance = getDouble(params, P_IMPORTANCE, DEFAULT_IMPORTANCE);
            String filePath = (String) params.getOrDefault(P_FILE_PATH, null);
            String modality = (String) params.getOrDefault(P_MODALITY, null);

            Map<String, String> metadata = new LinkedHashMap<>();

            if (T_PERCEPTUAL.equals(memoryType) && filePath != null) {
                String inferred = modality != null ? modality : inferModality(filePath);
                metadata.put(META_MODALITY, inferred);
                metadata.put(META_RAW_DATA, filePath);
            }

            metadata.put(META_SESSION_ID, currentSessionId);
            metadata.put(META_TIMESTAMP, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            Set<String> reserved = Set.of(P_ACTION, P_CONTENT, P_MEMORY_TYPE, P_IMPORTANCE,
                    P_FILE_PATH, P_MODALITY, P_MEMORY_TYPES, P_QUERY);
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (!reserved.contains(entry.getKey()) && entry.getValue() instanceof String) {
                    metadata.put(entry.getKey(), (String) entry.getValue());
                }
            }

            String memoryId = memoryManager.addMemory(content, memoryType, importance, metadata, false);
            return "✅ 记忆已添加 (ID: " + memoryId.substring(0, 8) + "..., 类型: " + memoryType + ")";

        } catch (Exception e) {
            return "❌ 添加记忆失败: " + e.getMessage();
        }
    }

    private String inferModality(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.matches(".*\\.(png|jpg|jpeg|gif|bmp|webp|svg)$")) return M_IMAGE;
        if (lower.matches(".*\\.(mp4|avi|mov|mkv|webm|flv)$")) return M_VIDEO;
        if (lower.matches(".*\\.(mp3|wav|ogg|flac|aac|m4a)$")) return M_AUDIO;
        return M_TEXT;
    }

    // ==================== 操作2: search ====================

    @SuppressWarnings("unchecked")
    private String searchMemory(Map<String, Object> params) {
        try {
            String query = (String) params.getOrDefault(P_QUERY, "");
            int limit = getInt(params, P_LIMIT, DEFAULT_LIMIT);
            double minImportance = getDouble(params, P_MIN_IMPORTANCE, DEFAULT_MIN_IMPORTANCE);

            List<String> memoryTypes = null;
            if (params.get(P_MEMORY_TYPES) instanceof List) {
                memoryTypes = (List<String>) params.get(P_MEMORY_TYPES);
            } else if (params.containsKey(P_MEMORY_TYPE)) {
                memoryTypes = List.of((String) params.get(P_MEMORY_TYPE));
            }

            List<MemoryManager.MemoryItem> results =
                    memoryManager.retrieveMemories(query, limit, memoryTypes, minImportance);

            if (results.isEmpty()) {
                return "🔍 未找到与 '" + query + "' 相关的记忆";
            }

            Map<String, String> labels = MemoryManager.typeLabels();

            StringBuilder sb = new StringBuilder();
            sb.append("🔍 找到 ").append(results.size()).append(" 条相关记忆:\n");

            int i = 1;
            for (MemoryManager.MemoryItem mem : results) {
                String label = labels.getOrDefault(mem.memoryType, mem.memoryType);
                String preview = mem.content.length() > 80
                        ? mem.content.substring(0, 80) + "..." : mem.content;
                sb.append(i).append(". [").append(label).append("] ")
                        .append(preview)
                        .append(" (重要性: ").append(String.format("%.2f", mem.importance)).append(")\n");
                i++;
            }

            return sb.toString().trim();

        } catch (Exception e) {
            return "❌ 搜索记忆失败: " + e.getMessage();
        }
    }

    // ==================== 操作3: summary ====================

    private String getSummary() {
        return memoryManager.getSummary();
    }

    // ==================== 操作4: stats ====================

    private String getStats() {
        int total = memoryManager.totalCount();
        if (total == 0) return "📊 暂无记忆数据";

        Map<String, Long> byType = memoryManager.countByType();
        double avgImp = memoryManager.averageImportance();
        Map<String, String> labels = MemoryManager.typeLabels();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 记忆统计:\n");
        sb.append("  总记忆数: ").append(total).append("\n");
        sb.append("  平均重要性: ").append(String.format("%.2f", avgImp)).append("\n");
        sb.append("  类型分布:\n");
        for (Map.Entry<String, Long> e : byType.entrySet()) {
            double pct = total > 0 ? (double) e.getValue() / total * 100 : 0;
            String label = labels.getOrDefault(e.getKey(), e.getKey());
            sb.append("    ").append(label).append(": ").append(e.getValue())
                    .append(" (").append(String.format("%.1f", pct)).append("%)\n");
        }

        return sb.toString().trim();
    }

    // ==================== 操作5: update ====================

    private String updateMemory(Map<String, Object> params) {
        try {
            String id = (String) params.get(P_ID);
            if (id == null || id.isBlank()) return "❌ 更新记忆需要提供 id 参数";

            String content = (String) params.get(P_CONTENT);
            Double importance = params.containsKey(P_IMPORTANCE)
                    ? getDouble(params, P_IMPORTANCE, DEFAULT_IMPORTANCE) : null;

            Map<String, String> metadata = new LinkedHashMap<>();
            Set<String> reserved = Set.of(P_ACTION, P_ID, P_CONTENT, P_IMPORTANCE);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (!reserved.contains(e.getKey()) && e.getValue() instanceof String) {
                    metadata.put(e.getKey(), (String) e.getValue());
                }
            }

            boolean ok = memoryManager.updateMemory(id, content, importance,
                    metadata.isEmpty() ? null : metadata);
            return ok ? "✅ 记忆已更新 (ID: " + id.substring(0, 8) + "...)"
                    : "❌ 未找到记忆: " + id;

        } catch (Exception e) {
            return "❌ 更新记忆失败: " + e.getMessage();
        }
    }

    // ==================== 操作6: remove ====================

    private String removeMemory(Map<String, Object> params) {
        try {
            String id = (String) params.get(P_ID);
            if (id == null || id.isBlank()) return "❌ 删除记忆需要提供 id 参数";

            boolean ok = memoryManager.removeMemory(id);
            return ok ? "🗑️ 记忆已删除 (ID: " + id.substring(0, 8) + "...)"
                    : "❌ 未找到记忆: " + id;

        } catch (Exception e) {
            return "❌ 删除记忆失败: " + e.getMessage();
        }
    }

    // ==================== 操作7: forget ====================

    private String forget(Map<String, Object> params) {
        try {
            String strategy = (String) params.getOrDefault(P_STRATEGY, S_IMPORTANCE_BASED);
            double threshold = getDouble(params, P_THRESHOLD, DEFAULT_MIN_IMPORTANCE);
            int maxAgeDays = getInt(params, P_MAX_AGE_DAYS, DEFAULT_MAX_AGE_DAYS);

            int count = memoryManager.forgetMemories(strategy, threshold, maxAgeDays);

            String label = STRATEGY_LABELS.getOrDefault(strategy, strategy);
            return "🧹 已遗忘 " + count + " 条记忆（策略: " + label + "）";

        } catch (Exception e) {
            return "❌ 遗忘记忆失败: " + e.getMessage();
        }
    }

    // ==================== 操作8: consolidate ====================

    private String consolidate(Map<String, Object> params) {
        try {
            String fromType = (String) params.getOrDefault(P_FROM_TYPE, T_WORKING);
            String toType = (String) params.getOrDefault(P_TO_TYPE, T_EPISODIC);
            double threshold = getDouble(params, P_THRESHOLD, DEFAULT_CONSOLIDATE_THRESHOLD);

            int count = memoryManager.consolidateMemories(fromType, toType, threshold);

            return "🔄 已整合 " + count + " 条记忆为长期记忆（"
                    + fromType + " → " + toType + "，阈值=" + threshold + "）";

        } catch (Exception e) {
            return "❌ 整合记忆失败: " + e.getMessage();
        }
    }

    // ==================== 操作9: clear_all ====================

    private String clearAll() {
        int count = memoryManager.clearAll();
        return "🗑️ 已清空所有记忆（共 " + count + " 条）";
    }

    // ==================== 辅助方法 ====================

    private void ensureSession() {
        if (currentSessionId == null) {
            currentSessionId = "session_" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        }
    }

    private double getDouble(Map<String, Object> params, String key, double defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private int getInt(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    // ==================== 公开访问器 ====================

    public MemoryManager getMemoryManager() { return memoryManager; }
    public String getCurrentSessionId() { return currentSessionId; }
    public void setCurrentSessionId(String sessionId) { this.currentSessionId = sessionId; }
}
