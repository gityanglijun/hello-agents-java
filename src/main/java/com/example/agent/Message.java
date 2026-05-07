package com.example.agent;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Message {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";

    private final String content;
    private final String role;
    private final LocalDateTime timestamp;
    private final Map<String, Object> metadata;
    private final String toolCallId;
    private final String name;
    private final List<Map<String, Object>> toolCalls;

    public Message(String content, String role) {
        this(content, role, LocalDateTime.now(), new HashMap<>(), null, null, null);
    }

    public Message(String content, String role, LocalDateTime timestamp, Map<String, Object> metadata) {
        this(content, role, timestamp, metadata, null, null, null);
    }

    /** Tool result message constructor */
    public Message(String content, String role, String toolCallId, String name) {
        this(content, role, LocalDateTime.now(), new HashMap<>(), toolCallId, name, null);
    }

    /** Assistant message with tool_calls constructor */
    public Message(String content, String role, List<Map<String, Object>> toolCalls) {
        this(content, role, LocalDateTime.now(), new HashMap<>(), null, null, toolCalls);
    }

    private Message(String content, String role, LocalDateTime timestamp, Map<String, Object> metadata,
                    String toolCallId, String name, List<Map<String, Object>> toolCalls) {
        if (!isValidRole(role)) {
            throw new IllegalArgumentException("Invalid role: " + role +
                    ". Must be one of: user, assistant, system, tool");
        }
        this.content = content;
        this.role = role;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.toolCallId = toolCallId;
        this.name = name;
        this.toolCalls = toolCalls;
    }

    public String content() { return content; }
    public String role() { return role; }
    public LocalDateTime timestamp() { return timestamp; }
    public Map<String, Object> metadata() { return metadata; }
    public String toolCallId() { return toolCallId; }
    public String name() { return name; }
    public List<Map<String, Object>> toolCalls() { return toolCalls; }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** Simple role+content dict for text-only APIs (backward compatible). */
    public Map<String, String> toSimpleDict() {
        Map<String, String> dict = new HashMap<>();
        dict.put("role", role);
        dict.put("content", content != null ? content : "");
        return dict;
    }

    /** Full dict with tool_call_id, name, tool_calls for OpenAI-compatible APIs. */
    public Map<String, Object> toDict() {
        Map<String, Object> dict = new HashMap<>();
        dict.put("role", role);
        if (content != null) dict.put("content", content);
        if (toolCallId != null) dict.put("tool_call_id", toolCallId);
        if (name != null) dict.put("name", name);
        if (toolCalls != null) dict.put("tool_calls", toolCalls);
        return dict;
    }

    @Override
    public String toString() {
        if (ROLE_TOOL.equals(role)) {
            return "[" + role + ":" + name + "] " + (content != null ? content.substring(0, Math.min(200, content.length())) : "");
        }
        if (hasToolCalls()) {
            return "[" + role + "] tool_calls: " + toolCalls.size();
        }
        return "[" + role + "] " + (content != null ? content.substring(0, Math.min(200, content.length())) : "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Message message)) return false;
        return Objects.equals(content, message.content)
                && Objects.equals(role, message.role)
                && Objects.equals(timestamp, message.timestamp)
                && Objects.equals(toolCallId, message.toolCallId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, role, timestamp, toolCallId);
    }

    private static boolean isValidRole(String role) {
        return ROLE_USER.equals(role) || ROLE_ASSISTANT.equals(role)
                || ROLE_SYSTEM.equals(role) || ROLE_TOOL.equals(role);
    }
}
