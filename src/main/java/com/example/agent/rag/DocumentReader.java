package com.example.agent.rag;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 通用文档读取器（基于 Apache Tika），将任意格式文档转换为文本。
 *
 * 支持格式：
 * - 文档：PDF、Word、Excel、PowerPoint、EPUB、ODT 等
 * - 图像：PNG、JPG、GIF（OCR）
 * - 文本/代码：TXT、MD、CSV、JSON、XML、HTML、YAML 等
 * - 兜底：未知格式尝试 UTF-8 读取
 */
public class DocumentReader {

    // 文本类扩展名（快速路径，跳过 Tika）
    // Tika 处理更优的标记/数据格式（去标签、结构化提取）
    private static final Set<String> TIKA_BETTER_EXTENSIONS = Set.of(
            ".html", ".htm", ".xml", ".epub", ".rtf");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".markdown", ".csv", ".tsv",
            ".json", ".yaml", ".yml",
            ".properties", ".ini", ".cfg", ".conf", ".log",
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx",
            ".c", ".cpp", ".h", ".hpp", ".go", ".rs", ".rb",
            ".php", ".swift", ".kt", ".scala", ".sh", ".bat",
            ".sql", ".r", ".m", ".lua", ".css", ".scss", ".less",
            ".toml", ".lock", ".gradle", ".cmake", ".dockerfile"
    );

    // 音频格式（Tika 只提取元数据，不支持转录）
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a");

    private static Tika tika;

    /** 惰性初始化 Tika（首次加载较慢但后续复用） */
    private static Tika getTika() {
        if (tika == null) {
            tika = new Tika();
            System.out.println("[RAG] Apache Tika 已就绪");
        }
        return tika;
    }

    // ==================== 读取入口 ====================

    /**
     * 读取文档，返回文本内容。
     * 策略：文本文件直接读（快）→ 二进制文件走 Tika → 兜底 UTF-8
     */
    public static String read(String filePath) {
        if (filePath == null || filePath.isBlank()) return "";

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            System.out.println("[RAG] 文件不存在: " + filePath);
            return "";
        }

        String ext = extension(path).toLowerCase();

        // 音频 → Tika 只能提取元数据，无转录能力
        if (AUDIO_EXTENSIONS.contains(ext)) {
            System.out.println("[RAG] 音频转录需要 Vosk/Whisper: " + path.getFileName());
            return "";
        }

        // 文本/代码类 → 直接读（比 Tika 快）
        if (TEXT_EXTENSIONS.contains(ext)) {
            return readTextFile(path);
        }

        // 标记/文档格式 → Tika（去标签、结构化提取）
        if (TIKA_BETTER_EXTENSIONS.contains(ext)) {
            return readWithTika(path);
        }

        // 其他所有格式 → Tika
        return readWithTika(path);
    }

    /**
     * 读取文档并包装为 Markdown 结构。
     */
    public static String readAsMarkdown(String filePath) {
        String content = read(filePath);
        if (content.isBlank()) return "";

        Path path = Paths.get(filePath);
        String filename = path.getFileName().toString();
        String ext = extension(path);

        if (".md".equals(ext) || ".markdown".equals(ext)) {
            return content;
        }

        if (!content.stripLeading().startsWith("#")) {
            System.out.println("[RAG] Markdown 包装: " + filename + " -> 添加标题 # " + filename);
            return "# " + filename + "\n\n" + content;
        }

        return content;
    }

    // ==================== Tika 路径 ====================

    private static String readWithTika(Path path) {
        try {
            String content = getTika().parseToString(path.toFile());
            if (content != null && !content.isBlank()) {
                System.out.println("[RAG] Tika 解析成功: " + path.getFileName()
                        + " -> " + content.length() + " 字符");
                return content;
            }
        } catch (TikaException e) {
            System.out.println("[RAG] Tika 解析失败 " + path.getFileName() + ": " + e.getMessage());
        } catch (Exception e) {
            System.out.println("[RAG] Tika 异常 " + path.getFileName() + ": " + e.getMessage());
        }

        // Tika 失败 → 兜底 UTF-8
        return fallbackRead(path);
    }

    /**
     * 完整的 Tika 解析（包括元数据）。
     * 适用于需要提取作者、标题等元信息的场景。
     */
    public static Map<String, String> readWithMetadata(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return Collections.emptyMap();

        try (InputStream in = Files.newInputStream(path)) {
            var handler = new org.apache.tika.parser.AutoDetectParser();
            var metadata = new org.apache.tika.metadata.Metadata();
            var bodyHandler = new org.apache.tika.sax.BodyContentHandler(-1);

            handler.parse(in, bodyHandler, metadata);
            String content = bodyHandler.toString();

            Map<String, String> result = new LinkedHashMap<>();
            result.put("content", content);
            for (String name : metadata.names()) {
                result.put(name, metadata.get(name));
            }
            return result;

        } catch (Exception e) {
            System.out.println("[RAG] Tika 元数据提取失败: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ==================== 文本文件（快速路径） ====================

    private static String readTextFile(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (!content.isBlank()) {
                System.out.println("[RAG] 文本读取成功: " + path.getFileName()
                        + " -> " + content.length() + " 字符");
            }
            return content != null ? content : "";
        } catch (IOException e) {
            try {
                String content = Files.readString(path, Charset.defaultCharset());
                System.out.println("[RAG] 文本读取成功(默认编码): " + path.getFileName()
                        + " -> " + content.length() + " 字符");
                return content != null ? content : "";
            } catch (IOException e2) {
                System.out.println("[WARNING] 文本读取失败 " + path + ": " + e2.getMessage());
                return "";
            }
        }
    }

    // ==================== 兜底 ====================

    private static String fallbackRead(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content != null && !content.isBlank()) {
                System.out.println("[RAG] 兜底文本读取: " + path.getFileName()
                        + " -> " + content.length() + " 字符");
                return content;
            }
        } catch (IOException e) {
            System.out.println("[WARNING] 无法读取文件 " + path + ": " + e.getMessage());
        }
        return "";
    }

    // ==================== 工具方法 ====================

    public static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase() : "";
    }

    public static Set<String> supportedExtensions() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(TEXT_EXTENSIONS);
        all.addAll(AUDIO_EXTENSIONS);
        // Tika 支持 1000+ 种——这里列常见二进制格式
        all.addAll(Set.of(".pdf", ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".ppt",
                ".odt", ".ods", ".odp", ".epub", ".rtf",
                ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tiff"));
        return all;
    }

    public static boolean isSupported(String filePath) {
        return !read(filePath).isEmpty();
    }

    public static boolean isTextFormat(String filePath) {
        return TEXT_EXTENSIONS.contains(extension(Paths.get(filePath)));
    }

    public static boolean needsExternalLib(String filePath) {
        String ext = extension(Paths.get(filePath));
        return AUDIO_EXTENSIONS.contains(ext);
    }
}
