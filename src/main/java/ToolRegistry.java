import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    public static class ToolEntry {
        public final Method method;
        public final String description;

        public ToolEntry(Method method, String description) {
            this.method = method;
            this.description = description;
        }
    }

    private final Map<String, ToolEntry> tools = new HashMap<>();

    public void register(String name, String description, Method method) {
        tools.put(name, new ToolEntry(method, description));
    }

    public ToolEntry get(String name) {
        return tools.get(name);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public void unregister(String name) {
        tools.remove(name);
    }

    public List<String> listTools() {
        return new ArrayList<>(tools.keySet());
    }

    public Map<String, ToolEntry> all() {
        return new HashMap<>(tools);
    }

    public String describeTools() {
        if (tools.isEmpty()) return "暂无可用工具";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ToolEntry> entry : tools.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ")
                    .append(entry.getValue().description).append("\n");
        }
        return sb.toString();
    }

    public String executeTool(String name, String parameters) throws Exception {
        ToolEntry entry = tools.get(name);
        if (entry == null) {
            return "❌ 错误:未找到工具 '" + name + "'";
        }
        Object result = entry.method.invoke(null, parameters);
        return result != null ? result.toString() : "无返回值";
    }
}
