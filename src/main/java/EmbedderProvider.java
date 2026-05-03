import java.util.*;
import java.util.regex.Pattern;

/**
 * 统一嵌入接口，支持多后端自动降级。
 *
 * 降级链: BGE 本地 ONNX → LLM Embedding API → 百炼 API → 本地 TF-IDF
 * BGE 免费、中文最优、完全离线，是默认推荐方案。
 */
public class EmbedderProvider {

    // 后端标识
    public enum Backend { BGE, LLM_EMBED, BAILIAN, TFIDF, NONE }

    // 默认向量维度
    public static final int BAILIAN_DIM = 1536;
    public static final int BGE_DIM = 512;  // bge-small-zh，实际维度自动检测
    public static final int GENERIC_EMBED_DIM = 1536;
    private final int tfidfDim;

    private BGEOnnxEmbedding bgeClient;
    private LLMEmbeddingClient llmEmbedClient;
    private BailianEmbeddingClient bailianClient;
    private EpisodicMemory.TextEmbedder tfidfEmbedder;
    private Backend activeBackend = Backend.NONE;
    private int activeDim = 256;

    private boolean bgeTried;
    private boolean llmEmbedTried;
    private boolean bailianTried;

    // ==================== 构造 ====================

    public EmbedderProvider() {
        this(256);
    }

    public EmbedderProvider(int tfidfDim) {
        this.tfidfDim = tfidfDim;
        this.activeDim = tfidfDim;
    }

    // ==================== 嵌入接口 ====================

    /** 单文本编码 */
    public float[] encode(String text) {
        List<float[]> results = encodeBatch(List.of(text));
        return results.isEmpty() ? zeroVector() : results.get(0);
    }

    /** 批量编码 */
    public List<float[]> encodeBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();

        // 1. 尝试 BGE 本地 ONNX 模型（免费 + 中文最优 + 离线）
        if (activeBackend == Backend.NONE && !bgeTried) {
            tryBGE();
        }
        if (activeBackend == Backend.BGE && bgeClient != null) {
            try {
                List<float[]> results = bgeClient.encodeBatch(texts);
                if (!results.isEmpty()) return results;
            } catch (Exception e) {
                System.out.println("[Embedder] BGE 推理失败: " + e.getMessage());
            }
            System.out.println("[Embedder] BGE 失败，降级到 LLM Embedding API");
            activeBackend = Backend.NONE;
            activeDim = tfidfDim;
        }

        // 2. 尝试 LLM 提供商 Embedding API（复用 LLM_API_KEY/LLM_BASE_URL）
        if (activeBackend == Backend.NONE && !llmEmbedTried) {
            tryLLMEmbed();
        }
        if (activeBackend == Backend.LLM_EMBED && llmEmbedClient != null) {
            List<float[]> results = llmEmbedClient.encode(texts);
            if (!results.isEmpty()) return results;
            System.out.println("[Embedder] LLM Embedding API 失败，降级到百炼");
            activeBackend = Backend.NONE;
            activeDim = tfidfDim;
        }

        // 3. 尝试百炼 API
        if (activeBackend == Backend.NONE && !bailianTried) {
            tryBailian();
        }
        if (activeBackend == Backend.BAILIAN && bailianClient != null) {
            List<float[]> results = bailianClient.encode(texts);
            if (!results.isEmpty()) return results;
            System.out.println("[Embedder] 百炼 API 失败，降级到 TF-IDF");
            activeBackend = Backend.TFIDF;
            activeDim = tfidfDim;
        }

        // 4. 降级: TF-IDF
        if (activeBackend == Backend.TFIDF || activeBackend == Backend.NONE) {
            if (tfidfEmbedder == null) {
                tfidfEmbedder = new EpisodicMemory.TextEmbedder(tfidfDim);
            }
            if (activeBackend == Backend.NONE) {
                activeBackend = Backend.TFIDF;
                activeDim = tfidfDim;
                System.out.println("[Embedder] 使用 TF-IDF 本地嵌入 (" + tfidfDim + " 维)");
            }

            List<float[]> results = new ArrayList<>();
            for (String text : texts) {
                tfidfEmbedder.register(text);
                results.add(tfidfEmbedder.encode(text));
            }
            return results;
        }

