import java.util.ArrayList;
import java.util.List;

public class CalculatorTool {
    public static String calculate(String expression) {
        try {
            expression = expression.trim();
            // 处理加减乘除，从左到右简化计算
            String[] parts = expression.split("(?=[+\\-*/])|(?<=[+\\-*/])");
            if (parts.length < 3) {
                return "表达式格式错误，请使用 数字 运算符 数字 的格式，例如: 15 * 8 + 32";
            }

            double result = Double.parseDouble(parts[0].trim());
            for (int i = 1; i < parts.length; i += 2) {
                String op = parts[i].trim();
                double num = Double.parseDouble(parts[i + 1].trim());
                switch (op) {
                    case "+" -> result += num;
                    case "-" -> result -= num;
                    case "*" -> result *= num;
                    case "/" -> {
                        if (num == 0) return "错误: 除数不能为0";
                        result /= num;
                    }
                    default -> {
                        return "不支持的运算符: " + op;
                    }
                }
            }

            // 整数则返回整数形式
            if (result == (long) result) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);

        } catch (NumberFormatException e) {
            return "计算错误: 无法解析表达式中的数字 \"" + expression + "\"";
        } catch (Exception e) {
            return "计算错误: " + e.getMessage();
        }
    }

    public static void main(String[] args) {
        System.out.println(calculate("15 * 8 + 32"));   // 152
        System.out.println(calculate("100 / 4"));       // 25
        System.out.println(calculate("10 + 20 * 2"));   // 60
    }
}
