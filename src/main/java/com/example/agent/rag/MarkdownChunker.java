package com.example.agent.rag;
import java.util.*;

/**
 * Markdown 结构感知的智能分块器。
 *
 * 分块流程：
 * Markdown文本 → 标题层次解析 → 段落语义分割 → Token计算分块 → 重叠策略优化
 *
 * 充分利用 Markdown 的标题结构（#、##、###）进行精确语义分割，
 * 支持中英文混合 Token 估算，确保每个分块既适合向量化又保持结构完整性。
 */
public class MarkdownChunker {

    private final int chunkTokens;
    private final int overlapTokens;

    public MarkdownChunker() {
        this(500, 50);
    }

    public MarkdownChunker(int chunkTokens, int overlapTokens) {
        this.chunkTokens = chunkTokens;
        this.overlapTokens = overlapTokens;
    }

    // ==================== 数据结构 ====================

    public static class Paragraph {
        public final String content;
        public final String headingPath;   // 如 "Python基础 > 函数 > 装饰器"
        public final int startChar;
        public final int endChar;

        Paragraph(String content, String headingPath, int startChar, int endChar) {
            this.content = content;
            this.headingPath = headingPath;
            this.startChar = startChar;
            this.endChar = endChar;
        }
    }

    public static class Chunk {
        public final String content;
        public final int startChar;
        public final int endChar;
        public final String headingPath;   // 继承自最后一个有标题的段落
        public final int tokenCount;

        Chunk(String content, int startChar, int endChar, String headingPath, int tokenCount) {
            this.content = content;
            this.startChar = startChar;
            this.endChar = endChar;
            this.headingPath = headingPath;
            this.tokenCount = tokenCount;
        }
    }

    // ==================== 主流程 ====================

    /**
     * 对 Markdown 文本进行结构感知分块。
     *
     * @param text Markdown 格式文本
     * @return 有序分块列表
     */
    public List<Chunk> chunk(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        // 第一层：按标题层次分割为语义段落
        List<Paragraph> paragraphs = splitParagraphsWithHeadings(text);

        // 第二层：按 Token 数量合并段落为分块
        return chunkParagraphs(paragraphs);
    }

    // ==================== 标题层次分割 ====================

    /**
     * 根据 Markdown 标题层次分割段落，保持语义完整性。
     * 每个标题开启一个新的语义单元，其下所有内容共享 heading_path。
     */
    List<Paragraph> splitParagraphsWithHeadings(String text) {
        String[] lines = text.split("\n", -1);
        List<String> headingStack = new ArrayList<>();
        List<Paragraph> paragraphs = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        int charPos = 0;

        for (String raw : lines) {
            String stripped = raw.strip();

            if (stripped.startsWith("#")) {
                // 遇到标题 → 先刷出之前的缓冲区
                flushBuffer(buf, headingStack, paragraphs, charPos);

                // 解析标题层级
                int level = 0;
                while (level < stripped.length() && stripped.charAt(level) == '#') level++;
                if (level <= 0) level = 1;
                String title = stripped.substring(level).strip();

                // 更新标题栈：level 级标题替换 level-1 及更深，保留更浅层
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(title);

                charPos += raw.length() + 1;
                continue;
            }

            // 空行 → 段落边界
            if (stripped.isEmpty()) {
                flushBuffer(buf, headingStack, paragraphs, charPos);
                buf.clear();
            } else {
                buf.add(raw);
            }
            charPos += raw.length() + 1;
        }

        // 处理最后的缓冲区
        flushBuffer(buf, headingStack, paragraphs, charPos);

        // 兜底：如果完全没有提取到段落，整篇作为一个段落
        if (paragraphs.isEmpty()) {
            paragraphs.add(new Paragraph(text, null, 0, text.length()));
        }

        return paragraphs;
    }

    private void flushBuffer(List<String> buf, List<String> headingStack,
                             List<Paragraph> out, int charPos) {
        if (buf.isEmpty()) return;
        String content = String.join("\n", buf).strip();
        if (content.isEmpty()) return;

        int contentLen = 0;
        for (String s : buf) contentLen += s.length() + 1;
        int start = Math.max(0, charPos - contentLen);
        int end = charPos;

        String headingPath = headingStack.isEmpty() ? null
                : String.join(" > ", headingStack);

        out.add(new Paragraph(content, headingPath, start, end));
    }