        return Collections.emptyList();
    }

    /** 注册文本到 TF-IDF 词汇表（用于构建 DF 统计） */
    public void register(String text) {
        if (tfidfEmbedder != null) {
            tfidfEmbedder.register(text);
        }
    }

    // ==================== 后端切换 ====================

    private void tryBGE() {
        bgeTried = true;
        if (BGEOnnxEmbedding.isModelReady()) {
            try {
                bgeClient = new BGEOnnxEmbedding();
                float[] testVec = bgeClient.encode("test");
                if (testVec != null && testVec.length > 0) {
                    activeBackend = Backend.BGE;
                    activeDim = testVec.length;
                    System.out.println("[Embedder] BGE 本地模型就绪 ("
                            + testVec.length + " 维, 免费 + 离线)");
                    return;
                }
            } catch (Exception e) {
                System.out.println("[Embedder] BGE 模型不可用: " + e.getMessage());
            }
        }
        System.out.println("[Embedder] BGE 模型未安装，尝试 LLM Embedding API...");
    }

    private void tryLLMEmbed() {
        llmEmbedTried = true;
        if (LLMEmbeddingClient.isConfigured()) {
            try {
                llmEmbedClient = new LLMEmbeddingClient();
                float[] testVec = llmEmbedClient.encode("test");
                if (testVec != null && testVec.length > 0) {
                    activeBackend = Backend.LLM_EMBED;
                    activeDim = testVec.length;
                    System.out.println("[Embedder] LLM Embedding API 就绪 ("
                            + llmEmbedClient.getModel() + ", " + testVec.length + " 维)");
                    return;
                }
            } catch (Exception e) {
                System.out.println("[Embedder] LLM Embedding API 不可用: " + e.getMessage());
            }
        }
        System.out.println("[Embedder] LLM Embedding API 未配置，尝试百炼...");
    }

    private void tryBailian() {
        bailianTried = true;
        if (BailianEmbeddingClient.isConfigured()) {
            try {
                bailianClient = new BailianEmbeddingClient();
                // 快速连通性测试
                float[] testVec = bailianClient.encode("test");
                if (testVec != null && testVec.length > 0) {
                    activeBackend = Backend.BAILIAN;
                    System.out.println("[Embedder] 百炼 API 就绪 (" + testVec.length + " 维)");
                    return;
                }
            } catch (Exception e) {
                System.out.println("[Embedder] 百炼 API 不可用: " + e.getMessage());
            }
        }
        // 直接降级
        activeBackend = Backend.TFIDF;
        if (tfidfEmbedder == null) {
            tfidfEmbedder = new EpisodicMemory.TextEmbedder(tfidfDim);
        }
        System.out.println("[Embedder] 使用 TF-IDF 本地嵌入 (" + tfidfDim + " 维)");
    }

    /** 强制切换到指定后端 */
    public void forceBackend(Backend backend) {
        this.activeBackend = backend;
        this.bailianTried = true;
    }

    // ==================== 查询 ====================

    public Backend getActiveBackend() { return activeBackend; }

    public int getDimension() {
        return activeDim;
    }

    // ==================== 工具 ====================

    private float[] zeroVector() {
        return new float[getDimension()];
    }

    /**
     * 预处理 Markdown 文本以获得更好的嵌入质量。
     * - 去除代码块（``` ... ```）
     * - 去除图片链接（![...](...)）
     * - 去除 HTML 标签
     * - 压缩多余空白
     */
    public static String preprocessMarkdown(String raw) {
        if (raw == null || raw.isBlank()) return "";

        String text = raw;

        // 去除代码块
        text = Pattern.compile("```[\\s\\S]*?```", Pattern.MULTILINE).matcher(text).replaceAll(" ");
        // 去除行内代码
        text = Pattern.compile("`[^`]+`").matcher(text).replaceAll(" ");
        // 去除图片
        text = Pattern.compile("!\\[.*?]\\(.*?\\)").matcher(text).replaceAll(" ");
        // 去除链接（保留链接文本）
        text = Pattern.compile("\\[(.*?)]\\(.*?\\)").matcher(text).replaceAll("$1");
        // 去除 HTML 标签
        text = Pattern.compile("<[^>]+>").matcher(text).replaceAll(" ");
        // 去除 Markdown 格式符号（粗体、斜体）
        text = Pattern.compile("\\*{1,3}|_{1,3}").matcher(text).replaceAll("");
        // 压缩空白
        text = Pattern.compile("\\s+").matcher(text).replaceAll(" ");

        return text.strip();
    }

    // ==================== 余弦相似度 ====================

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }
}
