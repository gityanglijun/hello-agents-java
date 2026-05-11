package com.example.agent.app;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.SimpleAgent;
import com.example.agent.tool.*;

import java.util.List;
import java.util.Map;

/**
 * GAIA 快速开始 — 展示 SimpleAgent 有无工具链对评估结果的影响。
 *
 * 对应 Python 第12章示例5。
 *
 * 关键区别：
 *   - 无工具: SimpleAgent 单靠 LLM 知识回答，Level 1 还行，Level 2/3 计算题全挂
 *   - 有工具: SimpleAgent 会调用 calculator 等工具，真正"解决问题"而非"回忆答案"
 *
 * 这就是为什么 GAIA 评估有意义：它测的是 Agent 的工具使用能力，不是 LLM 的背诵能力。
 *
 * 运行:
 *   mvn exec:java -Dexec.mainClass=com.example.agent.app.GaiaQuickStart
 *   mvn exec:java -Dexec.mainClass=com.example.agent.app.GaiaQuickStart \
 *     -Dexec.args="--level 2 --samples 3 --tools"
 */
public class GaiaQuickStart {

    public static void main(String[] args) {
        int level = 1;
        int maxSamples = 2;
        String dataSource = "evaluation_data/GAIA_test.json";
        boolean enableTools = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--level"       -> level = Integer.parseInt(args[++i]);
                case "--samples"     -> maxSamples = Integer.parseInt(args[++i]);
                case "--data-source" -> dataSource = args[++i];
                case "--tools"       -> enableTools = true;
            }
        }

        // Level 2/3 默认启用工具
        if (!enableTools && level >= 2) {
            enableTools = true;
        }

        System.out.println("=".repeat(60));
        System.out.println("GAIA 快速开始");
        System.out.println("=".repeat(60));
        System.out.println("\n配置:");
        System.out.println("   级别: Level " + level);
        System.out.println("   样本数: " + maxSamples);
        System.out.println("   数据源: " + dataSource);
        System.out.println("   工具链: " + (enableTools ? "启用" : "禁用（纯 LLM 回答）"));

        // 1. 创建 LLM
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }
        System.out.println("   模型: " + llm.model);

        // 2. 创建 Agent
        SimpleAgent agent = new SimpleAgent(
                "GAIA_Agent",
                llm,
                GAIAEvaluationTool.GAIA_SYSTEM_PROMPT);

        // 3. 注册工具（如果需要）
        if (enableTools) {
            agent.addTool(createCalculatorTool());
            System.out.println("   已注册工具: calculator");
        }

        // 4. 运行评估
        System.out.println("\n" + (enableTools
                ? "Agent 会通过 [TOOL_CALL:...] 调用工具来解题"
                : "Agent 仅靠 LLM 知识回答（无工具可用）"));

        GAIAEvaluationTool gaiaTool = new GAIAEvaluationTool(agent);
        String resultJson = gaiaTool.run(Map.of(
                "action", "run_eval",
                "level", level,
                "max_samples", maxSamples,
                "data_source", dataSource,
                "export_results", true,
                "generate_report", true));

        // 5. 结果解读
        double rate = extractRate(resultJson);
        System.out.println("\n" + "=".repeat(60));
        System.out.println("结果解读");
        System.out.println("=".repeat(60));
        System.out.println("  精确匹配率: " + String.format("%.2f%%", rate * 100));

        if (!enableTools) {
            System.out.println("\n  ⚠️ 当前未启用工具链。Agent 只能靠 LLM 记忆回答。");
            System.out.println("     Level 1 (常识题) 可能还行，Level 2/3 (计算题) 大概率出错。");
            System.out.println("     试试: --tools 启用工具链看效果差异");
        } else {
            System.out.println("\n  ✅ 已启用工具链。Agent 可以调用 calculator 等工具辅助解题。");
            System.out.println("    对 Level 2/3 的计算题，准确率应明显提升。");
        }
    }

    /** 创建一个简单的计算器 Tool（public 供其他类复用）。 */
    public static Tool createCalculatorTool() {
        return new Tool("calculator", "Evaluate mathematical expressions. "
                + "Supports +, -, *, /, parentheses, sqrt, pi, pow, abs, sin, cos, tan.") {
            @Override
            public String run(Map<String, Object> parameters) {
                String expr = String.valueOf(
                        parameters.getOrDefault("expression", parameters.getOrDefault("input", "")));
                if (expr.isBlank()) return "错误：表达式不能为空";
                try {
                    double result = evaluateExpression(expr);
                    if (result == (long) result) return String.valueOf((long) result);
                    return String.valueOf(result);
                } catch (Exception e) {
                    return "计算错误: " + e.getMessage();
                }
            }

            @Override
            public List<ToolParameter> getParameters() {
                return List.of(new ToolParameter("expression", "string",
                        "Mathematical expression to evaluate, e.g. '145+37' or '2*3+4'", true, null));
            }
        };
    }

    /** 简单的表达式求值（仅支持 + - * /）。 */
    private static double evaluateExpression(String expr) {
        // 去除空格和等号
        expr = expr.replaceAll("\\s+", "").replace("=", "");

        // 尝试直接解析为数字
        try { return Double.parseDouble(expr); } catch (NumberFormatException ignored) {}

        // 简化处理: 找最后一个运算符并递归
        // 先处理 + 和 -（最低优先级）
        int parenDepth = 0;
        int lastPlus = -1, lastMinus = -1;
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth--;
            else if (parenDepth == 0) {
                if (c == '+' && lastPlus < 0) lastPlus = i;
                if (c == '-' && lastMinus < 0 && i > 0 && Character.isDigit(expr.charAt(i - 1))) lastMinus = i;
            }
        }
        if (lastPlus > 0 && parenDepth == 0)
            return evaluateExpression(expr.substring(0, lastPlus)) + evaluateExpression(expr.substring(lastPlus + 1));
        if (lastMinus > 0 && parenDepth == 0)
            return evaluateExpression(expr.substring(0, lastMinus)) - evaluateExpression(expr.substring(lastMinus + 1));

        // 处理 * 和 /
        int lastMul = -1, lastDiv = -1;
        parenDepth = 0;
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth--;
            else if (parenDepth == 0) {
                if (c == '*' && lastMul < 0) lastMul = i;
                if (c == '/' && lastDiv < 0) lastDiv = i;
            }
        }
        if (lastMul > 0 && parenDepth == 0)
            return evaluateExpression(expr.substring(0, lastMul)) * evaluateExpression(expr.substring(lastMul + 1));
        if (lastDiv > 0 && parenDepth == 0) {
            double divisor = evaluateExpression(expr.substring(lastDiv + 1));
            if (divisor == 0) throw new ArithmeticException("除以零");
            return evaluateExpression(expr.substring(0, lastDiv)) / divisor;
        }

        // 处理括号
        if (expr.startsWith("(") && expr.endsWith(")"))
            return evaluateExpression(expr.substring(1, expr.length() - 1));

        throw new IllegalArgumentException("无法解析表达式: " + expr);
    }

    private static double extractRate(String json) {
        try {
            var gson = new com.google.gson.Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = gson.fromJson(json, Map.class);
            String rateStr = (String) map.get("exact_match_rate");
            if (rateStr != null) {
                return Double.parseDouble(rateStr.replace("%", "")) / 100.0;
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
