import java.lang.reflect.Method;

public class MySimpleAgentTest {

    public static void main(String[] args) throws Exception {
        // 创建 LLM 实例（自动从 .env 读取配置）
        MyLLM llm = new MyLLM.Builder().build();

        // ==================== 测试1: 基础对话 ====================
        System.out.println("=== 测试1:基础对话 ===");
        MySimpleAgent basicAgent = new MySimpleAgent(
                "基础助手",
                llm,
                "你是一个友好的AI助手，请用简洁明了的方式回答问题。"
        );
        String response1 = basicAgent.run("你好，请介绍一下自己");
        System.out.println("基础对话响应: " + response1 + "\n");

        // ==================== 测试2: 工具增强对话 ====================
        System.out.println("=== 测试2:工具增强对话 ===");
        ToolRegistry registry = new ToolRegistry();

        Method calcMethod = CalculatorTool.class.getMethod("calculate", String.class);
        registry.register("calculator", "计算数学表达式，例如: 15 * 8 + 32", calcMethod);

        MySimpleAgent enhancedAgent = new MySimpleAgent(
                "增强助手",
                llm,
                "你是一个智能助手，可以使用工具来帮助用户。",
                null,
                registry,
                true
        );
        String response2 = enhancedAgent.run("请帮我计算 15 * 8 + 32");
        System.out.println("工具增强响应: " + response2 + "\n");

        // ==================== 测试3: 流式响应 ====================
        System.out.println("=== 测试3:流式响应 ===");
        System.out.print("流式响应: ");
        basicAgent.streamRun("请解释什么是人工智能").forEach(chunk -> {});
        System.out.println();

        // ==================== 测试4: 动态工具管理 ====================
        System.out.println("=== 测试4:动态工具管理 ===");
        System.out.println("添加工具前是否有工具: " + basicAgent.hasTools());
        System.out.println("可用工具: " + basicAgent.listTools());

        // 查看对话历史
        System.out.println("\n对话历史: " + basicAgent.getHistory().size() + " 条消息");
    }
}
