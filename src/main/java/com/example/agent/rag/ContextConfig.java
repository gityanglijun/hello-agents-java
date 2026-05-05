package com.example.agent.rag;

/**
 * 上下文构建配置 — 对应 Python ContextConfig dataclass。
 * 控制将哪些候选信息包纳入 LLM 上下文、如何排序和压缩。
 */
public class ContextConfig {

    private final int maxTokens;
    private final double reserveRatio;
    private final double minRelevance;
    private final boolean enableCompression;
    private final double recencyWeight;
    private final double relevanceWeight;

    private ContextConfig(Builder builder) {
        this.maxTokens = builder.maxTokens;
        this.reserveRatio = builder.reserveRatio;
        this.minRelevance = builder.minRelevance;
        this.enableCompression = builder.enableCompression;
        this.recencyWeight = builder.recencyWeight;
        this.relevanceWeight = builder.relevanceWeight;

        // 等价于 Python __post_init__ 的校验
        if (reserveRatio < 0.0 || reserveRatio > 1.0) {
            throw new IllegalArgumentException("reserveRatio 必须在 [0, 1] 范围内，当前值: " + reserveRatio);
        }
        if (minRelevance < 0.0 || minRelevance > 1.0) {
            throw new IllegalArgumentException("minRelevance 必须在 [0, 1] 范围内，当前值: " + minRelevance);
        }
        if (Math.abs(recencyWeight + relevanceWeight - 1.0) > 1e-6) {
            throw new IllegalArgumentException(
                    "recencyWeight + relevanceWeight 必须等于 1.0，当前: "
                    + recencyWeight + " + " + relevanceWeight + " = " + (recencyWeight + relevanceWeight));
        }
    }

    public static Builder builder() { return new Builder(); }
    public static ContextConfig defaults() { return builder().build(); }

    // ==================== 访问器 ====================

    public int maxTokens()             { return maxTokens; }
    public double reserveRatio()       { return reserveRatio; }
    public double minRelevance()       { return minRelevance; }
    public boolean enableCompression() { return enableCompression; }
    public double recencyWeight()      { return recencyWeight; }
    public double relevanceWeight()    { return relevanceWeight; }

    /** 扣除系统预留后的实际可用 token 数 */
    public int effectiveMaxTokens() {
        return (int) (maxTokens * (1.0 - reserveRatio));
    }

    // ==================== Builder ====================

    public static class Builder {
        int maxTokens = 3000;
        double reserveRatio = 0.2;
        double minRelevance = 0.1;
        boolean enableCompression = true;
        double recencyWeight = 0.3;
        double relevanceWeight = 0.7;

        public Builder maxTokens(int v)           { this.maxTokens = v; return this; }
        public Builder reserveRatio(double v)     { this.reserveRatio = v; return this; }
        public Builder minRelevance(double v)     { this.minRelevance = v; return this; }
        public Builder enableCompression(boolean v) { this.enableCompression = v; return this; }
        public Builder recencyWeight(double v)    { this.recencyWeight = v; return this; }
        public Builder relevanceWeight(double v)  { this.relevanceWeight = v; return this; }

        public ContextConfig build() { return new ContextConfig(this); }
    }

    @Override
    public String toString() {
        return "ContextConfig{maxTokens=" + maxTokens
                + ", reserveRatio=" + reserveRatio
                + ", minRelevance=" + minRelevance
                + ", compression=" + enableCompression
                + ", recencyW=" + recencyWeight
                + ", relevanceW=" + relevanceWeight + "}";
    }
}
