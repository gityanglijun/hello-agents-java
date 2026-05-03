package com.example.agent.tool;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.example.agent.client.SerpApiHttpClient;

public class ToolExecutor {

    public Map<String,Map<String, Object>> tools ;

    public ToolExecutor(){
        this.tools = new HashMap<>();
    }

    public void registerTool(String name, String description, Method func){
        //向工具箱中注册一个新工具
        if(tools.containsKey(name)){
            System.out.println("警告：工具"+name+"已存在，将被覆盖。");
        }
        Map<String,Object> tool = new HashMap<>();
        tool.put("description",description);
        tool.put("func",func);

        tools.put(name,tool);

        System.out.println("工具"+name+"已注册。");
    }

    public Method getTool(String name){
        //根据名称获取工具的一个执行函数
        return (Method) tools.get(name).get("func");
    }

    public String getAvailableTools(){
        //获取所有可用工具的格式化描述字符串

        return this.tools.entrySet().stream()
                .map(entry -> "- " + entry.getKey() + ": " + entry.getValue().get("description"))
                .collect(Collectors.joining("\n"));
    }

    public static void main(String[] args) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        // 1. 初始化工具执行器
        ToolExecutor toolExecutor = new ToolExecutor();
        // 2. 注册我们的实战搜索工具
        String searchDescription = "一个网页搜索引擎。当你需要回答关于时事、事实以及在你的知识库中找不到的信息时，应使用此工具。";
        toolExecutor.registerTool("Search",searchDescription,SerpApiHttpClient.class.getMethod("search", String.class));
        //3. 打印可用的工具
        System.out.println("\n--- 可用的工具 ---");
        System.out.println(toolExecutor.getAvailableTools());

        //4. 智能体的Action调用，这次我们问一个实时性的问题
        System.out.println("\n--- 执行 Action: Search['英伟达最新的GPU型号是什么'] ---");
        String toolName = "Search";
        String toolInput = "英伟达最新的GPU型号是什么";
        Method toolFunction = toolExecutor.getTool(toolName);

        if(toolFunction != null){
            String observation = (String)toolFunction.invoke(null,toolInput);
            System.out.println("--- 观察 (Observation) ---");
            System.out.println(observation);
        }
        else {
            System.out.println("错误：未找到名为 '"+toolName+" 的工具。");
        }
    }
}