    // ==================== Token 分块 ====================

    /**
     * 基于 Token 数量将段落合并为分块，带重叠策略。
     * 每个分块的 heading_path 继承自其最后一个有标题信息的段落。
     */
    List<Chunk> chunkParagraphs(List<Paragraph> paragraphs) {
        List<Chunk> chunks = new ArrayList<>();
        List<Paragraph> current = new ArrayList<>();
        int currentTokens = 0;
        int i = 0;

        while (i < paragraphs.size()) {
            Paragraph p = paragraphs.get(i);
            int pTokens = Math.max(approxTokenLen(p.content), 1);

            if (currentTokens + pTokens <= chunkTokens || current.isEmpty()) {
                current.add(p);
                currentTokens += pTokens;
                i++;
            } else {
                // 生成当前分块
                chunks.add(buildChunk(current));

                // 构建重叠部分
                if (overlapTokens > 0 && !current.isEmpty()) {
                    List<Paragraph> kept = new ArrayList<>();
                    int keptTokens = 0;
                    for (int j = current.size() - 1; j >= 0; j--) {
                        Paragraph x = current.get(j);
                        int t = Math.max(approxTokenLen(x.content), 1);
                        if (keptTokens + t > overlapTokens) break;
                        kept.add(0, x);
                        keptTokens += t;
                    }
                    current = kept;
                    currentTokens = keptTokens;
                } else {
                    current.clear();
                    currentTokens = 0;
                }
            }
        }

        // 最后一个分块
        if (!current.isEmpty()) {
            chunks.add(buildChunk(current));
        }

        return chunks;
    }

    private Chunk buildChunk(List<Paragraph> paragraphs) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) content.append("\n\n");
            content.append(paragraphs.get(i).content);
        }

        int start = paragraphs.get(0).startChar;
        int end = paragraphs.get(paragraphs.size() - 1).endChar;
        int tokens = approxTokenLen(content.toString());

        // 从后往前找第一个有 heading_path 的段落
        String headingPath = null;
        for (int i = paragraphs.size() - 1; i >= 0; i--) {
            if (paragraphs.get(i).headingPath != null) {
                headingPath = paragraphs.get(i).headingPath;
                break;
            }
        }

        return new Chunk(content.toString(), start, end, headingPath, tokens);
    }

    // ==================== Token 估算 ====================

    /**
     * 近似估计 Token 长度，支持中英文混合。
     * - CJK 字符：1 字符 ≈ 1 token
     * - 其他字符：按空白分词计数（英文单词 ≈ 1 token）
     */
    public static int approxTokenLen(String text) {
        if (text == null || text.isBlank()) return 0;

        int cjk = 0;
        int nonCjkChars = 0;
        for (char ch : text.toCharArray()) {
            if (isCjk(ch)) {
                cjk++;
            } else if (ch > ' ') {
                nonCjkChars++;
            }
        }

        // 非 CJK 部分：按空格分词估算 token 数
        String[] nonCjkTokens = text.split("\\s+");
        int nonCjkTokenCount = 0;
        for (String token : nonCjkTokens) {
            // 过滤掉纯 CJK 部分（已经在 cjk 中计数）
            String cleaned = token.replaceAll("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF]", "");
            if (!cleaned.isEmpty()) nonCjkTokenCount++;
        }

        return cjk + nonCjkTokenCount;
    }

    /** 判断是否为 CJK 字符 */
    public static boolean isCjk(char ch) {
        int code = ch;
        return (code >= 0x4E00 && code <= 0x9FFF)    // CJK 统一汉字
                || (code >= 0x3400 && code <= 0x4DBF)  // CJK 扩展 A
                || (code >= 0x20000 && code <= 0x2A6DF) // CJK 扩展 B
                || (code >= 0x2A700 && code <= 0x2B73F) // CJK 扩展 C
                || (code >= 0x2B740 && code <= 0x2B81F) // CJK 扩展 D
                || (code >= 0x2B820 && code <= 0x2CEAF) // CJK 扩展 E
                || (code >= 0xF900 && code <= 0xFAFF);  // CJK 兼容汉字
    }

    // ==================== 访问器 ====================

    public int getChunkTokens() { return chunkTokens; }
    public int getOverlapTokens() { return overlapTokens; }
}
