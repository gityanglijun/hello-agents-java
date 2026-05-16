package com.example.agent.store;

import com.example.agent.memory.MemoryManager;
import com.example.agent.rag.RAGTool;
import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * SQLite 文档存储 — 统一管理记忆条目、情景、文档和分块。
 * 替代内存 LinkedHashMap，提供持久化 CRUD。
 */
public class DocumentStore implements AutoCloseable {

    private final Connection conn;

    public DocumentStore(String dbPath) {
        try {
            boolean isMemory = ":memory:".equals(dbPath);
            if (!isMemory) {
                Path path = Path.of(dbPath);
                Files.createDirectories(path.getParent());
            }
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            if (!isMemory) {
                try (Statement pragmaStmt = conn.createStatement()) {
                    pragmaStmt.execute("PRAGMA journal_mode=WAL");
                }
            }
            createTables();
        } catch (SQLException | java.io.IOException e) {
            throw new RuntimeException("DocumentStore 初始化失败: " + e.getMessage(), e);
        }
    }

    // ==================== 建表 ====================

    private void createTables() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memory_items (
                    id TEXT PRIMARY KEY,
                    content TEXT NOT NULL,
                    memory_type TEXT DEFAULT 'working',
                    importance REAL DEFAULT 0.5,
                    metadata TEXT DEFAULT '{}',
                    created_at TEXT NOT NULL,
                    updated_at TEXT
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mi_type ON memory_items(memory_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mi_importance ON memory_items(importance)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS episodes (
                    episode_id TEXT PRIMARY KEY,
                    session_id TEXT DEFAULT 'default',
                    timestamp TEXT,
                    content TEXT,
                    context TEXT DEFAULT '{}'
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ep_session ON episodes(session_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    content TEXT,
                    metadata TEXT DEFAULT '{}',
                    created_at TEXT
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    id TEXT PRIMARY KEY,
                    doc_id TEXT REFERENCES documents(id),
                    chunk_index INTEGER,
                    content TEXT,
                    start_char INTEGER,
                    end_char INTEGER,
                    heading_path TEXT
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunk_doc ON chunks(doc_id)");
        }
    }

    // ==================== MemoryItem ====================

    public void insertMemoryItem(MemoryManager.MemoryItem item) {
        String sql = "INSERT OR REPLACE INTO memory_items (id, content, memory_type, importance, metadata, created_at, updated_at) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item.id);
            ps.setString(2, item.content);
            ps.setString(3, item.memoryType);
            ps.setDouble(4, item.importance);
            ps.setString(5, mapToJson(item.metadata));
            ps.setString(6, item.createdAt);
            ps.setString(7, item.updatedAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 插入 memory_item 失败: " + e.getMessage());
        }
    }

    public MemoryManager.MemoryItem getMemoryItem(String id) {
        // 先精确匹配，再前缀匹配
        MemoryManager.MemoryItem item = getMemoryItemExact(id);
        if (item != null) return item;
        return getMemoryItemByPrefix(id);
    }

    private MemoryManager.MemoryItem getMemoryItemExact(String id) {
        String sql = "SELECT * FROM memory_items WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMemoryItem(rs);
            }
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 查询 memory_item 失败: " + e.getMessage());
        }
        return null;
    }

