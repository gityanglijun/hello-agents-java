package com.example.agent.rag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import com.example.agent.embedding.EmbedderProvider;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.store.DocumentStore;
import com.example.agent.store.InMemoryVectorStore;
import com.example.agent.store.VectorStore;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolParameter;

/**
 * RAG 工具（检索增强生成）— 使用 VectorStore + DocumentStore 作为存储后端。
 */
public class RAGTool extends Tool {

    // ==================== 数据类 ====================

    public static class Document {
        public final String id;
        public final String title;
        public final String content;
        public final Map<String, String> metadata;
        public final String createdAt;

        public Document(String id, String title, String content, Map<String, String> metadata, String createdAt) {
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
        public final String headingPath;

        public Chunk(String id, String docId, int index, String content,
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

    private final DocumentStore docStore;
    private final boolean ownDocStore;
    private final VectorStore vectorStore;
    private final EmbedderProvider embedder;
    private HelloAgentsLLM llm;
    private final MarkdownChunker chunker;

    // ==================== 构造 ====================

    public RAGTool() {
        this(500, 50, 256);
    }

    public RAGTool(int chunkTokens, int overlapTokens, int vectorDim) {
        this(chunkTokens, overlapTokens, vectorDim, null, null);
    }

    /** 使用外部存储后端（持久化）。传 null 则使用默认内存存储 */
    public RAGTool(int chunkTokens, int overlapTokens, int vectorDim,
                   DocumentStore docStore, VectorStore vectorStore) {
        super("rag", "RAG检索增强生成工具。支持文档添加、语义检索、LLM增强问答和知识库管理。");
        this.chunker = new MarkdownChunker(chunkTokens, overlapTokens);
        this.embedder = new EmbedderProvider(vectorDim);
        this.docStore = docStore != null ? docStore : new DocumentStore(":memory:");
        this.ownDocStore = docStore == null;
        this.vectorStore = vectorStore != null ? vectorStore : new InMemoryVectorStore(vectorDim);
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
                new ToolParameter("enable_mqe", "boolean", "expanded_search: 启用多查询扩展"),
                new ToolParameter("mqe_expansions", "integer", "expanded_search: MQE 扩展查询数"),
                new ToolParameter("enable_hyde", "boolean", "expanded_search: 启用假设文档嵌入"),
                new ToolParameter("candidate_pool_multiplier", "integer", "expanded_search/rerank: 候选池倍数"),
                new ToolParameter("enable_rerank", "boolean", "ask: 启用 LLM Reranker 精排（默认与 advanced_search 一致）"),
                new ToolParameter("score_threshold", "number", "expanded_search: 最低分数阈值"),
                new ToolParameter("file_path", "string", "文件路径（add_file）"),
                new ToolParameter("title", "string", "文档标题（add_document）"),
                new ToolParameter("content", "string", "文档内容 / 查询文本"),
                new ToolParameter("query", "string", "检索查询 / 问答问题"),
                new ToolParameter("doc_id", "string", "文档ID（remove_document）"),
                new ToolParameter("top_k", "integer", "检索返回数量（默认5）"),
                new ToolParameter("enable_llm", "boolean", "ask 是否启用 LLM 生成")
        );
    }

    // ==================== Action: add_document ====================

    private String addDocument(Map<String, Object> params) {
        try {
            String title = (String) params.getOrDefault("title", "未命名文档");
            String content = (String) params.getOrDefault("content", "");
            if (content.isBlank()) return "❌ 文档内容不能为空";

            String docId = UUID.randomUUID().toString();
            String now = now();

            Map<String, String> metadata = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (!List.of("action", "title", "content").contains(e.getKey())
                        && e.getValue() instanceof String) {
                    metadata.put(e.getKey(), (String) e.getValue());
                }
            }

            // 文档存储
            docStore.insertDocument(docId, title, content, metadata, now);

            // 分块
            List<MarkdownChunker.Chunk> mdChunks = chunker.chunk(content);

            for (int ci = 0; ci < mdChunks.size(); ci++) {
                MarkdownChunker.Chunk mc = mdChunks.get(ci);
                String chunkId = UUID.randomUUID().toString();

                docStore.insertChunk(chunkId, docId, ci, mc.content,
                        mc.startChar, mc.endChar, mc.headingPath);

                embedder.register(mc.content);
                float[] vec = embedder.encode(
                        EmbedderProvider.preprocessMarkdown(mc.content));
                vectorStore.add(chunkId, vec);
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
            String filePath = (String) params.getOrDefault("file_path", params.get("path"));
            if (filePath == null || filePath.isBlank()) return "❌ 需要提供 file_path";

            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return "❌ 文件不存在: " + filePath;
            }

            if (DocumentReader.needsExternalLib(filePath)) {
                System.out.println("[RAG] 音频文件需 Vosk/Whisper: " + filePath);
            }

            String content = DocumentReader.readAsMarkdown(filePath);
            if (content.isBlank()) {
                return "❌ 无法读取文件内容: " + filePath
                        + (DocumentReader.needsExternalLib(filePath)
                            ? "\n提示: 该格式需要添加对应的依赖库"
                            : "");
            }

            String title = (String) params.getOrDefault("title",
                    path.getFileName().toString());

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
                Document doc = docStore.getDocument(chunk.docId);
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

    // ==================== Action: ask ====================

    private String ask(Map<String, Object> params) {
        try {
            String query = (String) params.getOrDefault("query",
                    params.getOrDefault("content", ""));
            if (query.isBlank()) return "❌ 问题不能为空";

            int topK = getInt(params, "top_k", 5);
            boolean enableLLM = getBool(params, "enable_llm", true);
            boolean enableAdvanced = getBool(params, "enable_advanced_search", false);
            boolean enableMqe = getBool(params, "enable_mqe", false);
            boolean enableHyde = getBool(params, "enable_hyde", false);
            boolean enableRerank = getBool(params, "enable_rerank", enableAdvanced);

            // 如果启用重排序，候选池要更大
            int finalTopK = enableRerank ? Math.max(topK, 3) : topK;
            int candidateMultiplier = getInt(params, "candidate_pool_multiplier",
                    enableRerank ? 10 : 5);

            // ===== 阶段1: 混合检索（粗筛） =====
            List<ChunkHit> hits;
            if (enableAdvanced && initLLM()) {
                hits = searchVectorsExpanded(query, finalTopK,
                        enableMqe, getInt(params, "mqe_expansions", 3),
                        enableHyde, candidateMultiplier,
                        null);
            } else {
                hits = retrieveChunks(query, Math.max(finalTopK, 20));
            }
            if (hits.isEmpty()) return "🔍 未找到与问题相关的知识";

            // ===== 阶段2: LLM Reranker 精排 =====
            if (enableRerank && initLLM() && hits.size() > topK) {
                System.out.println("[RAG] Reranker 精排: " + hits.size() + " → " + topK);
                hits = rerankWithLLM(query, hits, topK);
            }

            StringBuilder context = new StringBuilder();
            List<String> sources = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                Chunk chunk = hits.get(i).chunk;
                Document doc = docStore.getDocument(chunk.docId);
                context.append("【来源").append(i + 1).append("】");
                if (chunk.headingPath != null) {
                    context.append(" [章节: ").append(chunk.headingPath).append("]");
                }
                context.append("\n").append(chunk.content).append("\n");
                if (doc != null) {
                    sources.add("来源" + (i + 1) + ": " + doc.title);
                }
            }

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
        List<Document> docs = docStore.getAllDocuments();
        if (docs.isEmpty()) return "📚 知识库为空";

        StringBuilder sb = new StringBuilder();
        sb.append("📚 知识库文档列表 (").append(docs.size()).append(" 篇):\n\n");

        int i = 1;
        for (Document doc : docs) {
            List<Map<String, String>> chunkRaws = docStore.getChunksByDocId(doc.id);
            int chunkCount = chunkRaws.size();
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

        Document doc = docStore.getDocument(docId);
        if (doc == null) return "❌ 未找到文档: " + docId;

        // 清理向量存储
        List<Map<String, String>> chunkRaws = docStore.getChunksByDocId(doc.id);
        for (Map<String, String> c : chunkRaws) {
            vectorStore.remove(c.get("id"));
        }

        docStore.deleteDocument(docId);

        return "🗑️ 已删除文档: " + doc.title
                + "（含 " + chunkRaws.size() + " 个分块）";
    }

    // ==================== Action: stats ====================

    private String stats() {
        int docCount = docStore.countDocuments();
        int chunkCount = docStore.countChunks();

        if (docCount == 0) return "📊 知识库为空";

        long totalChars = 0;
        for (Document doc : docStore.getAllDocuments()) {
            totalChars += doc.content.length();
        }
        double avgChunks = docCount > 0 ? (double) chunkCount / docCount : 0;

        return "📊 知识库统计:\n"
                + "  文档数: " + docCount + "\n"
                + "  总字符数: " + totalChars + "\n"
                + "  分块数: " + chunkCount + "\n"
                + "  平均分块/文档: " + String.format("%.1f", avgChunks) + "\n"
                + "  向量维度: " + (vectorStore.isEmpty() ? "N/A"
                        : embedder.getDimension());
    }

    // ==================== 检索 ====================

    private List<ChunkHit> retrieveChunks(String query, int topK) {
        float[] queryVec = embedder.encode(
                EmbedderProvider.preprocessMarkdown(query));

        // 候选池扩大 3 倍，用关键词加权重排序
        int candidateK = Math.max(topK * 3, 30);
        List<VectorStore.VectorHit> hits = vectorStore.search(queryVec, candidateK);

        List<ChunkHit> results = new ArrayList<>();
        for (VectorStore.VectorHit hit : hits) {
            Chunk chunk = rawToChunk(docStore.getChunkRaw(hit.id));
            if (chunk != null) {
                double kwScore = keywordMatchScore(query, chunk.content);
                double hybridScore = hit.score * 0.6 + kwScore * 0.4; // 向量60% + 关键词40%
                results.add(new ChunkHit(chunk, hybridScore));
            }
        }

        results.sort((a, b) -> Double.compare(b.score, a.score));
        int n = Math.min(topK, results.size());
        return results.subList(0, n);
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

    private static final String RAG_SYSTEM_PROMPT =
            "你是一个严谨的知识库助手。请严格遵守以下规则：\n"
            + "1. 只根据下方参考资料中的内容回答，不得使用你自己的知识\n"
            + "2. 回答必须引用具体的来源编号，如「[来源1]」\n"
            + "3. 如果参考资料中有明确的定义、概念或事实，直接引用原文\n"
            + "4. 如果资料中只涉及部分内容，明确说明哪些来自资料、哪些资料未覆盖\n"
            + "5. 如果资料完全不涉及该问题，回答「参考资料中未直接涉及该问题」\n"
            + "6. 不要编造、推测或补充资料中没有的信息";

    private String buildRAGPrompt(String question, String context) {
        return "=== 参考资料 ===\n"
                + context + "\n"
                + "=== 用户问题 ===\n"
                + question + "\n\n"
                + "请严格基于资料回答：";
    }

    /** 双消息格式：规则用 system role（更高遵从度），资料+问题用 user role */
    private String callLLM(String prompt) {
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", RAG_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", prompt)
            );
            return llm.think(messages);
        } catch (Exception e) {
            return "（LLM 调用失败: " + e.getMessage() + "）";
        }
    }

    // ==================== 扩展检索 ====================

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

            boolean needLLM = enableMqe || enableHyde;
            if (needLLM && !initLLM()) {
                System.out.println("[RAG] LLM 不可用，降级为普通检索");
                return search(params);
            }

            List<ChunkHit> hits = searchVectorsExpanded(
                    query, topK, enableMqe, mqeExpansions, enableHyde,
                    poolMultiplier, scoreThreshold);

            if (hits.isEmpty()) return "🔍 未找到与 '" + query + "' 相关的内容（扩展检索）";

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
                Document doc = docStore.getDocument(chunk.docId);
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

    // ==================== MQE ====================

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

    // ==================== HyDE ====================

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

    private List<ChunkHit> searchVectorsExpanded(
            String query, int topK,
            boolean enableMqe, int mqeExpansions,
            boolean enableHyde, int poolMultiplier,
            Double scoreThreshold) {

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

        List<String> unique = new ArrayList<>();
        for (String e : expansions) {
            if (e != null && !e.isBlank() && !unique.contains(e)) {
                unique.add(e);
            }
        }
        System.out.println("[RAG] 扩展查询: " + unique.size() + " 个（原始 + MQE + HyDE）");

        int poolSize = Math.max(topK * poolMultiplier, 20);
        int perQuery = Math.max(1, poolSize / Math.max(1, unique.size()));
        System.out.println("[RAG] 候选池: " + poolSize + ", 每查询: " + perQuery);

        Map<String, ChunkHit> aggregated = new LinkedHashMap<>();

        for (String q : unique) {
            float[] queryVec = embedder.encode(
                    EmbedderProvider.preprocessMarkdown(q));

            List<VectorStore.VectorHit> hits = vectorStore.search(queryVec, perQuery);

            for (VectorStore.VectorHit hit : hits) {
                if (scoreThreshold != null && hit.score < scoreThreshold) continue;
                ChunkHit existing = aggregated.get(hit.id);
                if (existing == null || hit.score > existing.score) {
                    Chunk chunk = rawToChunk(docStore.getChunkRaw(hit.id));
                    if (chunk != null) {
                        aggregated.put(hit.id, new ChunkHit(chunk, hit.score));
                    }
                }
            }
        }

        List<ChunkHit> merged = new ArrayList<>(aggregated.values());
        merged.sort((a, b) -> Double.compare(b.score, a.score));
        int resultLimit = Math.min(topK, merged.size());

        System.out.println("[RAG] 合并结果: " + merged.size() + " 个唯一片段 → 返回 " + resultLimit);
        return merged.subList(0, resultLimit);
    }

    // ==================== 批量索引 ====================

    public void indexChunks(String docId, List<MarkdownChunker.Chunk> mdChunks) {
        if (mdChunks.isEmpty()) return;

        List<String> texts = new ArrayList<>();
        for (MarkdownChunker.Chunk mc : mdChunks) {
            texts.add(EmbedderProvider.preprocessMarkdown(mc.content));
        }

        System.out.println("[RAG] 批量嵌入: " + texts.size() + " 个分块, "
                + "后端: " + embedder.getActiveBackend()
                + " (" + embedder.getDimension() + " 维)");

        for (int i = 0; i < texts.size(); i++) {
            embedder.register(texts.get(i));
        }

        for (int i = 0; i < mdChunks.size(); i++) {
            MarkdownChunker.Chunk mc = mdChunks.get(i);
            String chunkId = UUID.randomUUID().toString();

            docStore.insertChunk(chunkId, docId, i, mc.content,
                    mc.startChar, mc.endChar, mc.headingPath);

            float[] vec = embedder.encode(texts.get(i));
            vectorStore.add(chunkId, vec);
        }
    }

    // ==================== 公开访问器 ====================

    public int docCount() { return docStore.countDocuments(); }
    public int chunkCount() { return docStore.countChunks(); }

    public Document getDocument(String idOrPrefix) {
        return docStore.getDocument(idOrPrefix);
    }

    public List<Chunk> getChunks(String docId) {
        Document doc = docStore.getDocument(docId);
        if (doc == null) return Collections.emptyList();
        List<Map<String, String>> raws = docStore.getChunksByDocId(doc.id);
        return raws.stream().map(this::rawToChunk)
                .filter(Objects::nonNull).collect(Collectors.toList());
    }

    public void clear() {
        docStore.clearAll();
        vectorStore.clear();
    }

    public EmbedderProvider getEmbedder() { return embedder; }

    /** 持久化向量存储 */
    public void saveVectors() { vectorStore.save(); }

    public void close() {
        if (ownDocStore) docStore.close();
    }

    // ==================== 类型转换 ====================

    private Chunk rawToChunk(Map<String, String> raw) {
        if (raw == null) return null;
        try {
            return new Chunk(
                    raw.get("id"),
                    raw.get("docId"),
                    Integer.parseInt(raw.getOrDefault("index", "0")),
                    raw.get("content"),
                    Integer.parseInt(raw.getOrDefault("startChar", "0")),
                    Integer.parseInt(raw.getOrDefault("endChar", "0")),
                    raw.get("headingPath")
            );
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 两阶段检索：LLM Reranker ====================

    /**
     * LLM 列表式重排序（Listwise Reranking）。
     * 将候选分块编号后交给 LLM，由 LLM 的全注意力机制做 query-passage 交叉打分，
     * 输出最相关分块的编号顺序。精度远超余弦相似度。
     */
    private List<ChunkHit> rerankWithLLM(String query, List<ChunkHit> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;

        // 构建排序 prompt：每个候选分块取前 250 字符做预览
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个搜索排序专家。根据用户查询，从候选文档片段中选出最相关的 ")
                .append(topK).append(" 个。\n\n");
        sb.append("用户查询: ").append(query).append("\n\n");
        sb.append("候选片段:\n");

        for (int i = 0; i < candidates.size(); i++) {
            Chunk chunk = candidates.get(i).chunk;
            String preview = chunk.content.length() > 250
                    ? chunk.content.substring(0, 250) + "..."
                    : chunk.content;
            sb.append("[").append(i + 1).append("] ").append(preview).append("\n\n");
        }

        sb.append("请只输出最相关的 ").append(topK)
                .append(" 个片段编号（用逗号分隔），例如: 3,7,12,1,5");

        try {
            String response = llm.think(List.of(
                    Map.of("role", "system", "content", "你是搜索排序专家。只输出最相关片段编号，用逗号分隔。不要解释。"),
                    Map.of("role", "user", "content", sb.toString())
            ));

            if (response == null || response.isBlank()) {
                System.out.println("[Reranker] LLM 无响应，降级为原始排序");
                return candidates.subList(0, topK);
            }

            // 解析排序结果
            List<Integer> ranking = parseRanking(response, candidates.size());
            if (ranking.isEmpty()) {
                System.out.println("[Reranker] 无法解析排序结果，降级为原始排序");
                return candidates.subList(0, topK);
            }

            List<ChunkHit> reranked = new ArrayList<>();
            for (int idx : ranking) {
                if (idx >= 0 && idx < candidates.size()) {
                    reranked.add(candidates.get(idx));
                }
            }

            // 不足 topK 时用原始排序补齐
            if (reranked.size() < topK) {
                for (ChunkHit hit : candidates) {
                    if (!reranked.contains(hit)) {
                        reranked.add(hit);
                        if (reranked.size() >= topK) break;
                    }
                }
            }

            System.out.println("[Reranker] 精排完成: " + ranking);
            return reranked.subList(0, Math.min(topK, reranked.size()));

        } catch (Exception e) {
            System.out.println("[Reranker] 失败: " + e.getMessage() + "，降级为原始排序");
            return candidates.subList(0, topK);
        }
    }

    /** 解析 LLM 返回的排序结果，支持 "3,7,12,1,5" 或 "[3, 7, 12, 1, 5]" 等格式 */
    private static List<Integer> parseRanking(String response, int maxNum) {
        List<Integer> result = new ArrayList<>();
        // 提取所有数字
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(response);
        while (m.find()) {
            int num = Integer.parseInt(m.group());
            if (num >= 1 && num <= maxNum) {
                // LLM 返回的是 1-indexed，转为 0-indexed
                int idx = num - 1;
                if (!result.contains(idx)) {
                    result.add(idx);
                }
            }
        }
        return result;
    }

    // ==================== 混合检索：关键词匹配 ====================

    /**
     * 计算查询与分块内容的关键词匹配得分。
     * 中文用 jieba 分词提取关键词，英文按空格和标点分词。
     * 得分 = 匹配到的关键词数 / 总关键词数，归一化到 [0, 1]。
     */
    private double keywordMatchScore(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) return 0;

        String qLower = query.toLowerCase();
        String cLower = content.toLowerCase();

        // 提取查询关键词
        Set<String> keywords = new java.util.LinkedHashSet<>();

        // 英文词（长度 ≥ 2，过滤短噪音）
        for (String word : qLower.split("[^a-z0-9]+")) {
            if (word.length() >= 2) keywords.add(word);
        }

        // 中文词：用 jieba 分词
        try {
            java.util.Map<String, Integer> cnFreq = com.example.agent.nlp.ChineseTokenizer.segmentWithFreq(query);
            for (String w : cnFreq.keySet()) {
                if (w.length() >= 2) keywords.add(w);
            }
        } catch (Exception ignored) {}

        if (keywords.isEmpty()) return 0;

        // 计算匹配比例
        int matched = 0;
        for (String kw : keywords) {
            if (cLower.contains(kw)) matched++;
        }

        return (double) matched / keywords.size();
    }

    // ==================== 工具方法 ====================

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

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    // ==================== 内部类型 ====================

    private static class ChunkHit {
        final Chunk chunk;
        final double score;
        ChunkHit(Chunk chunk, double score) { this.chunk = chunk; this.score = score; }
    }
}
