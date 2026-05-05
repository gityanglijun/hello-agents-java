package com.example.agent.rag;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 候选信息包 — 对应 Python ContextPacket dataclass。
 * 封装一条待进入上下文的片段及其元信息。
 */
public class ContextPacket {

    private final String content;
    private final LocalDateTime timestamp;
    private final int tokenCount;
    private final double relevanceScore;
    private final Map<String, Object> metadata;

    public ContextPacket(String content, LocalDateTime timestamp, int tokenCount,
                         double relevanceScore, Map<String, Object> metadata) {
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
        this.tokenCount = Math.max(0, tokenCount);
        this.relevanceScore = clampScore(relevanceScore);
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
    }

    public ContextPacket(String content, int tokenCount, double relevanceScore) {
        this(content, LocalDateTime.now(), tokenCount, relevanceScore, null);
    }

    // ==================== 访问器 ====================

    public String content()           { return content; }
    public LocalDateTime timestamp()  { return timestamp; }
    public int tokenCount()           { return tokenCount; }
    public double relevanceScore()    { return relevanceScore; }
    public Map<String, Object> metadata() { return new LinkedHashMap<>(metadata); }

    // ==================== 辅助 ====================

    private static double clampScore(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    @Override
    public String toString() {
        return "ContextPacket{content='" + (content.length() > 60 ? content.substring(0, 60) + "..." : content)
                + "', relevance=" + String.format("%.3f", relevanceScore)
                + ", tokens=" + tokenCount + "}";
    }
}
