package com.example.agent.embedding;

import ai.onnxruntime.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.*;

import javax.imageio.ImageIO;

/**
 * CLIP ONNX 本地嵌入 — 文本与图像共享同一向量空间。
 *
 * 模型文件放在 models/clip/ 目录：
 *   - clip_text.onnx   (~350MB, 文本编码器)
 *   - clip_image.onnx  (~350MB, 图像编码器)
 *   - vocab.json        (~1MB,  BPE 词表)
 *   - merges.txt        (~500KB, BPE 合并规则)
 *
 * 导出方式（Python）：
 *   from transformers import CLIPModel
 *   model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32")
 *   # 文本侧
 *   torch.onnx.export(model.text_model, torch.zeros(1,77,dtype=torch.long),
 *       "clip_text.onnx", input_names=["input_ids"], output_names=["text_features"])
 *   # 图像侧
 *   torch.onnx.export(model.vision_model, torch.zeros(1,3,224,224),
 *       "clip_image.onnx", input_names=["pixel_values"], output_names=["image_features"])
 *
 * 向量维度: 512 (ViT-B/32)，文本和图像共享同一空间，可直接用余弦相似度做跨模态检索。
 */
public class CLIPOnnxEmbedding {

    // ==================== 常量 ====================

    private static final int IMAGE_SIZE = 224;
    private static final int TEXT_MAX_LENGTH = 77;
    private static final int EMBEDDING_DIM = 512;
    private static final int BOS_TOKEN = 49406;
    private static final int EOS_TOKEN = 49407;
    private static final int PAD_TOKEN = 0;

