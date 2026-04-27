import java.util.HashMap;
import java.util.Map;

public class MyReflectionAgentTest {

    public static void main(String[] args) {
        MyLLM llm = new MyLLM.Builder().build();

        // ==================== 测试1: 默认通用提示词 ====================
        System.out.println("=== 测试1: 默认通用提示词 ===");
        MyReflectionAgent agent = new MyReflectionAgent("我的反思助手", llm);
        String result = agent.run("写一篇关于人工智能发展历程的简短文章，200字左右");
        System.out.println("反思结果: " + result + "\n");

        // ==================== 测试2: 自定义提示词 ====================
        System.out.println("=== 测试2: 自定义代码生成提示词 ===");
        Map<String, String> codePrompts = new HashMap<>();
        codePrompts.put("initial", "你是Python专家，请编写函数:{task}");
        codePrompts.put("reflect", "请审查代码的算法效率:\n任务:{task}\n代码:{content}");
        codePrompts.put("refine", "请根据反馈优化代码:\n任务:{task}\n反馈:{feedback}");

        MyReflectionAgent codeAgent = new MyReflectionAgent(
                "我的代码生成助手",
                llm,
                null,
                null,
                codePrompts
        );
        String code = codeAgent.run("实现一个计算斐波那契数列的函数");
        System.out.println("代码生成结果: " + code + "\n");

        // ==================== 测试3: 查看对话历史 ====================
        System.out.println("=== 测试3: 对话历史 ===");
        System.out.println("反思助手历史: " + agent.getHistory().size() + " 条消息");
        System.out.println("代码助手历史: " + codeAgent.getHistory().size() + " 条消息");
    }
}
