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

    private MemoryConfig(Builder builder) {
        this.workingMaxCapacity = builder.workingMaxCapacity;
        this.workingMaxAgeMinutes = builder.workingMaxAgeMinutes;
        this.embedderFallbackDim = builder.embedderFallbackDim;
        this.perceptualTextDim = builder.perceptualTextDim;
        this.perceptualImageDim = builder.perceptualImageDim;
        this.perceptualAudioDim = builder.perceptualAudioDim;
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

        public Builder workingMaxCapacity(int v)        { this.workingMaxCapacity = v; return this; }
        public Builder workingMaxAgeMinutes(int v)      { this.workingMaxAgeMinutes = v; return this; }
        public Builder embedderFallbackDim(int v)       { this.embedderFallbackDim = v; return this; }
        public Builder perceptualTextDim(int v)         { this.perceptualTextDim = v; return this; }
        public Builder perceptualImageDim(int v)        { this.perceptualImageDim = v; return this; }
        public Builder perceptualAudioDim(int v)        { this.perceptualAudioDim = v; return this; }

        public MemoryConfig build() { return new MemoryConfig(this); }
    }
}