    // CLIP 图像归一化参数 (RGB)
    private static final float[] CLIP_MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] CLIP_STD  = {0.26862954f, 0.26130258f, 0.27577711f};

    // ==================== ONNX Runtime ====================

    private final OrtEnvironment env;
    private final OrtSession textSession;
    private final OrtSession imageSession;

    // ==================== 分词器 ====================

    private final Map<String, Integer> vocab;       // token → id
    private final Map<Integer, String> idToToken;   // id → token (用于 BPE)
    private final Map<BpePair, Integer> bpeRanks;    // 合并优先级
    private final Map<Integer, Character> byteToChar;  // byte → Unicode
    private final Map<Character, Integer> charToByte;  // Unicode → byte

    // ==================== 构造 ====================

    public CLIPOnnxEmbedding() throws OrtException, IOException {
        this(Paths.get("models", "clip", "clip_text.onnx"),
             Paths.get("models", "clip", "clip_image.onnx"),
             Paths.get("models", "clip", "vocab.json"),
             Paths.get("models", "clip", "merges.txt"));
    }

    public CLIPOnnxEmbedding(Path textModelPath, Path imageModelPath,
                            Path vocabPath, Path mergesPath) throws OrtException, IOException {
        for (Path p : List.of(textModelPath, imageModelPath, vocabPath, mergesPath)) {
            if (!Files.exists(p)) throw new IOException("模型文件不存在: " + p);
        }

        // ONNX 环境 & 模型
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());

        this.textSession = env.createSession(textModelPath.toString(), opts);
        this.imageSession = env.createSession(imageModelPath.toString(), opts);

        // 加载 BPE 词表
        this.vocab = loadVocab(vocabPath);
        this.idToToken = new HashMap<>();
        for (Map.Entry<String, Integer> e : vocab.entrySet()) {
            idToToken.put(e.getValue(), e.getKey());
        }

        // 加载 BPE 合并规则
        this.bpeRanks = loadMerges(mergesPath);

        // 构建 byte ↔ char 映射 (GPT-2/CLIP 标准编码)
        this.byteToChar = bytesToUnicode();
        this.charToByte = new HashMap<>();
        for (Map.Entry<Integer, Character> e : byteToChar.entrySet()) {
            charToByte.put(e.getValue(), e.getKey());
        }

        System.out.println("[CLIP] ONNX 模型已加载"
                + " | 文本输入: " + textSession.getInputNames()
                + " | 图像输入: " + imageSession.getInputNames());
    }

    // ==================== 公开 API ====================

    /** 图像编码 — 从文件路径 */
    public float[] encodeImage(String imagePath) throws IOException, OrtException {
        BufferedImage img = ImageIO.read(new File(imagePath));
        if (img == null) throw new IOException("无法读取图像: " + imagePath);
        return encodeImage(img);
    }

    /** 图像编码 — 从 BufferedImage */
    public float[] encodeImage(BufferedImage image) throws OrtException {
        float[] pixels = preprocessImage(image);

        long[] shape = {1, 3, IMAGE_SIZE, IMAGE_SIZE};
        FloatBuffer buf = FloatBuffer.allocate(3 * IMAGE_SIZE * IMAGE_SIZE);
        for (float p : pixels) buf.put(p);
        buf.flip();

        try (OnnxTensor input = OnnxTensor.createTensor(env, buf, shape)) {
            Map<String, OnnxTensor> inputs = Map.of("pixel_values", input);
            OrtSession.Result result = imageSession.run(inputs);

            float[] vector;
            OnnxTensor output = (OnnxTensor) result.get("image_features").get();
            float[][] raw = (float[][]) output.getValue();
            vector = raw[0];

            result.close();
            return normalize(vector);
        }
    }

    /** 文本编码 */
    public float[] encodeText(String text) throws OrtException {
        int[] inputIds = tokenize(text);

        long[] shape = {1, inputIds.length};
        LongBuffer buf = LongBuffer.allocate(inputIds.length);
        for (int id : inputIds) buf.put(id);
        buf.flip();

        try (OnnxTensor input = OnnxTensor.createTensor(env, buf, shape)) {
            Map<String, OnnxTensor> inputs = Map.of("input_ids", input);
            OrtSession.Result result = textSession.run(inputs);

            float[] vector;
            OnnxTensor output = (OnnxTensor) result.get("text_features").get();
            float[][] raw = (float[][]) output.getValue();
            vector = raw[0];

            result.close();
            return normalize(vector);
        }
    }

    /** 便捷方法：文本搜图场景 — 用文本查询匹配图像向量空间 */
    public float[] encodeQueryForImage(String query) throws OrtException {
        return encodeText(query);
    }

    public int getDimension() { return EMBEDDING_DIM; }

    /** 检查模型文件是否就绪 */
    public static boolean isModelReady() {
        return Files.exists(Paths.get("models", "clip", "clip_text.onnx"))
                && Files.exists(Paths.get("models", "clip", "clip_image.onnx"))
                && Files.exists(Paths.get("models", "clip", "vocab.json"))
                && Files.exists(Paths.get("models", "clip", "merges.txt"));
    }

    // ==================== 图像预处理 ====================

    /**
     * CLIP 图像预处理管线：
     *   resize(224×224) → RGB float [0,1] → normalize(mean, std) → NCHW layout
     */
    private float[] preprocessImage(BufferedImage original) {
        // 1. Resize 到 224×224（双线性插值 + 中心裁剪等效）
        BufferedImage resized = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D gfx = resized.createGraphics();
        gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gfx.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 等比缩放 + 中心裁剪 (Resize + CenterCrop)
        int w = original.getWidth();
        int h = original.getHeight();
        int size = Math.min(w, h);
        int sx = (w - size) / 2;
        int sy = (h - size) / 2;
        gfx.drawImage(original, 0, 0, IMAGE_SIZE, IMAGE_SIZE, sx, sy, sx + size, sy + size, null);
        gfx.dispose();

        // 2. 提取 RGB → float[0,1] → normalize → NCHW layout
        // NCHW: [C][H][W], 即先按 channel (R,G,B)，每个 channel 内按行优先
        float[] chw = new float[3 * IMAGE_SIZE * IMAGE_SIZE];
        int rOffset = 0;
        int gOffset = IMAGE_SIZE * IMAGE_SIZE;
        int bOffset = 2 * IMAGE_SIZE * IMAGE_SIZE;

        for (int y = 0; y < IMAGE_SIZE; y++) {
            for (int x = 0; x < IMAGE_SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                float r = ((rgb >> 16) & 0xFF) / 255.0f;
                float g = ((rgb >> 8) & 0xFF) / 255.0f;
                float b = (rgb & 0xFF) / 255.0f;

                int idx = y * IMAGE_SIZE + x;
                chw[rOffset + idx] = (r - CLIP_MEAN[0]) / CLIP_STD[0];
                chw[gOffset + idx] = (g - CLIP_MEAN[1]) / CLIP_STD[1];
                chw[bOffset + idx] = (b - CLIP_MEAN[2]) / CLIP_STD[2];
            }
        }

        return chw;
    }

    // ==================== BPE 分词器 ====================

    /**
     * 完整分词管线：文本 → 小写 → 分词 → BPE → token IDs → [BOS] + ids + [EOS] → 截断/填充到77
     */
    private int[] tokenize(String text) {
        if (text == null || text.isEmpty()) text = " ";

        String lower = text.toLowerCase().strip();

        // 1. 分割为"单词"（空格分隔，但保留标点作为独立单元）
        List<String> words = splitWords(lower);

        // 2. 对每个单词应用 BPE
        List<Integer> tokenIds = new ArrayList<>();
        tokenIds.add(BOS_TOKEN);

        for (String word : words) {
            // 单词 → 字节序列
            List<Integer> bytes = wordToBytes(word);
            // 字节序列 → 字节的 Unicode 表示
            List<String> chars = new ArrayList<>();
            for (int b : bytes) chars.add(String.valueOf(byteToChar.get(b)));

            // BPE 合并
            List<String> bpeTokens = bpe(chars);

            // 映射到 vocab id
            for (String token : bpeTokens) {
                Integer id = vocab.get(token);
                tokenIds.add(id != null ? id : vocab.getOrDefault(token, BOS_TOKEN));
            }
        }

        tokenIds.add(EOS_TOKEN);

        // 3. 截断/填充到 77
        if (tokenIds.size() > TEXT_MAX_LENGTH) {
            tokenIds = new ArrayList<>(tokenIds.subList(0, TEXT_MAX_LENGTH - 1));
            tokenIds.add(EOS_TOKEN);
        }
        while (tokenIds.size() < TEXT_MAX_LENGTH) {
            tokenIds.add(PAD_TOKEN);
        }

        int[] result = new int[tokenIds.size()];
        for (int i = 0; i < tokenIds.size(); i++) result[i] = tokenIds.get(i);
        return result;
    }

    /** 按空格/标点切分，保留标点为独立 token */
    private List<String> splitWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (buf.length() > 0) { words.add(buf.toString()); buf.setLength(0); }
            } else if (isClipPunct(c)) {
                if (buf.length() > 0) { words.add(buf.toString()); buf.setLength(0); }
                words.add(String.valueOf(c));
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) words.add(buf.toString());
        return words;
    }

    private static boolean isClipPunct(char c) {
        return "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0;
    }

    /** 字符串 → 字节列表（UTF-8 编码），每个字节映射到对应 Unicode 字符后再回到字节 */
    private List<Integer> wordToBytes(String word) {
        List<Integer> bytes = new ArrayList<>();
        for (char c : word.toCharArray()) {
            // 先检查是否已是一个 byte-char（Unicode 映射字符）
            Integer b = charToByte.get(c);
            if (b != null) {
                bytes.add(b);
            } else {
                // 普通字符走 UTF-8 编码
                byte[] utf8 = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte utf8b : utf8) bytes.add(utf8b & 0xFF);
            }
        }
        return bytes;
    }

    /** BPE 合并算法 */
    private List<String> bpe(List<String> chars) {
        if (chars.size() <= 1) return new ArrayList<>(chars);

        while (true) {
            // 找到优先级最高（rank 最小）的相邻符号对
            int bestRank = Integer.MAX_VALUE;
            int bestIdx = -1;
            for (int i = 0; i < chars.size() - 1; i++) {
                Integer rank = bpeRanks.get(new BpePair(chars.get(i), chars.get(i + 1)));
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) break;  // 无可合并的对

            // 合并
            String merged = chars.get(bestIdx) + chars.get(bestIdx + 1);
            chars.set(bestIdx, merged);
            chars.remove(bestIdx + 1);
        }

        return chars;
    }

    // ==================== 词表加载 ====================

    /** 加载 BPE vocab.json: {"token": id, ...} */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> loadVocab(Path path) throws IOException {
        String json = Files.readString(path);
        Map<String, Object> raw = new com.google.gson.Gson().fromJson(json, Map.class);
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            map.put(e.getKey(), ((Double) e.getValue()).intValue());
        }
        return map;
    }

    /** 加载 BPE merges.txt: 每行 "token_a token_b"，行号即为优先级（rank） */
    private static Map<BpePair, Integer> loadMerges(Path path) throws IOException {
        Map<BpePair, Integer> map = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path);
        int rank = 0;
        for (String line : lines) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;  // 跳过注释/版本行
            String[] parts = line.split("\\s+");
            if (parts.length == 2) {
                map.put(new BpePair(parts[0], parts[1]), rank++);
            }
        }
        return map;
    }

    /** GPT-2/CLIP 标准 byte ↔ Unicode 映射表 */
    private static Map<Integer, Character> bytesToUnicode() {
        Map<Integer, Character> map = new LinkedHashMap<>();
        // 可打印 ASCII 直接映射自身
        for (int b = '!'; b <= '~'; b++) map.put(b, (char) b);
        for (int b = 0xA1; b <= 0xAC; b++) map.put(b, (char) b);
        for (int b = 0xAE; b <= 0xFF; b++) map.put(b, (char) b);

        // 其余字节映射到高位 Unicode 区域
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!map.containsKey(b)) {
                map.put(b, (char) (256 + n));
                n++;
            }
        }
        return map;
    }

    // ==================== 工具方法 ====================

    /** L2 归一化 */
    private static float[] normalize(float[] vec) {
        double norm = 0;
        for (float v : vec) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        }
        return vec;
    }

    /** 关闭资源 */
    public void close() throws OrtException {
        textSession.close();
        imageSession.close();
        env.close();
    }

    // ==================== 内部类型 ====================

    /** BPE 符号对（不可变，用于 HashMap key） */
    private static class BpePair {
        final String a, b;
        final int hash;
        BpePair(String a, String b) { this.a = a; this.b = b; this.hash = Objects.hash(a, b); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof BpePair)) return false;
            BpePair p = (BpePair) o;
            return a.equals(p.a) && b.equals(p.b);
        }
        @Override public int hashCode() { return hash; }
    }
}
