import java.util.*;
import java.util.regex.Pattern;

/**
 * 统一嵌入接口，支持多后端自动降级。
 *
 * 降级链: 百炼 API → 本地 TF-IDF
 * 未来可扩展: HuggingFace 本地模型（DJL）、Ollama 等
 */
public class EmbedderProvider {

    // 后端标识
    public enum Backend { BAILIAN, TFIDF, NONE }

    // 默认向量维度（百炼 text-embedding-v2 = 1536，本地 TF-IDF 可配置）
    public static final int BAILIAN_DIM = 1536;
    private final int tfidfDim;

    private BailianEmbeddingClient bailianClient;
    private EpisodicMemory.TextEmbedder tfidfEmbedder;
    private Backend activeBackend = Backend.NONE;

    private boolean bailianTried;

    // ==================== 构造 ====================

    public EmbedderProvider() {
        this(256);
    }

    public EmbedderProvider(int tfidfDim) {
        this.tfidfDim = tfidfDim;
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

        // 1. 尝试百炼 API
        if (activeBackend == Backend.NONE && !bailianTried) {
            tryBailian();
        }
        if (activeBackend == Backend.BAILIAN && bailianClient != null) {
            List<float[]> results = bailianClient.encode(texts);
            if (!results.isEmpty()) return results;
            // 百炼失败 → 降级
            System.out.println("[Embedder] 百炼 API 失败，降级到 TF-IDF");
            activeBackend = Backend.TFIDF;
        }

        // 2. 降级: TF-IDF
        if (activeBackend == Backend.TFIDF || activeBackend == Backend.NONE) {
            if (tfidfEmbedder == null) {
                tfidfEmbedder = new EpisodicMemory.TextEmbedder(tfidfDim);
            }
            if (activeBackend == Backend.NONE) {
                activeBackend = Backend.TFIDF;
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
        if (activeBackend == Backend.BAILIAN) return BAILIAN_DIM;
        return tfidfDim;
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