    private MemoryManager.MemoryItem getMemoryItemByPrefix(String prefix) {
        String sql = "SELECT * FROM memory_items WHERE id LIKE ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMemoryItem(rs);
            }
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 前缀查询 memory_item 失败: " + e.getMessage());
        }
        return null;
    }

    public List<MemoryManager.MemoryItem> getAllMemoryItems() {
        List<MemoryManager.MemoryItem> list = new ArrayList<>();
        String sql = "SELECT * FROM memory_items ORDER BY created_at DESC";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(rowToMemoryItem(rs));
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 查询所有 memory_items 失败: " + e.getMessage());
        }
        return list;
    }

    public List<MemoryManager.MemoryItem> getMemoryItemsByType(String type) {
        List<MemoryManager.MemoryItem> list = new ArrayList<>();
        String sql = "SELECT * FROM memory_items WHERE memory_type = ? ORDER BY created_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rowToMemoryItem(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 按类型查询 memory_items 失败: " + e.getMessage());
        }
        return list;
    }

    public boolean updateMemoryItem(String id, String content, Double importance, Map<String, String> metadata) {
        MemoryManager.MemoryItem existing = getMemoryItem(id);
        if (existing == null) return false;

        String newContent = content != null ? content : existing.content;
        double newImp = importance != null ? importance : existing.importance;
        Map<String, String> newMeta = metadata != null ? mergeMeta(existing.metadata, metadata) : existing.metadata;
        String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        String sql = "UPDATE memory_items SET content=?, importance=?, metadata=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newContent);
            ps.setDouble(2, newImp);
            ps.setString(3, mapToJson(newMeta));
            ps.setString(4, now);
            ps.setString(5, existing.id);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 更新 memory_item 失败: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteMemoryItem(String id) {
        // 先精确再前缀
        MemoryManager.MemoryItem item = getMemoryItem(id);
        if (item == null) return false;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM memory_items WHERE id = ?")) {
            ps.setString(1, item.id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 删除 memory_item 失败: " + e.getMessage());
            return false;
        }
    }

    public int countMemoryItems() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM memory_items")) {
            return rs.getInt(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    public Map<String, Long> countMemoryItemsByType() {
        Map<String, Long> map = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT memory_type, COUNT(*) FROM memory_items GROUP BY memory_type")) {
            while (rs.next()) map.put(rs.getString(1), rs.getLong(2));
        } catch (SQLException ignored) {}
        return map;
    }

    public double averageImportance() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT AVG(importance) FROM memory_items")) {
            return rs.getDouble(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 查询遗忘策略将要删除的 ID 列表（用于同步清理记忆类内部存储） */
    public List<String> getMemoryItemIdsByStrategy(String strategy, double threshold, int maxAgeDays) {
        List<String> ids = new ArrayList<>();
        try {
            switch (strategy) {
                case "importance_based":
                    try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM memory_items WHERE importance < ?")) {
                        ps.setDouble(1, threshold);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) ids.add(rs.getString(1));
                        }
                    }
                    break;
                case "time_based": {
                    String cutoff = java.time.LocalDateTime.now().minus(maxAgeDays, java.time.temporal.ChronoUnit.DAYS)
                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM memory_items WHERE created_at < ?")) {
                        ps.setString(1, cutoff);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) ids.add(rs.getString(1));
                        }
                    }
                    break;
                }
                case "capacity_based": {
                    int maxCap = (int) threshold;
                    int total = countMemoryItems();
                    if (maxCap > 0 && total > maxCap) {
                        try (Statement stmt = conn.createStatement();
                             ResultSet rs = stmt.executeQuery(
                                     "SELECT id FROM memory_items ORDER BY importance ASC LIMIT " + (total - maxCap))) {
                            while (rs.next()) ids.add(rs.getString(1));
                        }
                    }
                    break;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 查询待删除ID失败: " + e.getMessage());
        }
        return ids;
    }

    public int deleteMemoryItems(String strategy, double threshold, int maxAgeDays) {
        int before = countMemoryItems();
        try {
            switch (strategy) {
                case "importance_based":
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM memory_items WHERE importance < ?")) {
                        ps.setDouble(1, threshold);
                        ps.executeUpdate();
                    }
                    break;
                case "time_based": {
                    String cutoff = java.time.LocalDateTime.now().minus(maxAgeDays, java.time.temporal.ChronoUnit.DAYS)
                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM memory_items WHERE created_at < ?")) {
                        ps.setString(1, cutoff);
                        ps.executeUpdate();
                    }
                    break;
                }
                case "capacity_based":
                    int maxCap = (int) threshold;
                    if (maxCap > 0 && before > maxCap) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("DELETE FROM memory_items WHERE id IN (SELECT id FROM memory_items ORDER BY importance ASC LIMIT " + (before - maxCap) + ")");
                        }
                    }
                    break;
                default:
                    return 0;
            }
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 遗忘策略执行失败: " + e.getMessage());
        }
        return before - countMemoryItems();
    }

    // ==================== Episode ====================

    public void insertEpisode(String episodeId, String sessionId, String timestamp,
                              String content, Map<String, String> context) {
        String sql = "INSERT OR REPLACE INTO episodes (episode_id, session_id, timestamp, content, context) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, episodeId);
            ps.setString(2, sessionId != null ? sessionId : "default");
            ps.setString(3, timestamp);
            ps.setString(4, content);
            ps.setString(5, mapToJson(context));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 插入 episode 失败: " + e.getMessage());
        }
    }

    public Map<String, String> getEpisodeRaw(String episodeId) {
        String sql = "SELECT * FROM episodes WHERE episode_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, episodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("episodeId", rs.getString("episode_id"));
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("timestamp", rs.getString("timestamp"));
                    row.put("content", rs.getString("content"));
                    row.put("context", rs.getString("context"));
                    return row;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 查询 episode 失败: " + e.getMessage());
        }
        return null;
    }

    public List<Map<String, String>> getEpisodesBySession(String sessionId) {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "SELECT * FROM episodes WHERE session_id = ? ORDER BY timestamp DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("episodeId", rs.getString("episode_id"));
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("timestamp", rs.getString("timestamp"));
                    row.put("content", rs.getString("content"));
                    row.put("context", rs.getString("context"));
                    list.add(row);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 按会话查询 episodes 失败: " + e.getMessage());
        }
        return list;
    }

    public List<Map<String, String>> getAllEpisodes() {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "SELECT * FROM episodes ORDER BY timestamp DESC";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("episodeId", rs.getString("episode_id"));
                row.put("sessionId", rs.getString("session_id"));
                row.put("timestamp", rs.getString("timestamp"));
                row.put("content", rs.getString("content"));
                row.put("context", rs.getString("context"));
                list.add(row);
            }
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 查询所有 episodes 失败: " + e.getMessage());
        }
        return list;
    }

    public List<String> listEpisodeSessions() {
        List<String> sessions = new ArrayList<>();
        String sql = "SELECT DISTINCT session_id FROM episodes ORDER BY session_id";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) sessions.add(rs.getString(1));
        } catch (SQLException ignored) {}
        return sessions;
    }

    public void deleteEpisode(String episodeId) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM episodes WHERE episode_id = ?")) {
            ps.setString(1, episodeId);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public int countEpisodes() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM episodes")) {
            return rs.getInt(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    // ==================== Document ====================

    public void insertDocument(String id, String title, String content,
                               Map<String, String> metadata, String createdAt) {
        String sql = "INSERT OR REPLACE INTO documents (id, title, content, metadata, created_at) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, title);
            ps.setString(3, content);
            ps.setString(4, mapToJson(metadata));
            ps.setString(5, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 插入 document 失败: " + e.getMessage());
        }
    }

    public RAGTool.Document getDocument(String idOrPrefix) {
        // 先精确匹配
        RAGTool.Document doc = getDocumentExact(idOrPrefix);
        if (doc != null) return doc;
        // 前缀匹配
        String sql = "SELECT * FROM documents WHERE id LIKE ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, idOrPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToDocument(rs);
            }
        } catch (SQLException ignored) {}
        return null;
    }

    private RAGTool.Document getDocumentExact(String id) {
        String sql = "SELECT * FROM documents WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToDocument(rs);
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public List<RAGTool.Document> getAllDocuments() {
        List<RAGTool.Document> list = new ArrayList<>();
        String sql = "SELECT * FROM documents ORDER BY created_at DESC";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(rowToDocument(rs));
        } catch (SQLException ignored) {}
        return list;
    }

    public void deleteDocument(String id) {
        RAGTool.Document doc = getDocument(id);
        if (doc == null) return;
        try {
            // 级联删除分块
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM chunks WHERE doc_id = ?")) {
                ps.setString(1, doc.id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM documents WHERE id = ?")) {
                ps.setString(1, doc.id);
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}
    }

    public int countDocuments() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM documents")) {
            return rs.getInt(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    // ==================== Chunk ====================

    public void insertChunk(String id, String docId, int index, String content,
                            int startChar, int endChar, String headingPath) {
        String sql = "INSERT OR REPLACE INTO chunks (id, doc_id, chunk_index, content, start_char, end_char, heading_path) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, docId);
            ps.setInt(3, index);
            ps.setString(4, content);
            ps.setInt(5, startChar);
            ps.setInt(6, endChar);
            ps.setString(7, headingPath);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DocumentStore] 插入 chunk 失败: " + e.getMessage());
        }
    }

    public Map<String, String> getChunkRaw(String chunkId) {
        String sql = "SELECT * FROM chunks WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chunkId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToChunkMap(rs);
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public List<Map<String, String>> getChunksByDocId(String docId) {
        List<Map<String, String>> list = new ArrayList<>();
        String sql = "SELECT * FROM chunks WHERE doc_id = ? ORDER BY chunk_index";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rowToChunkMap(rs));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    public void deleteChunksByDocId(String docId) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM chunks WHERE doc_id = ?")) {
            ps.setString(1, docId);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public int countChunks() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM chunks")) {
            return rs.getInt(1);
        } catch (SQLException e) {
            return 0;
        }
    }

    // ==================== 辅助 ====================

    private MemoryManager.MemoryItem rowToMemoryItem(ResultSet rs) throws SQLException {
        return new MemoryManager.MemoryItem(
                rs.getString("id"),
                rs.getString("content"),
                rs.getString("memory_type"),
                rs.getDouble("importance"),
                jsonToMap(rs.getString("metadata")),
                rs.getString("created_at")
        );
    }

    private RAGTool.Document rowToDocument(ResultSet rs) throws SQLException {
        return new RAGTool.Document(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("content"),
                jsonToMap(rs.getString("metadata")),
                rs.getString("created_at")
        );
    }

    private Map<String, String> rowToChunkMap(ResultSet rs) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("docId", rs.getString("doc_id"));
        m.put("index", String.valueOf(rs.getInt("chunk_index")));
        m.put("content", rs.getString("content"));
        m.put("startChar", String.valueOf(rs.getInt("start_char")));
        m.put("endChar", String.valueOf(rs.getInt("end_char")));
        m.put("headingPath", rs.getString("heading_path"));
        return m;
    }

    // ==================== JSON 工具 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, String> jsonToMap(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return new LinkedHashMap<>();
        try {
            return new com.google.gson.Gson().fromJson(json,
                    new com.google.gson.reflect.TypeToken<Map<String, String>>(){}.getType());
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String mapToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        return new com.google.gson.Gson().toJson(map);
    }

    private static Map<String, String> mergeMeta(Map<String, String> base, Map<String, String> updates) {
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(updates);
        return merged;
    }

    // ==================== 生命周期 ====================

    public void clearAll() {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM memory_items");
            stmt.execute("DELETE FROM episodes");
            stmt.execute("DELETE FROM chunks");
            stmt.execute("DELETE FROM documents");
        } catch (SQLException ignored) {}
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {}
    }
}
