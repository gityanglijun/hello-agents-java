package com.example.agent.tool;
public class ToolParameter {

    private final String name;
    private final String type;
    private final String description;
    private final boolean required;
    private final Object defaultValue;

    public ToolParameter(String name, String type, String description,
                         boolean required, Object defaultValue) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
        this.defaultValue = defaultValue;
    }

    public ToolParameter(String name, String type, String description) {
        this(name, type, description, true, null);
    }

    public String name() { return name; }
    public String type() { return type; }
    public String description() { return description; }
    public boolean required() { return required; }
    public Object defaultValue() { return defaultValue; }
}
