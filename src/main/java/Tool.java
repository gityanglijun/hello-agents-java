import java.util.Map;

public abstract class Tool {

    protected final String name;
    protected final String description;

    public Tool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String name() { return name; }
    public String description() { return description; }

    /** 执行工具 */
    public abstract String run(Map<String, Object> parameters);

    /** 获取工具参数定义 */
    public abstract java.util.List<ToolParameter> getParameters();
}
