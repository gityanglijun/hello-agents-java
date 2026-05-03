package com.example.agent.embedding;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

import ai.onnxruntime.*;

/**
 * BGE（BAAI General Embedding）本地嵌入客户端。
 *
 * 使用 ONNX Runtime 加载 BGE 模型（如 bge-small-zh-v1.5），纯本地推理，
 * 零 API 费用，中文理解能力优于 text-embedding-3。
 *
 * 模型文件放在 models/bge/ 目录：
 *   - model.onnx   (~130MB, ONNX 格式模型)
 *   - vocab.txt     (~100KB, BERT 词表)
 *
 * 获取方式：
 *   pip install optimum[onnxruntime]
 *   optimum-cli export onnx --model BAAI/bge-small-zh-v1.5 models/bge/
 * 或从 HuggingFace 下载已转换的 ONNX 模型。
 */
public class BGEOnnxEmbedding {

    // ==================== 常量 ====================

    private static final int MAX_LENGTH = 512;
    private static final int CLS_ID = 101;
    private static final int SEP_ID = 102;
    private static final int UNK_ID = 100;
    private static final int PAD_ID = 0;

    // ==================== ONNX Runtime ====================

    private final OrtEnvironment env;
    private final OrtSession session;
    private final boolean usePrePooled;     // 模型是否已内置 mean pooling
    private final boolean needTokenTypeIds; // 模型是否需要 token_type_ids 输入
    private Integer detectedDim;            // 首次推理后填充

    // ==================== 词表 ====================

    private final Map<String, Integer> vocab;       // token → id
    private final int maxInputChars;                // 最大词条长度（用于贪心匹配上限）

    // ==================== 构造 ====================

    public BGEOnnxEmbedding() throws OrtException, IOException {
        this(Paths.get("models", "bge", "model.onnx"),
             Paths.get("models", "bge", "vocab.txt"));
    }

    public BGEOnnxEmbedding(Path modelPath, Path vocabPath) throws OrtException, IOException {
        if (!Files.exists(modelPath)) {
            throw new IOException("模型文件不存在: " + modelPath);
        }
        if (!Files.exists(vocabPath)) {
            throw new IOException("词表文件不存在: " + vocabPath);
        }

        // 1. 加载 ONNX 模型
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
        this.session = env.createSession(modelPath.toString(), opts);

        // 检测输入/输出格式
        Set<String> inputNames = session.getInputNames();
        Set<String> outputNames = session.getOutputNames();
        this.needTokenTypeIds = inputNames.contains("token_type_ids");
        this.usePrePooled = outputNames.contains("sentence_embedding");

        // 2. 加载词表
        this.vocab = loadVocab(vocabPath);
        this.maxInputChars = vocab.keySet().stream()
                .mapToInt(String::length).max().orElse(10);

        System.out.println("[BGE] ONNX 模型已加载: " + modelPath.getFileName()
                + " | 输入: " + session.getInputNames()
                + " | 输出: " + session.getOutputNames());
    }

    // ==================== 公开 API ====================

    public float[] encode(String text) throws OrtException {
        int[] inputIds = tokenize(text);
        return runInference(inputIds);
    }

