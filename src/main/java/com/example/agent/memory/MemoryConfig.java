package com.example.agent.memory;

/**
 * 记忆系统统一配置 — 替代分散在各类构造函数中的参数。
 *
 * 使用方式：
 * <pre>
 *   MemoryConfig config = MemoryConfig.builder()
 *       .workingMaxCapacity(100)
 *       .embedderFallbackDim(512)
 *       .build();
 *   WorkingMemory wm = new WorkingMemory(config);
 * </pre>
 */
public class MemoryConfig {

    // ==================== 工作记忆 ====================
    final int workingMaxCapacity;
    final int workingMaxAgeMinutes;

    // ==================== 嵌入 ====================
    final int embedderFallbackDim;

    // ==================== 感知记忆 ====================
    final int perceptualTextDim;
    final int perceptualImageDim;
    final int perceptualAudioDim;

    // ==================== 存储后端 ====================
    final String vectorStoreType;   // "inmemory" 或 "qdrant"
    final String graphStoreType;    // "inmemory" 或 "neo4j"
    final String qdrantUrl;
    final String neo4jUri;
    final String neo4jUser;
    final String neo4jPassword;

    private MemoryConfig(Builder builder) {
        this.workingMaxCapacity = builder.workingMaxCapacity;
        this.workingMaxAgeMinutes = builder.workingMaxAgeMinutes;
        this.embedderFallbackDim = builder.embedderFallbackDim;
        this.perceptualTextDim = builder.perceptualTextDim;
        this.perceptualImageDim = builder.perceptualImageDim;
        this.perceptualAudioDim = builder.perceptualAudioDim;
        this.vectorStoreType = builder.vectorStoreType;
        this.graphStoreType = builder.graphStoreType;
        this.qdrantUrl = builder.qdrantUrl;
        this.neo4jUri = builder.neo4jUri;
        this.neo4jUser = builder.neo4jUser;
        this.neo4jPassword = builder.neo4jPassword;
    }

    public static Builder builder() { return new Builder(); }

    /** 返回默认配置 */
    public static MemoryConfig defaults() { return builder().build(); }

    // ==================== Builder ====================

    public static class Builder {
        int workingMaxCapacity = 50;
        int workingMaxAgeMinutes = 60;
        int embedderFallbackDim = 256;
        int perceptualTextDim = 256;
        int perceptualImageDim = 512;
        int perceptualAudioDim = 512;
        String vectorStoreType = "inmemory";
        String graphStoreType = "inmemory";
        String qdrantUrl = "http://localhost:6333";
        String neo4jUri = "bolt://localhost:7687";
        String neo4jUser = "neo4j";
        String neo4jPassword = "password";

        public Builder workingMaxCapacity(int v)        { this.workingMaxCapacity = v; return this; }
        public Builder workingMaxAgeMinutes(int v)      { this.workingMaxAgeMinutes = v; return this; }
        public Builder embedderFallbackDim(int v)       { this.embedderFallbackDim = v; return this; }
        public Builder perceptualTextDim(int v)         { this.perceptualTextDim = v; return this; }
        public Builder perceptualImageDim(int v)        { this.perceptualImageDim = v; return this; }
        public Builder perceptualAudioDim(int v)        { this.perceptualAudioDim = v; return this; }
        public Builder vectorStoreType(String v)        { this.vectorStoreType = v; return this; }
        public Builder graphStoreType(String v)         { this.graphStoreType = v; return this; }
        public Builder qdrantUrl(String v)              { this.qdrantUrl = v; return this; }
        public Builder neo4jUri(String v)               { this.neo4jUri = v; return this; }
        public Builder neo4jUser(String v)              { this.neo4jUser = v; return this; }
        public Builder neo4jPassword(String v)          { this.neo4jPassword = v; return this; }

        public MemoryConfig build() { return new MemoryConfig(this); }
    }
}
