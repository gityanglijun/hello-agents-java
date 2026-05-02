import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 工具（检索增强生成）
 *
 * 提供完整的 RAG 能力：
 * - 添加多格式文档（文本、后续可扩展 PDF/Office/图片/音频）
 * - 智能分块 + 向量化 + 存储
 * - 语义检索与召回
 * - LLM 增强问答（检索 + 生成）
 * - 知识库管理
 */
public class RAGTool extends Tool {

    // ==================== 数据类 ====================

    public static class Document {
        public final String id;
        public final String title;
        public final String content;
        public final Map<String, String> metadata;
        public final String createdAt;

        Document(String id, String title, String content, Map<String, String> metadata, String createdAt) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
            this.createdAt = createdAt;
        }
    }

    public static class Chunk {
        public final String id;
        public final String docId;
        public final int index;
        public final String content;
        public final int startChar;
        public final int endChar;
        public final String headingPath;  // Markdown 标题路径，如 "Python > 函数"

        Chunk(String id, String docId, int index, String content,
              int startChar, int endChar, String headingPath) {
            this.id = id;
            this.docId = docId;
            this.index = index;
            this.content = content;
            this.startChar = startChar;
            this.endChar = endChar;
            this.headingPath = headingPath;
        }
    }

    // ==================== 存储层 ====================

    // 文档存储
    private final Map<String, Document> documents = new LinkedHashMap<>();
    // 分块存储: chunkId → Chunk
    private final Map<String, Chunk> chunks = new LinkedHashMap<>();
    // 文档→分块索引: docId → [chunkId, ...]
    private final Map<String, List<String>> docToChunks = new LinkedHashMap<>();
    // 向量存储: chunkId → float[]
    private final Map<String, float[]> vectorStore = new LinkedHashMap<>();
    // 统一嵌入接口（百炼API → TF-IDF 自动降级）
    private final EmbedderProvider embedder;

    // LLM（ask 时惰性初始化）
    private HelloAgentsLLM llm;

    // 智能分块器（Markdown 结构感知）
    private final MarkdownChunker chunker;

    // ==================== 构造 ====================

    public RAGTool() {
        this(500, 50, 256);
    }

    public RAGTool(int chunkTokens, int overlapTokens, int vectorDim) {
        super("rag", "RAG检索增强生成工具。支持文档添加、语义检索、LLM增强问答和知识库管理。");
        this.chunker = new MarkdownChunker(chunkTokens, overlapTokens);
        this.embedder = new EmbedderProvider(vectorDim);
        this.llm = null;
    }

    // ==================== Tool 接口 ====================

    @Override
    public String run(Map<String, Object> parameters) {
        String action = (String) parameters.getOrDefault("action", "search");

        switch (action) {
            case "add_document":    return addDocument(parameters);
            case "search":          return search(parameters);
            case "add_file":        return addFile(parameters);
            case "ask":             return ask(parameters);
            case "list_documents":  return listDocuments();
            case "remove_document": return removeDocument(parameters);
            case "stats":           return stats();
            case "expanded_search": return expandedSearch(parameters);
            default:
                return "❌ 不支持的操作: " + action
                        + "\n支持: add_document, add_file, search, ask, list_documents, remove_document, stats, expanded_search";
        }
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("action", "string", "操作: add_document, add_file, search, ask, list_documents, remove_document, stats, expanded_search"),
                new ToolParameter("enable_mqe", "boolean", "expanded_search: 启用多查询扩展（默认false）"),
                new ToolParameter("mqe_expansions", "integer", "expanded_search: MQE 扩展查询数（默认2）"),
                new ToolParameter("enable_hyde", "boolean", "expanded_search: 启用假设文档嵌入（默认false）"),
                new ToolParameter("candidate_pool_multiplier", "integer", "expanded_search: 候选池倍数（默认4）"),
                new ToolParameter("score_threshold", "number", "expanded_search: 最低分数阈值"),
                new ToolParameter("file_path", "string", "文件路径（add_file）"),
                new ToolParameter("title", "string", "文档标题（add_document）"),
                new ToolParameter("content", "string", "文档内容 / 查询文本"),
                new ToolParameter("query", "string", "检索查询 / 问答问题"),
                new ToolParameter("doc_id", "string", "文档ID（remove_document）"),
                new ToolParameter("top_k", "integer", "检索返回数量（默认5）"),
                new ToolParameter("enable_llm", "boolean", "ask 是否启用 LLM 生成（默认true）")
        );
    }

    // ==================== Action: add_document ====================

    private String addDocument(Map<String, Object> params) {
        try {
            String title = (String) params.getOrDefault("title", "未命名文档");
            String content = (String) params.getOrDefault("content", "");
            if (content.isBlank()) return "❌ 文档内容不能为空";

            String docId = UUID.randomUUID().toString();
            String now = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            // 提取元数据
            Map<String, String> metadata = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (!List.of("action", "title", "content").contains(e.getKey())
                        && e.getValue() instanceof String) {
                    metadata.put(e.getKey(), (String) e.getValue());
                }
            }

            Document doc = new Document(docId, title, content, metadata, now);
            documents.put(docId, doc);

            // Markdown 结构感知分块
            List<MarkdownChunker.Chunk> mdChunks = chunker.chunk(content);
            docToChunks.put(docId, new ArrayList<>());

            for (int ci = 0; ci < mdChunks.size(); ci++) {
                MarkdownChunker.Chunk mc = mdChunks.get(ci);
                Chunk chunk = new Chunk(
                        UUID.randomUUID().toString(), docId, ci,
                        mc.content, mc.startChar, mc.endChar, mc.headingPath);
                chunks.put(chunk.id, chunk);
                docToChunks.get(docId).add(chunk.id);

                embedder.register(chunk.content);
                float[] vec = embedder.encode(
                        EmbedderProvider.preprocessMarkdown(chunk.content));
                vectorStore.put(chunk.id, vec);
            }

            return "✅ 文档已添加\n"
                    + "  ID: " + docId.substring(0, 8) + "...\n"
                    + "  标题: " + title + "\n"
                    + "  内容长度: " + content.length() + " 字符\n"
                    + "  分块数: " + mdChunks.size()
                    + " | 分块策略: Markdown 结构感知 ("
                    + chunker.getChunkTokens() + " tokens/块)";

        } catch (Exception e) {
            return "❌ 添加文档失败: " + e.getMessage();
        }
    }

    // ==================== Action: add_file ====================

    private String addFile(Map<String, Object> params) {
        try {
            String filePath = (String) params.getOrDefault("file_path",
                    params.get("path"));
            if (filePath == null || filePath.isBlank()) return "❌ 需要提供 file_path";

            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
            if (!java.nio.file.Files.exists(path)) {
                return "❌ 文件不存在: " + filePath;
            }

            // 检查是否需要外部依赖（目前仅音频需要）
            if (DocumentReader.needsExternalLib(filePath)) {
                System.out.println("[RAG] 音频文件需 Vosk/Whisper: " + filePath);
            }

            // 读取文件内容，包装为 Markdown 结构
            String content = DocumentReader.readAsMarkdown(filePath);
            if (content.isBlank()) {
                return "❌ 无法读取文件内容: " + filePath
                        + (DocumentReader.needsExternalLib(filePath)
                            ? "\n提示: 该格式需要添加对应的依赖库"
                            : "");
            }

            // 如果没有指定标题，用文件名做标题
            String title = (String) params.getOrDefault("title",
                    path.getFileName().toString());

            // 委托给 addDocument
            Map<String, Object> addParams = new LinkedHashMap<>(params);
            addParams.put("title", title);
            addParams.put("content", content);
            addParams.put("file_path", filePath);
            addParams.put("source_format", DocumentReader.extension(path));
            return addDocument(addParams);

        } catch (Exception e) {
            return "❌ 文件导入失败: " + e.getMessage();
        }
    }

    // ==================== Action: search ====================

    private String search(Map<String, Object> params) {
        try {
            String query = (String) params.getOrDefault("query",
                    params.getOrDefault("content", ""));
            if (query.isBlank()) return "❌ 查询不能为空";

            int topK = getInt(params, "top_k", 5);

            List<ChunkHit> hits = retrieveChunks(query, topK);
            if (hits.isEmpty()) return "🔍 未找到与 '" + query + "' 相关的内容";

            StringBuilder sb = new StringBuilder();
            sb.append("🔍 找到 ").append(hits.size()).append(" 个相关片段:\n\n");

            for (int i = 0; i < hits.size(); i++) {
                ChunkHit hit = hits.get(i);
                Chunk chunk = hit.chunk;
                Document doc = documents.get(chunk.docId);
                String docTitle = doc != null ? doc.title : "(已删除)";

                sb.append("--- 片段 ").append(i + 1)
                        .append(" | 文档: ").append(docTitle);
                if (chunk.headingPath != null) {
                    sb.append(" | 章节: ").append(chunk.headingPath);
                }
                sb.append(" | 相关度: ").append(String.format("%.3f", hit.score))
                        .append(" ---\n");
                sb.append(chunk.content).append("\n\n");
            }

            return sb.toString().trim();

        } catch (Exception e) {
            return "❌ 检索失败: " + e.getMessage();
        }
    }

    // ==================== Action: ask（完整 RAG） ====================

    private String ask(Map<String, Object> params) {
        try {
            String query = (String) params.getOrDefault("query",
                    params.getOrDefault("content", ""));
            if (query.isBlank()) return "❌ 问题不能为空";

            int topK = getInt(params, "top_k", 5);
            boolean enableLLM = getBool(params, "enable_llm", true);

            // 1. 检索相关片段
            List<ChunkHit> hits = retrieveChunks(query, topK);
            if (hits.isEmpty()) return "🔍 未找到与问题相关的知识";

            // 2. 构建上下文
            StringBuilder context = new StringBuilder();
            List<String> sources = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                Chunk chunk = hits.get(i).chunk;
                Document doc = documents.get(chunk.docId);
                context.append("【来源").append(i + 1).append("】").append(chunk.content).append("\n");
                if (doc != null) {
                    sources.add("来源" + (i + 1) + ": " + doc.title);
                }
            }

            // 3. LLM 生成答案
            if (enableLLM && initLLM()) {
                String prompt = buildRAGPrompt(query, context.toString());
                String answer = callLLM(prompt);

                StringBuilder result = new StringBuilder();
                result.append("🤖 RAG 增强回答:\n\n");
                result.append(answer).append("\n\n");
                result.append("---\n📚 参考来源:\n");
                for (String src : sources) {
                    result.append("  - ").append(src).append("\n");
                }
                return result.toString().trim();
            }

            // 4. 降级：仅返回检索片段
            StringBuilder sb = new StringBuilder();
            sb.append("📄 检索结果（未启用 LLM）:\n\n");
            for (int i = 0; i < hits.size(); i++) {
                sb.append("--- ").append(sources.get(i)).append(" ---\n");
                sb.append(hits.get(i).chunk.content).append("\n\n");
            }
            return sb.toString().trim();

        } catch (Exception e) {
            return "❌ RAG 问答失败: " + e.getMessage();
        }
    }

    // ==================== Action: list_documents ====================

    private String listDocuments() {
        if (documents.isEmpty()) return "📚 知识库为空";

        StringBuilder sb = new StringBuilder();
        sb.append("📚 知识库文档列表 (").append(documents.size()).append(" 篇):\n\n");

        int i = 1;
        for (Document doc : documents.values()) {
            int chunkCount = docToChunks.getOrDefault(doc.id, Collections.emptyList()).size();
            String preview = doc.content.length() > 80
                    ? doc.content.substring(0, 80).replace("\n", " ") + "..."
                    : doc.content.replace("\n", " ");
            sb.append(i).append(". ").append(doc.title)
                    .append(" | ").append(chunkCount).append(" 分块")
                    .append(" | ").append(doc.content.length()).append(" 字符\n");
            sb.append("   ID: ").append(doc.id.substring(0, 8)).append("...\n");
            sb.append("   预览: ").append(preview).append("\n\n");
            i++;
        }

        return sb.toString().trim();
    }

    // ==================== Action: remove_document ====================

    private String removeDocument(Map<String, Object> params) {
        String docId = (String) params.getOrDefault("doc_id", params.get("id"));
        if (docId == null || docId.isBlank()) return "❌ 需要提供 doc_id";

        // 支持前缀匹配
        String fullId = findDocId(docId);
        if (fullId == null) return "❌ 未找到文档: " + docId;

        Document doc = documents.remove(fullId);
        List<String> chunkIds = docToChunks.remove(fullId);
        if (chunkIds != null) {
            for (String cid : chunkIds) {
                chunks.remove(cid);
                vectorStore.remove(cid);
            }
        }

        return "🗑️ 已删除文档: " + doc.title
                + "（含 " + (chunkIds != null ? chunkIds.size() : 0) + " 个分块）";
    }

    // ==================== Action: stats ====================

    private String stats() {
        int docCount = documents.size();
        int chunkCount = chunks.size();

        if (docCount == 0) return "📊 知识库为空";

        long totalChars = documents.values().stream().mapToLong(d -> d.content.length()).sum();
        double avgChunks = docCount > 0 ? (double) chunkCount / docCount : 0;

        return "📊 知识库统计:\n"
                + "  文档数: " + docCount + "\n"
                + "  总字符数: " + totalChars + "\n"
                + "  分块数: " + chunkCount + "\n"
                + "  平均分块/文档: " + String.format("%.1f", avgChunks) + "\n"
                + "  向量维度: " + (vectorStore.isEmpty() ? "N/A"
                        : String.valueOf(vectorStore.values().iterator().next().length));
    }


    // ==================== 检索 ====================

    private List<ChunkHit> retrieveChunks(String query, int topK) {
        float[] queryVec = embedder.encode(
                EmbedderProvider.preprocessMarkdown(query));

        List<ChunkHit> hits = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : vectorStore.entrySet()) {
            double sim = cosine(queryVec, entry.getValue());
            if (sim > 0) {
                Chunk chunk = chunks.get(entry.getKey());
                if (chunk != null) {
                    hits.add(new ChunkHit(chunk, sim));
                }
            }
        }

        hits.sort((a, b) -> Double.compare(b.score, a.score));
        int n = Math.min(topK > 0 ? topK : 5, hits.size());
        return hits.subList(0, n);
    }

    // ==================== LLM 增强 ====================

    private boolean initLLM() {
        if (llm == null) {
            try {
                llm = new HelloAgentsLLM();
            } catch (Throwable e) {
                System.out.println("⚠️ LLM 初始化失败: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private String buildRAGPrompt(String question, String context) {
        return "你是一个知识库助手。请**仅根据**以下参考资料回答用户的问题。\n"
                + "如果参考资料中没有相关信息，请明确说明「无法从现有资料中找到答案」。\n"
                + "不要编造信息，引用时注明来源编号。\n\n"
                + "=== 参考资料 ===\n"
                + context + "\n"
                + "=== 用户问题 ===\n"
                + question + "\n\n"
                + "请用简洁清晰的中文回答：";
    }

    private String callLLM(String prompt) {
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "user", "content", prompt)
            );
            return llm.think(messages);
        } catch (Exception e) {
            return "（LLM 调用失败: " + e.getMessage() + "）";
        }
    }

    // ==================== 工具方法 ====================

    private String findDocId(String idOrPrefix) {
        // 精确匹配
        if (documents.containsKey(idOrPrefix)) return idOrPrefix;
        // 前缀匹配
        for (String id : documents.keySet()) {
            if (id.startsWith(idOrPrefix)) return id;
        }
        return null;
    }

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

    private double getDouble(Map<String, Object> params, String key, double def) {
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private int getInt(Map<String, Object> params, String key, int def) {
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private boolean getBool(Map<String, Object> params, String key, boolean def) {
        Object v = params.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return def;
    }

    // ==================== Action: expanded_search ====================

    /**
     * 扩展检索（统一入口）。
     * 支持 MQE（多查询扩展）和 HyDE（假设文档嵌入）两种互补策略，
     * 通过"扩展-检索-合并"三步流程提高召回率。
     */
    private String expandedSearch(Map<String, Object> params) {
        try {
            String query = (String) params.getOrDefault("query",
                    params.getOrDefault("content", ""));
            if (query.isBlank()) return "❌ 查询不能为空";

            int topK = getInt(params, "top_k", 5);
            boolean enableMqe = getBool(params, "enable_mqe", false);
            int mqeExpansions = getInt(params, "mqe_expansions", 2);
            boolean enableHyde = getBool(params, "enable_hyde", false);
            int poolMultiplier = getInt(params, "candidate_pool_multiplier", 4);
            Double scoreThreshold = params.containsKey("score_threshold")
                    ? getDouble(params, "score_threshold", 0) : null;

            // 检查 LLM 是否可用（MQE 和 HyDE 需要）
            boolean needLLM = enableMqe || enableHyde;
            if (needLLM && !initLLM()) {
                System.out.println("[RAG] LLM 不可用，降级为普通检索");
                return search(params);
            }

            List<ChunkHit> hits = searchVectorsExpanded(
                    query, topK, enableMqe, mqeExpansions, enableHyde,
                    poolMultiplier, scoreThreshold);

            if (hits.isEmpty()) return "🔍 未找到与 '" + query + "' 相关的内容（扩展检索）";

            // 格式化结果（与 search 一致）
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 扩展检索找到 ").append(hits.size()).append(" 个结果");

            List<String> strategies = new ArrayList<>();
            if (enableMqe) strategies.add("MQE(×" + mqeExpansions + ")");
            if (enableHyde) strategies.add("HyDE");
            sb.append(" | 策略: ").append(String.join("+", strategies));
            sb.append(" | 候选池: ").append(topK * poolMultiplier).append("\n\n");

            for (int i = 0; i < hits.size(); i++) {
                ChunkHit hit = hits.get(i);
                Chunk chunk = hit.chunk;
                Document doc = documents.get(chunk.docId);
                String docTitle = doc != null ? doc.title : "(已删除)";

                sb.append("--- 片段 ").append(i + 1)
                        .append(" | 文档: ").append(docTitle);
                if (chunk.headingPath != null) {
                    sb.append(" | 章节: ").append(chunk.headingPath);
                }
                sb.append(" | 相关度: ").append(String.format("%.4f", hit.score))
                        .append(" ---\n");
                sb.append(chunk.content).append("\n\n");
            }

            return sb.toString().trim();

        } catch (Exception e) {
            return "❌ 扩展检索失败: " + e.getMessage();
        }
    }

    // ==================== MQE：多查询扩展 ====================

    /**
     * 使用 LLM 生成多样化的查询扩展。
     * 同一个问题有多种表述方式，不同表述可能匹配到不同文档。
     */
    private List<String> promptMqe(String query, int n) {
        try {
            String prompt = "你是检索查询扩展助手。生成语义等价或互补的多样化查询。使用中文，简短，避免标点。\n\n"
                    + "原始查询：" + query + "\n"
                    + "请给出" + n + "个不同表述的查询，每行一个。";

            String text = llm.think(List.of(
                    Map.of("role", "system", "content", "你是检索查询扩展助手。生成语义等价或互补的多样化查询。使用中文，简短，避免标点。"),
                    Map.of("role", "user", "content", prompt)
            ));

            if (text == null || text.isBlank()) return List.of(query);

            List<String> results = new ArrayList<>();
            for (String line : text.split("\n")) {
                String cleaned = line.replaceFirst("^[\\d]+[\\.\\、\\)\\s\\-]+", "").strip();
                if (!cleaned.isBlank() && !cleaned.equals(query)) {
                    results.add(cleaned);
                }
            }
            return results.isEmpty() ? List.of(query) : results.subList(0, Math.min(n, results.size()));

        } catch (Exception e) {
            System.out.println("[MQE] 查询扩展失败: " + e.getMessage());
            return List.of(query);
        }
    }

    // ==================== HyDE：假设文档嵌入 ====================

    /**
     * 生成假设性文档用于改善检索——"用答案找答案"。
     * LLM 先生成假设答案段落，再用这个段落去检索真实文档，
     * 缩小问题（疑问句）和文档（陈述句）之间的语义鸿沟。
     */
    private String promptHyde(String query) {
        try {
            String text = llm.think(List.of(
                    Map.of("role", "system", "content", "根据用户问题，先写一段可能的答案性段落，用于向量检索的查询文档（不要分析过程）。"),
                    Map.of("role", "user", "content", "问题：" + query + "\n请直接写一段中等长度、客观、包含关键术语的段落。")
            ));

            if (text == null || text.isBlank()) return null;
            System.out.println("[HyDE] 假设文档已生成 (" + text.length() + " 字符)");
            return text;

        } catch (Exception e) {
            System.out.println("[HyDE] 假设文档生成失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== 扩展检索核心 ====================

    /**
     * "扩展-检索-合并"三步流程：
     * 1. 扩展：生成多个扩展查询（MQE + HyDE）
     * 2. 检索：对每个扩展查询并行执行向量检索
     * 3. 合并：去重 + 按分数排序，返回 top-K
     */
    private List<ChunkHit> searchVectorsExpanded(
            String query, int topK,
            boolean enableMqe, int mqeExpansions,
            boolean enableHyde, int poolMultiplier,
            Double scoreThreshold) {

        // === 第1步：查询扩展 ===
        List<String> expansions = new ArrayList<>();
        expansions.add(query);

        if (enableMqe && mqeExpansions > 0) {
            List<String> mqeResults = promptMqe(query, mqeExpansions);
            expansions.addAll(mqeResults);
            System.out.println("[RAG] MQE 扩展: " + mqeResults.size() + " 个查询");
        }

        if (enableHyde) {
            String hydeText = promptHyde(query);
            if (hydeText != null && !hydeText.isBlank()) {
                expansions.add(hydeText);
            }
        }

        // 去重
        List<String> unique = new ArrayList<>();
        for (String e : expansions) {
            if (e != null && !e.isBlank() && !unique.contains(e)) {
                unique.add(e);
            }
        }
        System.out.println("[RAG] 扩展查询: " + unique.size() + " 个（原始 + MQE + HyDE）");

        // === 第2步：分配候选池并检索 ===
        int poolSize = Math.max(topK * poolMultiplier, 20);
        int perQuery = Math.max(1, poolSize / Math.max(1, unique.size()));
        System.out.println("[RAG] 候选池: " + poolSize + ", 每查询: " + perQuery);

        Map<String, ChunkHit> aggregated = new LinkedHashMap<>();

        for (String q : unique) {
            float[] queryVec = embedder.encode(
                    EmbedderProvider.preprocessMarkdown(q));

            List<ChunkHit> hits = new ArrayList<>();
            for (Map.Entry<String, float[]> entry : vectorStore.entrySet()) {
                double sim = EmbedderProvider.cosineSimilarity(queryVec, entry.getValue());
                if (scoreThreshold != null && sim < scoreThreshold) continue;
                if (sim > 0) {
                    Chunk chunk = chunks.get(entry.getKey());
                    if (chunk != null) {
                        hits.add(new ChunkHit(chunk, sim));
                    }
                }
            }

            hits.sort((a, b) -> Double.compare(b.score, a.score));
            int limit = Math.min(perQuery, hits.size());

            for (int i = 0; i < limit; i++) {
                ChunkHit hit = hits.get(i);
                String key = hit.chunk.id;
                ChunkHit existing = aggregated.get(key);
                if (existing == null || hit.score > existing.score) {
                    aggregated.put(key, hit);
                }
            }
        }

        // === 第3步：合并排序 ===
        List<ChunkHit> merged = new ArrayList<>(aggregated.values());
        merged.sort((a, b) -> Double.compare(b.score, a.score));
        int resultLimit = Math.min(topK, merged.size());

        System.out.println("[RAG] 合并结果: " + merged.size() + " 个唯一片段 → 返回 " + resultLimit);
        return merged.subList(0, resultLimit);
    }

    // ==================== 批量索引 ====================

    /**
     * 批量索引 Markdown 分块。
     * 使用时先调用 chunker.chunk() 获取分块列表，再批量嵌入存储。
     */
    public void indexChunks(String docId, List<MarkdownChunker.Chunk> mdChunks) {
        if (mdChunks.isEmpty()) return;

        // 收集内容并预处理
        List<String> texts = new ArrayList<>();
        for (MarkdownChunker.Chunk mc : mdChunks) {
            texts.add(EmbedderProvider.preprocessMarkdown(mc.content));
        }

        System.out.println("[RAG] 批量嵌入: " + texts.size() + " 个分块, "
                + "后端: " + embedder.getActiveBackend()
                + " (" + embedder.getDimension() + " 维)");

        // 批量编码
        List<float[]> vectors;
        if (embedder.getActiveBackend() == EmbedderProvider.Backend.BAILIAN) {
            vectors = embedder.encodeBatch(texts);
        } else {
            // TF-IDF 需要先注册再逐个编码
            vectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                embedder.register(texts.get(i));
                vectors.add(embedder.encode(texts.get(i)));
            }
        }

        // 存储
        docToChunks.putIfAbsent(docId, new ArrayList<>());
        for (int i = 0; i < mdChunks.size(); i++) {
            MarkdownChunker.Chunk mc = mdChunks.get(i);
            Chunk chunk = new Chunk(UUID.randomUUID().toString(), docId, i,
                    mc.content, mc.startChar, mc.endChar, mc.headingPath);
            chunks.put(chunk.id, chunk);
            docToChunks.get(docId).add(chunk.id);
            vectorStore.put(chunk.id,
                    i < vectors.size() ? vectors.get(i) : new float[embedder.getDimension()]);
        }
    }

    // ==================== 公开访问器 ====================

    public int docCount() { return documents.size(); }
    public int chunkCount() { return chunks.size(); }

    public Document getDocument(String id) {
        String fullId = findDocId(id);
        return fullId != null ? documents.get(fullId) : null;
    }

    public List<Chunk> getChunks(String docId) {
        String fullId = findDocId(docId);
        if (fullId == null) return Collections.emptyList();
        List<String> ids = docToChunks.getOrDefault(fullId, Collections.emptyList());
        return ids.stream().map(chunks::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public void clear() {
        documents.clear();
        chunks.clear();
        docToChunks.clear();
        vectorStore.clear();
    }

    public EmbedderProvider getEmbedder() { return embedder; }

    // ==================== 内部类型 ====================

    private static class ChunkHit {
        final Chunk chunk;
        final double score;
        ChunkHit(Chunk chunk, double score) { this.chunk = chunk; this.score = score; }
    }
}
