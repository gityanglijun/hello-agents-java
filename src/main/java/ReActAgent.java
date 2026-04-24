import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReActAgent {
    public static String REACT_PROMPT_TEMPLATE = """
    请注意，你是一个有能力调用外部工具的智能助手。

    可用工具如下：
    %s

    请严格按照以下格式进行回应：

    Thought: 你的思考过程，用于分析问题、拆解任务和规划下一步行动。
    Action: 你决定采取的行动，必须是以下格式之一：
    - `{tool_name}[{tool_input}]`：调用一个可用工具。
    - `Finish[最终答案]`：当你认为已经获得最终答案时。
    - 当你收集到足够的信息，能够回答用户的最终问题时，你必须在`Action:`字段后使用 `Finish[最终答案]` 来输出最终答案。


    现在，请开始解决以下问题：
    Question: %s
    History: %s
    """;

    public HelloAgentsLLM llmClient;
    public ToolExecutor toolExecutor;
    public Integer maxSteps = 5;
    public StringBuffer history;

    public ReActAgent(HelloAgentsLLM llmClient,ToolExecutor toolExecutor,Integer maxSteps){
        this.llmClient  = llmClient;
        this.toolExecutor = toolExecutor;
        this.maxSteps = maxSteps;
        history = new StringBuffer();
    }


    public void run(String question) throws InvocationTargetException, IllegalAccessException {
        history = new StringBuffer();
        Integer currentStep = 0;

        while (currentStep < maxSteps){
            currentStep ++;
            System.out.println("\n--- 第 {"+currentStep+"} 步 ---");

            String toolsDesc = toolExecutor.getAvailableTools();
            String historyStr = history.toString();
            String prompt = String.format(REACT_PROMPT_TEMPLATE,toolsDesc,question,historyStr);

            List<Map<String,String>> messages = new ArrayList<>();
            Map<String,String> message = new HashMap<>();
            message.put("role","user");
            message.put("content",prompt);
            messages.add(message);

            String responseText = llmClient.think(messages);
            if(responseText == null || responseText.isEmpty()){
                System.out.println("错误：LLM未能返回有效响应。");
                break;
            }

            String[] parseOutputs = parseOutput(responseText);
            String thought = parseOutputs[0];
            String action = parseOutputs[1];

            if(thought != null){
                System.out.println("🤔 思考: "+thought);
            }
            if(action == null){
                System.out.println("警告：未能解析出有效的Action，流程终止。");
                break;
            }

            if(action.startsWith("Finish")){
                //如果是Finish指令，提取最终答案并结束
                String final_answer = parseActionInput(action)[0];
                System.out.println("🎉 最终答案: "+final_answer);
            }

            String[] parseActions = parseAction(action);
            String toolName = parseActions[0];
            String toolInput = parseActions[1];

            if(toolName == null || toolInput == null){
                history.append("Observation: 无效的Action格式，请检查。");
                continue;
            }

            System.out.println("🎬 行动: "+toolName+"["+toolInput+"]");
            Method toolFunction = toolExecutor.getTool(toolName);
            String observation ;
            if(toolFunction != null){
                observation  = toolFunction.invoke(null,toolInput).toString();
            }
            else {
                observation = String.format("错误：未找到名为 '%s' 的工具。", toolName);
            }
            System.out.println("👀 观察: "+observation);
            history.append("Action: " + action);
            history.append("Observation: " + observation);
        }
        System.out.println("已达到最大步数，流程终止。");
    }

    public String[] parseOutput(String text){
        // 1. 匹配 Thought: 后到下一个 Action: 或文本末尾的内容
        // 使用 Pattern.DOTALL 使 . 能匹配换行符
        Pattern thoughtPattern = Pattern.compile("Thought:\\s*(.*?)(?=\nAction:|$)", Pattern.DOTALL);
        Matcher thoughtMatcher = thoughtPattern.matcher(text);
        String thought = null;
        if (thoughtMatcher.find()) {
            thought = thoughtMatcher.group(1).trim();
        }

        // 2. 匹配 Action: 后到文本末尾的内容
        Pattern actionPattern = Pattern.compile("Action:\\s*(.*?)$", Pattern.DOTALL);
        Matcher actionMatcher = actionPattern.matcher(text);
        String action = null;
        if (actionMatcher.find()) {
            action = actionMatcher.group(1).trim();
        }

        return new String[]{thought, action};
    }

    public String[] parseAction(String actionText) {
        // 注意：正则中的 \w 和 \[ 在 Java 字符串中需要转义
        Pattern pattern = Pattern.compile("(\\w+)\\[(.*)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(actionText);

        if (matcher.matches()) {  // matches() 要求整个字符串匹配，等价于 Python 的 re.match
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return new String[]{null, null};
    }

    public String[] parseActionInput(String actionText) {
        // 注意：正则中的 \w 和 \[ 在 Java 字符串中需要转义
        Pattern pattern = Pattern.compile("\\w+\\[(.*)\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(actionText);

        if (matcher.matches()) {  // matches() 要求整个字符串匹配，等价于 Python 的 re.match
            return new String[]{matcher.group(1)};
        }
        return new String[]{null, null};
    }

    public static void main(String[] args) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        HelloAgentsLLM llm = new HelloAgentsLLM();
        ToolExecutor toolExecutor = new ToolExecutor();
        String searchDesc = "一个网页搜索引擎。当你需要回答关于时事、事实以及在你的知识库中找不到的信息时，应使用此工具。";
        toolExecutor.registerTool("Search",searchDesc,SerpApiHttpClient.class.getMethod("search",String.class));

        ReActAgent agent = new ReActAgent(llm,toolExecutor,5);
        String question = "华为最新的手机是哪一款？它的主要卖点是什么？";
        agent.run(question);
    }
}
