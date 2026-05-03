package com.example.agent.tool;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyCalculatorTool {

    public static String myCalculate(String expression) {
        if (expression == null || expression.isBlank()) {
            return "计算表达式不能为空";
        }

        try {
            expression = expression.trim();

            // 1. 递归计算所有 sqrt 函数
            expression = evalSqrt(expression);

            // 2. 替换 pi 常量
            expression = expression.replaceAll("(?<![a-zA-Z])pi(?![a-zA-Z])", String.valueOf(Math.PI));

            // 3. 计算四则运算
            double result = evalArithmetic(expression);

            if (result == (long) result) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);

        } catch (Exception e) {
            return "计算失败，请检查表达式格式: " + expression;
        }
    }

    /** 递归替换 sqrt(...) 为计算结果 */
    private static String evalSqrt(String expr) {
        Pattern sqrtPattern = Pattern.compile("sqrt\\(([^()]+)\\)");
        String prev;
        do {
            prev = expr;
            Matcher m = sqrtPattern.matcher(expr);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                double inner = evalArithmetic(m.group(1).trim());
                double sqrtVal = Math.sqrt(inner);
                m.appendReplacement(sb, String.valueOf(sqrtVal));
            }
            m.appendTail(sb);
            expr = sb.toString();
        } while (!expr.equals(prev));
        return expr;
    }

    /** 解析四则运算表达式（从左到右，不支持优先级） */
    private static double  evalArithmetic(String expr) {
        expr = expr.trim();
        if (expr.isEmpty()) return 0;

        // 先按加减分割
        List<String> terms = splitByDelimiter(expr, "+", "-", true);
        double result = 0;
        boolean first = true;

        for (String term : terms) {
            if (term.equals("+") || term.equals("-")) continue;
            double val = evalTerm(term);

            // 确定符号
            int idx = terms.indexOf(term);
            if (idx > 0) {
                String op = terms.get(idx - 1);
                if ("-".equals(op)) val = -val;
            }
            result += val;
        }

        return result;
    }

    /** 计算乘除项 */
    private static double evalTerm(String expr) {
        expr = expr.trim();
        List<String> factors = splitByDelimiter(expr, "*", "/", true);
        double result = 1;
        boolean first = true;

        for (String factor : factors) {
            if (factor.equals("*") || factor.equals("/")) continue;
            double val = Double.parseDouble(factor.trim());

            int idx = factors.indexOf(factor);
            if (idx == 0) {
                result = val;
            } else {
                String op = factors.get(idx - 1);
                if ("/".equals(op)) {
                    if (val == 0) throw new ArithmeticException("除数不能为0");
                    result /= val;
                } else {
                    result *= val;
                }
            }
        }

        return result;
    }

    /** 按运算符拆分，保留分隔符 */
    private static List<String> splitByDelimiter(String expr, String op1, String op2, boolean keepDelim) {
        List<String> parts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean escape = false;

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            String s = String.valueOf(c);

            if (s.equals(op1) || s.equals(op2)) {
                if (buf.length() > 0) {
                    parts.add(buf.toString().trim());
                    buf = new StringBuilder();
                }
                if (keepDelim) parts.add(s);
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            parts.add(buf.toString().trim());
        }

        return parts;
    }

    public static ToolRegistry createCalculatorRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerFunction(
                "my_calculator",
                "简单的数学计算工具，支持基本运算(+,-,*,/)和sqrt函数",
                MyCalculatorTool::myCalculate
        );
        return registry;
    }

    public static void main(String[] args) {
        String[] tests = {"2 + 3", "10 - 4", "5 * 6", "15 / 3", "sqrt(16)", "sqrt(16) + 2 * 3"};
        for (String t : tests) {
            System.out.println(t + " = " + myCalculate(t));
        }
    }
}
