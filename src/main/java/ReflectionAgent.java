import java.lang.management.MemoryMXBean;
import java.util.*;

import static java.lang.System.exit;

public class ReflectionAgent {


    // 1. 初始执行提示词
    public static String INITIAL_PROMPT_TEMPLATE = """
    你是一位资深的Java程序员。请根据以下要求，编写一个Java函数。
    你的代码必须包含完整的函数签名、文档字符串，并遵循PEP 8编码规范。
    
    要求: %s
    
    请直接输出代码，不要包含任何额外的解释。
    """;

    // 2. 反思提示词
    public static String  REFLECT_PROMPT_TEMPLATE = """
    你是一位极其严格的代码评审专家和资深算法工程师，对代码的性能有极致的要求。
    你的任务是审查以下Java代码，并专注于找出其在**算法效率**上的主要瓶颈。
    
    # 原始任务:
    %s
    
    # 待审查的代码:
    ```java
    %s
    ```
    
    请分析该代码的时间复杂度，并思考是否存在一种**算法上更优**的解决方案来显著提升性能。
    如果存在，请清晰地指出当前算法的不足，并提出具体的、可行的改进算法建议（例如，使用筛法替代试除法）。
    如果代码在算法层面已经达到最优，才能回答“无需改进”。
    
    请直接输出你的反馈，不要包含任何额外的解释。
    """;

    // 3. 优化提示词
    public static String REFINE_PROMPT_TEMPLATE = """
    你是一位资深的Java程序员。你正在根据一位代码评审专家的反馈来优化你的代码。
    
    # 原始任务:
    %s
    # 你上一轮尝试的代码:
    %s
    
    # 评审员的反馈:
    %s
    
    请根据评审员的反馈，生成一个优化后的新版本代码。
    你的代码必须包含完整的函数签名、文档字符串，并遵循PEP 8编码规范。
    请直接输出优化后的代码，不要包含任何额外的解释。
    """;

    private final HelloAgentsLLM llm_client;
    private final Memory memory;
    private final Integer max_iterations;

    public ReflectionAgent(HelloAgentsLLM llm_client, Integer max_iterations){
        this.llm_client = llm_client;
        this.memory = new Memory();
        this.max_iterations = max_iterations != null ? max_iterations : 3;
    }

    public void run(String task){
        System.out.println("\n--- 开始处理任务 ---\n任务: "+task+"");
        //--- 1. 初始执行 ---
        System.out.println("\n--- 正在进行初始尝试 ---");
        String initialPrompt = INITIAL_PROMPT_TEMPLATE.formatted(task);
        String initialCode = getLlmResponse(initialPrompt);
        memory.addRecord("execution",initialCode);

        //--- 2. 迭代循环：反思与优化 ---
        for(int i = 0;i<max_iterations;i++){
            System.out.print("\n--- 第 {"+(i+1)+"}/{"+max_iterations+"} 轮迭代 ---");
            // a. 反思
            System.out.print("\n-> 正在进行反思...");
            String lastCode = memory.getLastExecution();
            String reflectPrompt = REFLECT_PROMPT_TEMPLATE.formatted(task,lastCode);
            String feedback = getLlmResponse(reflectPrompt);
            memory.addRecord("reflection",feedback);

            // b. 检查是否需要停止
            if(feedback.contains("无需改进") || feedback.toLowerCase().contains("no need for improvement")){
                System.out.print("\n✅ 反思认为代码已无需改进，任务完成。");
                break;
            }

            // c. 优化
            System.out.print("\n-> 正在进行优化...");
            String refinePrompt = REFINE_PROMPT_TEMPLATE.formatted(task,lastCode,feedback);
            String refineCode = getLlmResponse(refinePrompt);
            memory.addRecord("execution",refineCode);
        }
        String finalCode = memory.getLastExecution();
        System.out.print("\n--- 任务完成 ---\n最终生成的代码:\n{"+finalCode+"}");
    }

    public String getLlmResponse(String prompt){
        //一个辅助方法，用于调用LLM并获取完整的流式响应。
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String,String> message = new HashMap<>();
        message.put("role","user");
        message.put("content",prompt);
        messages.add(message);

        //确保能处理生成器可能返回None的情况
        String responseText = llm_client.think(messages);
        responseText = responseText == null ? "" : responseText;

        return responseText;
    }

    public static void main(String[] args){
        // 1. 初始化LLM客户端 (请确保你的 .env 和 llm_client.py 文件配置正确)
        HelloAgentsLLM llm_client ;
        try {
            llm_client = new HelloAgentsLLM();
        }
        catch (Exception e){
            System.out.print("初始化LLM客户端时出错: {"+e+"}");
            return ;
        }

        // 2. 初始化 Reflection 智能体，设置最多迭代2轮
        ReflectionAgent agent = new ReflectionAgent(llm_client, 2);

        // 3. 定义任务并运行智能体
        String task = "编写一个Java函数，找出1到n之间所有的素数 (prime numbers)。";
        agent.run(task);
    }
}