    public List<float[]> encodeBatch(List<String> texts) throws OrtException {
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(encode(text));
        }
        return results;
    }

    /** 向量维度（首次推理后返回实际值，推理前返回 512） */
    public int getDimension() { return detectedDim != null ? detectedDim : 512; }

    /** 检查模型文件是否就绪 */
    public static boolean isModelReady() {
        return Files.exists(Paths.get("models", "bge", "model.onnx"))
                && Files.exists(Paths.get("models", "bge", "vocab.txt"));
    }

    // ==================== 分词（BERT Chinese WordPiece） ====================

    /**
     * 将文本转换为 input_ids（含 [CLS] 和 [SEP]，截断到 MAX_LENGTH）。
     */
    private int[] tokenize(String text) {
        if (text == null || text.isEmpty()) text = " ";
        List<String> basicTokens = basicTokenize(text);
        List<Integer> ids = new ArrayList<>();
        ids.add(CLS_ID);

        for (String token : basicTokens) {
            if (ids.size() >= MAX_LENGTH - 1) break;
            wordpieceTokenize(token, ids);
        }

        // 截断到 MAX_LENGTH - 1，为 [SEP] 留位置（WordPiece 可能一次添加多个子词）
        while (ids.size() > MAX_LENGTH - 1) {
            ids.remove(ids.size() - 1);
        }
        ids.add(SEP_ID);
        int[] result = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);
        return result;
    }

    /**
     * 基础分词：中文逐字拆分，英文按空格和标点拆分，全转小写。
     */
    private List<String> basicTokenize(String text) {
        List<String> tokens = new ArrayList<>();
        // 先在中文/英文边界、标点周围加空格
        text = text.toLowerCase();

        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                // 中文前后加空格
                if (buf.length() > 0) {
                    tokens.add(buf.toString().strip());
                    buf.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else if (Character.isWhitespace(c)) {
                if (buf.length() > 0) {
                    tokens.add(buf.toString().strip());
                    buf.setLength(0);
                }
            } else if (isPunctuation(c)) {
                if (buf.length() > 0) {
                    tokens.add(buf.toString().strip());
                    buf.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            String last = buf.toString().strip();
            if (!last.isEmpty()) tokens.add(last);
        }

        return tokens;
    }

    /**
     * WordPiece 贪心最长匹配。
     */
    private void wordpieceTokenize(String token, List<Integer> ids) {
        if (token.isEmpty()) return;
        // 先试完整 token
        Integer id = vocab.get(token);
        if (id != null) {
            ids.add(id);
            return;
        }

        // 贪心最长匹配
        int start = 0;
        while (start < token.length()) {
            int end = Math.min(token.length(), start + maxInputChars);
            boolean matched = false;
            while (end > start) {
                String sub = (start > 0 ? "##" : "") + token.substring(start, end);
                id = vocab.get(sub);
                if (id != null) {
                    ids.add(id);
                    start = end;
                    matched = true;
                    break;
                }
                end--;
            }
            if (!matched) {
                ids.add(UNK_ID);
                start++;
            }
        }
    }

    // ==================== ONNX 推理 ====================

    /**
     * 运行 ONNX 推理，返回归一化后的句子向量。
     */
    private float[] runInference(int[] inputIds) throws OrtException {
        int seqLen = inputIds.length;
        long[] shape = {1, seqLen};

        // 构建 input_ids
        LongBuffer idsBuf = LongBuffer.allocate(seqLen);
        for (int id : inputIds) idsBuf.put(id);
        idsBuf.flip();
        OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, idsBuf, shape);

        // 构建 attention_mask (全 1)
        LongBuffer maskBuf = LongBuffer.allocate(seqLen);
        for (int i = 0; i < seqLen; i++) maskBuf.put(1);
        maskBuf.flip();
        OnnxTensor maskTensor = OnnxTensor.createTensor(env, maskBuf, shape);

        // 构建 token_type_ids (全 0)，仅当模型需要时
        OnnxTensor typeTensor = null;
        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        inputs.put("input_ids", inputIdsTensor);
        inputs.put("attention_mask", maskTensor);
        if (needTokenTypeIds) {
            LongBuffer typeBuf = LongBuffer.allocate(seqLen);
            for (int i = 0; i < seqLen; i++) typeBuf.put(0);
            typeBuf.flip();
            typeTensor = OnnxTensor.createTensor(env, typeBuf, shape);
            inputs.put("token_type_ids", typeTensor);
        }

        // 推理
        OrtSession.Result result = session.run(inputs);
        float[] vector;

        if (usePrePooled) {
            // 模型已做 mean pooling: [1, dim]
            OnnxTensor output = (OnnxTensor) result.get("sentence_embedding").get();
            float[][] raw = (float[][]) output.getValue();
            vector = raw[0];
        } else {
            // 原始 hidden_states: [1, seqLen, hiddenDim] → 需要 mean pooling
            OnnxTensor output = (OnnxTensor) result.get("last_hidden_state").get();
            float[][][] raw = (float[][][]) output.getValue();
            int outSeqLen = raw[0].length;
            int outDim = raw[0][0].length;

            // Mean pooling: 对除 padding 外的 token 取平均，跳过 [CLS] 和 [SEP]
            vector = new float[outDim];
            int start = 1;                    // 跳过 [CLS]
            int end = Math.min(outSeqLen - 1, outSeqLen); // 跳过 [SEP]
            for (int i = start; i < end; i++) {
                for (int j = 0; j < outDim; j++) {
                    vector[j] += raw[0][i][j];
                }
            }
            int count = Math.max(1, end - start);
            for (int j = 0; j < outDim; j++) {
                vector[j] /= count;
            }
        }

        // 首次推理后记录实际维度
        if (detectedDim == null) {
            detectedDim = vector.length;
            System.out.println("[BGE] 向量维度: " + detectedDim);
        }

        // 清理
        inputIdsTensor.close();
        maskTensor.close();
        if (typeTensor != null) typeTensor.close();
        result.close();

        return normalize(vector);
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

    /** 判断是否为 CJK 字符 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    /** 判断是否为英文标点 */
    private static boolean isPunctuation(char c) {
        return Pattern.matches("\\p{Punct}", String.valueOf(c))
                && !(c == '-' || c == '#');  // 保留 ## 子词标记符和连字符
    }

    /** 加载 BERT WordPiece 词表 */
    private static Map<String, Integer> loadVocab(Path path) throws IOException {
        Map<String, Integer> map = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path);
        for (int i = 0; i < lines.size(); i++) {
            String token = lines.get(i).strip();
            if (!token.isEmpty()) {
                map.put(token, i);
            }
        }
        return map;
    }
}
