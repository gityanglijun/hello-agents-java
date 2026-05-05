package com.example.agent.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 笔记工具 — Markdown + YAML frontmatter 混合格式。
 *
 * 每个笔记是一个 .md 文件，YAML 头包含元数据，正文为 Markdown 自由格式。
 * 维护 notes_index.json 索引文件用于快速检索。
 *
 * 文件结构:
 *   notes/
 *   ├── notes_index.json
 *   ├── note_20250119_153000_0.md
 *   └── note_20250119_160000_0.md
 */
public class NoteTool {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter FILE_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path notesDir;
    private final Path indexPath;
    private final Map<String, Map<String, Object>> index;
    private int counter; // 同秒去重计数

    // ==================== 构造 ====================

    public NoteTool() {
        this(Paths.get("notes"));
    }

    public NoteTool(Path notesDir) {
        this.notesDir = notesDir;
        this.indexPath = notesDir.resolve("notes_index.json");

        try {
            Files.createDirectories(notesDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建笔记目录: " + notesDir, e);
        }

        this.index = loadIndex();
    }

    // ==================== 添加笔记 ====================

    /**
     * 添加笔记。
     *
     * @param content 正文 (Markdown)
     * @param type    类型标签 (task_state / knowledge / idea / meeting / general)
     * @param tags    标签列表
     * @param title   标题 (可选，默认从正文首行 # 标题提取)
     * @return 笔记 ID
     */
    public String addNote(String content, String type, List<String> tags, String title) {
        String id = generateId();
        String now = LocalDateTime.now().format(ISO);

        if (title == null || title.isBlank()) {
            title = extractTitle(content);
        }
        if (type == null || type.isBlank()) {
            type = "general";
        }
        if (tags == null) {
            tags = Collections.emptyList();
        }

        String fileName = id + ".md";
        Path filePath = notesDir.resolve(fileName);

        // 写入笔记文件
        String frontmatter = formatFrontmatter(id, title, type, tags, now, now);
        String fileContent = frontmatter + "\n" + content;
        try {
            Files.writeString(filePath, fileContent);
        } catch (IOException e) {
            throw new RuntimeException("写入笔记文件失败: " + filePath, e);
        }

        // 更新索引
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("title", title);
        entry.put("type", type);
        entry.put("tags", new ArrayList<>(tags));
        entry.put("created_at", now);
        entry.put("updated_at", now);
        entry.put("file_path", filePath.toString());
        index.put(id, entry);
        saveIndex();

        return id;
    }

    /** 便捷方法 */
    public String addNote(String content) {
        return addNote(content, "general", Collections.emptyList(), null);
    }

    public String addNote(String content, String type) {
        return addNote(content, type, Collections.emptyList(), null);
    }

    // ==================== 读取笔记 ====================

    /**
     * 读取笔记全文（含解析后的元数据 + 正文）。
     * @return {@link Note} 对象，不存在时返回 null
     */
    public Note getNote(String noteId) {
        Map<String, Object> entry = index.get(noteId);
        if (entry == null) return null;

        Path filePath = Paths.get((String) entry.get("file_path"));
        if (!Files.exists(filePath)) return null;

        try {
            String raw = Files.readString(filePath);
            Note note = parseNoteFile(raw, noteId);
            return note;
        } catch (IOException e) {
            return null;
        }
    }

    /** 只读正文（不含 frontmatter） */
    public String getNoteContent(String noteId) {
        Note note = getNote(noteId);
        return note != null ? note.content : null;
    }

    // ==================== 更新笔记 ====================

    /**
     * 更新笔记内容/标题/标签。未提供的字段保持不变。
     * @return 操作结果消息
     */
    public String updateNote(String noteId, String content, String title,
                             List<String> tags, String type) {
        Map<String, Object> entry = index.get(noteId);
        if (entry == null) {
            return "❌ 笔记不存在: " + noteId;
        }

        String now = LocalDateTime.now().format(ISO);
        String newTitle = (title != null && !title.isBlank()) ? title : (String) entry.get("title");
        String newType = (type != null && !type.isBlank()) ? type : (String) entry.get("type");

        @SuppressWarnings("unchecked")
        List<String> newTags = tags != null ? new ArrayList<>(tags)
                : (List<String>) entry.getOrDefault("tags", Collections.emptyList());

        String body;
        if (content != null) {
            body = content;
        } else {
            // 保留原正文
            Note existing = getNote(noteId);
            body = existing != null ? existing.content : "";
        }

        // 重写笔记文件
        Path filePath = Paths.get((String) entry.get("file_path"));
        String frontmatter = formatFrontmatter(noteId, newTitle, newType, newTags,
                (String) entry.get("created_at"), now);
        String fileContent = frontmatter + "\n" + body;
        try {
            Files.writeString(filePath, fileContent);
        } catch (IOException e) {
            throw new RuntimeException("写入笔记文件失败: " + filePath, e);
        }

        // 更新索引
        entry.put("title", newTitle);
        entry.put("type", newType);
        entry.put("tags", newTags);
        entry.put("updated_at", now);
        saveIndex();

        return "✅ 笔记已更新: " + newTitle;
    }

    // ==================== 删除笔记 ====================

    /**
     * 删除笔记。
     * @param noteId 笔记ID
     * @return 操作结果消息
     */
    public String deleteNote(String noteId) {
        Map<String, Object> entry = index.get(noteId);
        if (entry == null) {
            return "❌ 笔记不存在: " + noteId;
        }

        // 1. 删除文件
        String filePath = (String) entry.get("file_path");
        try {
            boolean deleted = Files.deleteIfExists(Path.of(filePath));
            if (!deleted) {
                System.out.println("[NoteTool] 笔记文件已不存在: " + filePath);
            }
        } catch (IOException e) {
            return "❌ 删除笔记文件失败: " + e.getMessage();
        }

        // 2. 从索引中移除
        String title = (String) entry.getOrDefault("title", noteId);
        index.remove(noteId);
        saveIndex();

        return "✅ 笔记已删除: " + title;
    }

    // ==================== 搜索笔记 ====================

    /**
     * 按关键词 + 类型 + 标签复合搜索。
     *
     * @param query 搜索关键词（在标题和正文中匹配）
     * @param type  类型过滤 (可选)
     * @param tags  标签过滤 (可选，匹配任一标签即命中)
     * @return 匹配的笔记列表
     */
    public List<Note> searchNotes(String query, String type, List<String> tags) {
        List<Note> results = new ArrayList<>();
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasType = type != null && !type.isBlank();
        boolean hasTags = tags != null && !tags.isEmpty();
        String lowerQuery = hasQuery ? query.toLowerCase() : null;

        for (Map.Entry<String, Map<String, Object>> e : index.entrySet()) {
            Map<String, Object> entry = e.getValue();

            // 类型过滤
            if (hasType) {
                String entryType = (String) entry.get("type");
                if (!type.equals(entryType)) continue;
            }

            // 标签过滤
            if (hasTags) {
                @SuppressWarnings("unchecked")
                List<String> entryTags = (List<String>) entry.getOrDefault("tags", List.of());
                boolean matched = tags.stream().anyMatch(t -> entryTags.contains(t));
                if (!matched) continue;
            }

            // 关键词搜索
            if (hasQuery) {
                Note note = getNote(e.getKey());
                if (note == null) continue;
                String searchTarget = (note.title + " " + note.content).toLowerCase();
                if (!searchTarget.contains(lowerQuery)) continue;
                results.add(note);
            } else {
                Note note = getNote(e.getKey());
                if (note != null) results.add(note);
            }
        }

        // 按更新时间倒序
        results.sort((a, b) -> b.updatedAt.compareTo(a.updatedAt));
        return results;
    }

    public List<Note> searchNotes(String query) {
        return searchNotes(query, null, null);
    }

    // ==================== 列出笔记 ====================

    /**
     * 列出笔记（可选项类型/标签过滤）。
     * @return 匹配的笔记列表，按更新时间倒序
     */
    public List<Note> listNotes(String type, List<String> tags) {
        return searchNotes(null, type, tags);
    }

    public List<Note> listNotes() {
        return searchNotes(null, null, null);
    }

    /** 列出所有笔记 ID */
    public Set<String> listNoteIds() {
        return new LinkedHashSet<>(index.keySet());
    }

    /** 列出所有使用的类型 */
    public Set<String> listTypes() {
        return index.values().stream()
                .map(e -> (String) e.get("type"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 列表所有使用的标签（去重） */
    public Set<String> listTags() {
        Set<String> all = new LinkedHashSet<>();
        for (Map<String, Object> entry : index.values()) {
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) entry.getOrDefault("tags", List.of());
            all.addAll(tags);
        }
        return all;
    }

    /** 总笔记数 */
    public int count() {
        return index.size();
    }

    /** 重建索引（从 notes/ 目录重新扫描所有 .md 文件） */
    public String rebuildIndex() {
        index.clear();
        try {
            Files.list(notesDir)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(p -> {
                        try {
                            String raw = Files.readString(p);
                            Note note = parseNoteFile(raw, null);
                            if (note != null) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("id", note.id);
                                entry.put("title", note.title);
                                entry.put("type", note.type);
                                entry.put("tags", new ArrayList<>(note.tags));
                                entry.put("created_at", note.createdAt);
                                entry.put("updated_at", note.updatedAt);
                                entry.put("file_path", p.toString());
                                index.put(note.id, entry);
                            }
                        } catch (IOException ignored) {}
                    });
            saveIndex();
        } catch (IOException e) {
            return "❌ 重建索引失败: " + e.getMessage();
        }
        return "✅ 索引已重建，共 " + index.size() + " 条笔记";
    }

    // ==================== 内部方法 ====================

    /** 生成笔记 ID: note_yyyyMMdd_HHmmss_N */
    private String generateId() {
        String prefix = "note_" + LocalDateTime.now().format(FILE_ID_FMT);
        counter++;
        return prefix + "_" + counter;
    }

    /** 保存索引到 notes_index.json */
    private void saveIndex() {
        try {
            String json = GSON.toJson(index);
            Files.writeString(indexPath, json);
        } catch (IOException e) {
            System.err.println("[NoteTool] 保存索引失败: " + e.getMessage());
        }
    }

    /** 加载索引，文件不存在则返回空 Map */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadIndex() {
        if (Files.exists(indexPath)) {
            try {
                String json = Files.readString(indexPath);
                return GSON.fromJson(json,
                        new TypeToken<Map<String, Map<String, Object>>>(){}.getType());
            } catch (IOException e) {
                System.err.println("[NoteTool] 加载索引失败: " + e.getMessage());
            }
        }
        return new LinkedHashMap<>();
    }

    /** 格式化 YAML frontmatter */
    String formatFrontmatter(String id, String title, String type,
                             List<String> tags, String createdAt, String updatedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("id: ").append(id).append("\n");
        sb.append("title: ").append(escapeYamlValue(title)).append("\n");
        sb.append("type: ").append(type).append("\n");
        if (tags.isEmpty()) {
            sb.append("tags: []\n");
        } else {
            sb.append("tags: [");
            sb.append(tags.stream()
                    .map(this::escapeYamlValue)
                    .collect(Collectors.joining(", ")));
            sb.append("]\n");
        }
        sb.append("created_at: ").append(createdAt).append("\n");
        sb.append("updated_at: ").append(updatedAt).append("\n");
        sb.append("---");
        return sb.toString();
    }

    /** YAML 值转义（含冒号或引号的值加双引号包裹） */
    private String escapeYamlValue(String value) {
        if (value == null) return "null";
        if (value.contains(":") || value.contains("\"") || value.contains("#")
                || value.contains("[") || value.contains("]")) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    /** 解析 .md 文件为 Note 对象 */
    Note parseNoteFile(String raw, String fallbackId) {
        if (raw == null || raw.isBlank()) return null;

        Map<String, Object> meta;
        String body;

        // 检测 YAML frontmatter (--- ... ---)
        if (raw.strip().startsWith("---")) {
            int end = raw.indexOf("---", 4);
            if (end > 0) {
                String frontmatter = raw.substring(4, end).strip();
                meta = parseFrontmatter(frontmatter);
                body = raw.substring(end + 3).strip();
            } else {
                // 格式异常，整段当正文
                meta = new LinkedHashMap<>();
                body = raw.strip();
            }
        } else {
            meta = new LinkedHashMap<>();
            body = raw.strip();
        }

        String id = (String) meta.getOrDefault("id", fallbackId != null ? fallbackId : "unknown");
        String title = (String) meta.getOrDefault("title", "Untitled");
        String type = (String) meta.getOrDefault("type", "general");
        String createdAt = (String) meta.getOrDefault("created_at", "");
        String updatedAt = (String) meta.getOrDefault("updated_at", createdAt);

        @SuppressWarnings("unchecked")
        Object tagsRaw = meta.get("tags");
        List<String> tags;
        if (tagsRaw instanceof List) {
            tags = new ArrayList<>((List<String>) tagsRaw);
        } else if (tagsRaw instanceof String) {
            // 兼容 "tags: tag1, tag2" 字符串格式
            tags = Arrays.stream(((String) tagsRaw).split(","))
                    .map(String::strip).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } else {
            tags = new ArrayList<>();
        }

        return new Note(id, title, type, tags, createdAt, updatedAt, body);
    }

    /** 解析 YAML frontmatter 文本为 Map */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseFrontmatter(String yaml) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (yaml == null || yaml.isBlank()) return map;

        String[] lines = yaml.split("\n");
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();
        boolean inList = false;

        for (String line : lines) {
            // 跳过空行和注释
            if (line.isBlank() || line.strip().startsWith("#")) continue;

            // 多行值续行（以空格开头）
            if (Character.isWhitespace(line.charAt(0)) && currentKey != null) {
                currentValue.append(" ").append(line.strip());
                continue;
            }

            // 保存前一个 key-value
            if (currentKey != null) {
                map.put(currentKey, parseYamlValue(currentValue.toString().strip()));
            }

            // 解析新行
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;

            String key = line.substring(0, colonIdx).strip();
            String value = line.substring(colonIdx + 1).strip();
            currentKey = key;
            currentValue = new StringBuilder(value);
        }

        // 最后一行
        if (currentKey != null) {
            map.put(currentKey, parseYamlValue(currentValue.toString().strip()));
        }

        return map;
    }

    /** 解析 YAML 值：列表 / 字符串 */
    @SuppressWarnings("unchecked")
    private static Object parseYamlValue(String value) {
        if (value.startsWith("[") && value.endsWith("]")) {
            // 列表格式: [tag1, tag2, tag3]
            String inner = value.substring(1, value.length() - 1).strip();
            if (inner.isEmpty()) return new ArrayList<String>();
            return Arrays.stream(inner.split(","))
                    .map(s -> s.strip().replaceAll("^[\"']|[\"']$", ""))
                    .collect(Collectors.toList());
        }
        // 字符串，去掉外围引号
        return value.replaceAll("^[\"']|[\"']$", "");
    }

    /** 从正文首行 # 标题提取标题 */
    private static String extractTitle(String content) {
        if (content == null || content.isBlank()) return "Untitled";
        Matcher m = Pattern.compile("^#\\s+(.+)", Pattern.MULTILINE).matcher(content.strip());
        return m.find() ? m.group(1).strip() : "Untitled";
    }

    // ==================== Note 数据类 ====================

    public static class Note {
        public final String id;
        public final String title;
        public final String type;
        public final List<String> tags;
        public final String createdAt;
        public final String updatedAt;
        public final String content;

        public Note(String id, String title, String type, List<String> tags,
                    String createdAt, String updatedAt, String content) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.content = content;
        }

        @Override
        public String toString() {
            return "[" + type + "] " + title
                    + (tags.isEmpty() ? "" : " " + tags)
                    + " (" + updatedAt + ")";
        }
    }
}
