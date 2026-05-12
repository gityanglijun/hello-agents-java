package com.example.agent.tool;
import java.util.List;
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

    /**
     * 是否为可展开工具（如 MCPTool 可展开为多个独立子工具）。
     * 子类重写此方法返回 true 来启用自动展开功能。
     */
    public boolean expandable() { return false; }

    /**
     * 展开为独立子工具列表。仅当 {@link #expandable()} 返回 true 时有意义。
     * 默认返回空列表，子类（如 MCPTool）应重写。
     */
    public List<? extends Tool> getExpandedTools() { return List.of(); }
}
