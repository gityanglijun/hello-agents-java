package com.example.agent.tool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToolChain {

    private final String name;
    private final String description;
    private final List<Step> steps;

    public static class Step {
        public final String toolName;
        public final String inputTemplate;
        public final String outputKey;

        public Step(String toolName, String inputTemplate, String outputKey) {
            this.toolName = toolName;
            this.inputTemplate = inputTemplate;
            this.outputKey = outputKey;
        }
    }

    public ToolChain(String name, String description) {
        this.name = name;
        this.description = description;
        this.steps = new ArrayList<>();
    }

    public String name() { return name; }
    public String description() { return description; }

    public void addStep(String toolName, String inputTemplate, String outputKey) {
        steps.add(new Step(toolName, inputTemplate,
                outputKey != null ? outputKey : "step_" + (steps.size()) + "_result"));
    }

    public void addStep(String toolName, String inputTemplate) {
        addStep(toolName, inputTemplate, null);
    }

    public String execute(ToolRegistry registry, String initialInput, Map<String, Object> context) throws Exception {
        if (context == null) {
            context = new HashMap<>();
        }
        context.put("input", initialInput);

        System.out.println("开始执行工具链: " + name);

        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);

            // 替换模板中的 {var} 变量
            String toolInput = formatTemplate(step.inputTemplate, context);
            System.out.println("  步骤 " + (i + 1) + ": 使用 " + step.toolName
                    + " 处理 '" + truncate(toolInput, 50) + "...'");

            String result = registry.executeTool(step.toolName, toolInput);
            context.put(step.outputKey, result);

            System.out.println("  ✅ 步骤 " + (i + 1) + " 完成，结果长度: " + result.length() + " 字符");
        }

        String finalResult = (String) context.get(steps.get(steps.size() - 1).outputKey);
        System.out.println("工具链 '" + name + "' 执行完成");
        return finalResult;
    }

    public String execute(ToolRegistry registry, String initialInput) throws Exception {
        return execute(registry, initialInput, null);
    }

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{([^}]+)\\}");

    private String formatTemplate(String template, Map<String, Object> context) {
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object value = context.get(key);
            if (value == null) {
                throw new IllegalArgumentException("模板变量 {" + key + "} 未找到");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    public static ToolChain createResearchChain() {
        ToolChain chain = new ToolChain("research_and_calculate", "搜索信息并进行相关计算");
        chain.addStep("search", "{input}", "search_result");
        chain.addStep("my_calculator", "根据以下信息计算相关数值:{search_result}", "calculation_result");
        return chain;
    }
}
