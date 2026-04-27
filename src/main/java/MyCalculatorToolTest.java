import java.util.List;
import java.util.Map;

public class MyCalculatorToolTest {

    public static void main(String[] args) throws Exception {
        testCalculatorTool();
        testWithSimpleAgent();
    }

    static void testCalculatorTool() throws Exception {
        System.out.println("🧪 测试自定义计算器工具\n");

        ToolRegistry registry = MyCalculatorTool.createCalculatorRegistry();

        String[] testCases = {
                "2 + 3",
                "10 - 4",
                "5 * 6",
                "15 / 3",
                "sqrt(16)",
        };

        for (int i = 0; i < testCases.length; i++) {
            String expr = testCases[i];
            System.out.println("测试 " + (i + 1) + ": " + expr);
            String result = registry.executeTool("my_calculator", expr);
            System.out.println("结果: " + result + "\n");
        }
    }

    static void testWithSimpleAgent() throws Exception {
        System.out.println("🤖 与SimpleAgent集成测试:");

        MyLLM llm = new MyLLM.Builder().build();
        ToolRegistry registry = MyCalculatorTool.createCalculatorRegistry();

        String userQuestion = "请帮我计算 sqrt(16) + 2 * 3";
        System.out.println("用户问题: " + userQuestion);

        // 使用工具计算
        String calcResult = registry.executeTool("my_calculator", "sqrt(16) + 2 * 3");
        System.out.println("计算结果: " + calcResult);

        // 构建最终回答
        List<Map<String, String>> finalMessages = List.of(
                Map.of("role", "user",
                        "content", "计算结果是 " + calcResult + "，请用自然语言回答用户的问题:" + userQuestion)
        );

        System.out.println("\n🎯 SimpleAgent的回答:");
        String response = llm.think(finalMessages);
        System.out.println(response);
        System.out.println();
    }
}
