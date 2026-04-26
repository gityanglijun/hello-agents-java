import java.time.LocalDateTime;
import java.util.HashMap;
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

    public Message(String content, String role) {
        this(content, role, LocalDateTime.now(), new HashMap<>());
    }

    public Message(String content, String role, LocalDateTime timestamp, Map<String, Object> metadata) {
        if (content == null) throw new IllegalArgumentException("content must not be null");
        if (!isValidRole(role)) {
            throw new IllegalArgumentException("Invalid role: " + role +
                    ". Must be one of: user, assistant, system, tool");
        }
        this.content = content;
        this.role = role;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    public String content() { return content; }
    public String role() { return role; }
    public LocalDateTime timestamp() { return timestamp; }
    public Map<String, Object> metadata() { return metadata; }

    public Map<String, String> toDict() {
        Map<String, String> dict = new HashMap<>();
        dict.put("role", role);
        dict.put("content", content);
        return dict;
    }

    @Override
    public String toString() {
        return "[" + role + "] " + content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Message message)) return false;
        return Objects.equals(content, message.content)
                && Objects.equals(role, message.role)
                && Objects.equals(timestamp, message.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, role, timestamp);
    }

    private static boolean isValidRole(String role) {
        return ROLE_USER.equals(role) || ROLE_ASSISTANT.equals(role)
                || ROLE_SYSTEM.equals(role) || ROLE_TOOL.equals(role);
    }
}
