package com.example.agent.llm;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

import com.example.agent.LoadDotenvUtil;
import com.example.agent.Message;

public class HelloAgentsLLM {

    public String model;
    public OpenAIClient client;
    public String provider;

    /** Result from thinkWithTools() — contains text content and/or tool calls. */
    public record ThinkWithToolsResult(String content, List<ToolCall> toolCalls,
                                        String reasoningContent) {
        public ThinkWithToolsResult(String content, List<ToolCall> toolCalls) {
            this(content, toolCalls, null);
        }
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /** A single tool call from the LLM response. */
    public record ToolCall(String id, String name, Map<String, Object> arguments) {}

    // 供子类使用的受保护构造方法：直接设置 model 和 client，绕过验证
    protected HelloAgentsLLM(String model, OpenAIClient client, String provider) {
        this.model = model;
        this.client = client;
        this.provider = provider;
    }

    public HelloAgentsLLM() {
        Map<String,String> envFile = LoadDotenvUtil.loadEnvFile();
        this.model = envFile.getOrDefault("LLM_MODEL_ID", System.getenv("LLM_MODEL_ID"));
        String apiKey = envFile.getOrDefault("LLM_API_KEY", System.getenv("LLM_API_KEY"));
        String baseUrl = envFile.getOrDefault("LLM_BASE_URL", System.getenv("LLM_BASE_URL"));
        Integer timeout = Integer.parseInt(
                envFile.getOrDefault("LLM_TIMEOUT",
                System.getenv().getOrDefault("LLM_TIMEOUT", String.valueOf(60))));

        if (model == null || model.isBlank()
                || apiKey == null || apiKey.isBlank()
                || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("模型ID、API密钥和服务地址必须被提供或在.env文件中定义。");
        }
        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(timeout))
                .build();
    }
    public HelloAgentsLLM(String model,String apiKey,String baseUrl,Integer timeout){
        this.model = model != null ? model : System.getenv("LLM_MODEL_ID");
        apiKey = apiKey != null ? apiKey : System.getenv("LLM_API_KEY");
        baseUrl = baseUrl != null ? baseUrl : System.getenv("LLM_BASE_URL");
        timeout = timeout != null ? timeout : Integer.parseInt(System.getenv().getOrDefault("LLM_TIMEOUT",String.valueOf(60)));

        if (model == null || model.isBlank()
                || apiKey == null || apiKey.isBlank()
                || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("模型ID、API密钥和服务地址必须被提供或在.env文件中定义。");
        }
        client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(timeout))
                .build();
    }

    public String think(List<Map<String,String>> messages, Float temperature){
        System.out.println("正在调用" + this.model + "模型");
        try {
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(this.model)
                    .temperature(temperature != null ? temperature.doubleValue() : 0.0);

            for (Map<String,String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");
                if (role == null || content == null) continue;
                switch (role) {
                    case "system" -> paramsBuilder.addSystemMessage(content);
                    case "user" -> paramsBuilder.addUserMessage(content);
                    case "assistant" -> paramsBuilder.addAssistantMessage(content);
                }
            }

            ChatCompletionCreateParams params = paramsBuilder.build();

            try (StreamResponse<ChatCompletionChunk> streamResponse =
                     client.chat().completions().createStreaming(params)) {
                System.out.println("大语言模型响应成功:");
                StringBuilder collected = new StringBuilder();
                streamResponse.stream().forEach(chunk -> {
                    String delta = chunk.choices().get(0).delta().content().orElse("");
                    System.out.print(delta);
                    System.out.flush();
                    collected.append(delta);
                });
                System.out.println();
                return collected.toString();
            }
        } catch (Exception e) {
            System.err.println("调用LLM API时发生错误: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public String think(List<Map<String,String>> messages){
        return think(messages, (float) 0);
    }

    // ==================== Message 重载（推荐 API） ====================

    public String thinkMessages(List<Message> messages, Float temperature) {
        List<Map<String, String>> dicts = new ArrayList<>();
        for (Message msg : messages) {
            Map<String, String> simple = new HashMap<>();
            simple.put("role", msg.role());
            simple.put("content", msg.content() != null ? msg.content() : "");
            dicts.add(simple);
        }
        return think(dicts, temperature);
    }

    public String thinkMessages(List<Message> messages) {
        return thinkMessages(messages, (float) 0);
    }

    // ==================== Tool Calling (Agentic) ====================

    /**
     * 支持 OpenAI function calling 的 LLM 调用。
     *
     * @param messages  对话消息列表（可包含 tool/assistant+tool_calls 角色）
     * @param toolSchemas 工具 schema 列表（由 ToolRegistry.getAllSchemas() 生成）
     * @param temperature 温度参数
     * @return ThinkWithToolsResult 包含文本回复和/或 tool_calls
     */
    public ThinkWithToolsResult thinkWithTools(List<Message> messages,
                                                List<Map<String, Object>> toolSchemas,
                                                Double temperature) {
        System.out.println("正在调用" + this.model + "模型 (with tools)");
        try {
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(this.model)
                    .temperature(temperature != null ? temperature : 0.0);

            // Add messages — handle all role types including tool
            for (Message msg : messages) {
                String role = msg.role();
                switch (role) {
                    case "system" -> paramsBuilder.addSystemMessage(msg.content());
                    case "user" -> paramsBuilder.addUserMessage(msg.content());
                    case "assistant" -> {
                        if (msg.hasToolCalls()) {
                            paramsBuilder.addMessage(buildAssistantWithToolCalls(msg));
                        } else {
                            var b = ChatCompletionAssistantMessageParam.builder()
                                    .content(msg.content() != null ? msg.content() : "");
                            String rc = (String) msg.metadata().get("reasoning_content");
                            if (rc != null && !rc.isEmpty()) {
                                b.putAdditionalProperty("reasoning_content", JsonValue.from(rc));
                            }
                            paramsBuilder.addMessage(b.build());
                        }
                    }
                    case "tool" -> paramsBuilder.addMessage(
                            ChatCompletionToolMessageParam.builder()
                                    .content(msg.content() != null ? msg.content() : "")
                                    .toolCallId(msg.toolCallId())
                                    .build());
                }
            }

            // Add tools
            if (toolSchemas != null && !toolSchemas.isEmpty()) {
                List<ChatCompletionTool> tools = new ArrayList<>();
                for (Map<String, Object> schema : toolSchemas) {
                    tools.add(buildTool(schema));
                }
                paramsBuilder.tools(tools);
                paramsBuilder.toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO);
            }

            ChatCompletionCreateParams params = paramsBuilder.build();

            // Non-streaming call
            ChatCompletion completion = client.chat().completions().create(params);
            ChatCompletionMessage responseMsg = completion.choices().get(0).message();

            String content = responseMsg.content().orElse(null);
            String reasoningContent = extractReasoningContent(responseMsg);

            List<ToolCall> toolCalls = null;
            if (responseMsg.toolCalls().isPresent() && !responseMsg.toolCalls().get().isEmpty()) {
                toolCalls = new ArrayList<>();
                for (ChatCompletionMessageToolCall tc : responseMsg.toolCalls().get()) {
                    if (tc.isFunction()) {
                        var funcTc = tc.asFunction();
                        String name = funcTc.function().name();
                        Map<String, Object> args = parseJsonToMap(funcTc.function().arguments());
                        toolCalls.add(new ToolCall(funcTc.id(), name, args));
                    }
                }
                System.out.println("大语言模型响应成功: tool_calls: " + toolCalls.size());
            } else {
                System.out.println("大语言模型响应成功: " + (content != null ? content.length() + " 字符" : "无内容"));
            }

            return new ThinkWithToolsResult(content, toolCalls, reasoningContent);

        } catch (Exception e) {
            System.err.println("调用LLM API (with tools) 时发生错误: " + e.getMessage());
            e.printStackTrace();
            return new ThinkWithToolsResult(null, null, null);
        }
    }

    public ThinkWithToolsResult thinkWithTools(List<Message> messages,
                                                List<Map<String, Object>> toolSchemas) {
        return thinkWithTools(messages, toolSchemas, 0.0);
    }

    // ==================== Tool schema construction ====================

    @SuppressWarnings("unchecked")
    private static ChatCompletionTool buildTool(Map<String, Object> schema) {
        Map<String, Object> func = (Map<String, Object>) schema.get("function");
        FunctionDefinition.Builder funcBuilder = FunctionDefinition.builder()
                .name((String) func.get("name"));
        if (func.containsKey("description")) {
            funcBuilder.description((String) func.get("description"));
        }
        if (func.containsKey("parameters")) {
            funcBuilder.parameters(buildFunctionParameters((Map<String, Object>) func.get("parameters")));
        }
        return ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder().function(funcBuilder.build()).build());
    }

    @SuppressWarnings("unchecked")
    private static FunctionParameters buildFunctionParameters(Map<String, Object> paramsMap) {
        FunctionParameters.Builder builder = FunctionParameters.builder();
        for (var entry : paramsMap.entrySet()) {
            builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static ChatCompletionAssistantMessageParam buildAssistantWithToolCalls(Message msg) {
        List<ChatCompletionMessageToolCall> sdkToolCalls = new ArrayList<>();
        for (Map<String, Object> tcMap : msg.toolCalls()) {
            String id = (String) tcMap.get("id");
            Map<String, Object> funcMap = (Map<String, Object>) tcMap.get("function");
            String name = (String) funcMap.get("name");
            String argsJson = (String) funcMap.get("arguments");

            var functionCall = ChatCompletionMessageFunctionToolCall.Function.builder()
                    .name(name)
                    .arguments(argsJson)
                    .build();
            var sdkTc = ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                            .id(id)
                            .function(functionCall)
                            .build());
            sdkToolCalls.add(sdkTc);
        }
        var builder = ChatCompletionAssistantMessageParam.builder()
                .content(msg.content())
                .toolCalls(sdkToolCalls);
        // DeepSeek thinking mode: must pass back reasoning_content
        String rc = (String) msg.metadata().get("reasoning_content");
        if (rc != null && !rc.isEmpty()) {
            builder.putAdditionalProperty("reasoning_content", JsonValue.from(rc));
        }
        return builder.build();
    }

    /**
     * Extract reasoning_content from ChatCompletionMessage.
     * Uses reflection since reasoning_content is a DeepSeek-specific field
     * not in the standard OpenAI SDK types.
     */
    private static String extractReasoningContent(ChatCompletionMessage msg) {
        try {
            java.lang.reflect.Method m = msg.getClass().getMethod("_additionalProperties");
            @SuppressWarnings("unchecked")
            Map<String, ?> props = (Map<String, ?>) m.invoke(msg);
            Object rc = props.get("reasoning_content");
            if (rc != null) {
                String s = rc.toString();
                if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                    s = s.substring(1, s.length() - 1);
                }
                return s;
            }
        } catch (Exception ignored) {
            // SDK doesn't expose _additionalProperties, or no reasoning_content
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            // Use Gson for JSON parsing (already a dependency via NoteTool)
            return new com.google.gson.Gson().fromJson(json, Map.class);
        } catch (Exception e) {
            System.err.println("JSON 解析失败: " + e.getMessage());
            return Map.of();
        }
    }

    // ==================== Streaming (text-only, no tool support) ====================

    public java.util.stream.Stream<String> streamThinkMessages(List<Message> messages, Float temperature) {
        List<Map<String, String>> dicts = new ArrayList<>();
        for (Message msg : messages) {
            Map<String, String> simple = new HashMap<>();
            simple.put("role", msg.role());
            simple.put("content", msg.content() != null ? msg.content() : "");
            dicts.add(simple);
        }
        return streamThink(dicts, temperature);
    }

    public java.util.stream.Stream<String> streamThink(List<Map<String,String>> messages, Float temperature) {
        System.out.println("正在流式调用" + this.model + "模型");
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(this.model)
                .temperature(temperature != null ? temperature.doubleValue() : 0.0);

        for (Map<String,String> msg : messages) {
            String role = msg.get("role");
            String content = msg.get("content");
            if (role == null || content == null) continue;
            switch (role) {
                case "system" -> paramsBuilder.addSystemMessage(content);
                case "user" -> paramsBuilder.addUserMessage(content);
                case "assistant" -> paramsBuilder.addAssistantMessage(content);
            }
        }

        try {
            StreamResponse<ChatCompletionChunk> streamResponse =
                    client.chat().completions().createStreaming(paramsBuilder.build());
            return streamResponse.stream()
                    .map(chunk -> chunk.choices().get(0).delta().content().orElse(""))
                    .filter(s -> !s.isEmpty());
        } catch (Exception e) {
            System.err.println("流式调用LLM API时发生错误: " + e.getMessage());
            e.printStackTrace();
            return java.util.stream.Stream.empty();
        }
    }

    public java.util.stream.Stream<String> streamThink(List<Map<String,String>> messages) {
        return streamThink(messages, (float) 0);
    }

    // ==================== Credentials ====================

    public static String[] resolveCredentials(String provider, String apiKey, String baseUrl) {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();

        switch (provider) {
            case "openai":
                apiKey = apiKey != null ? apiKey
                        : env.get("OPENAI_API_KEY") != null ? env.get("OPENAI_API_KEY")
                        : env.get("LLM_API_KEY");
                baseUrl = baseUrl != null ? baseUrl
                        : env.get("LLM_BASE_URL") != null ? env.get("LLM_BASE_URL")
                        : "https://api.openai.com/v1";
                break;

            case "modelscope":
                apiKey = apiKey != null ? apiKey
                        : env.get("MODELSCOPE_API_KEY") != null ? env.get("MODELSCOPE_API_KEY")
                        : env.get("LLM_API_KEY");
                baseUrl = baseUrl != null ? baseUrl
                        : env.get("LLM_BASE_URL") != null ? env.get("LLM_BASE_URL")
                        : "https://api-inference.modelscope.cn/v1/";
                break;

            case "zhipu":
                apiKey = apiKey != null ? apiKey
                        : env.get("ZHIPU_API_KEY") != null ? env.get("ZHIPU_API_KEY")
                        : env.get("LLM_API_KEY");
                baseUrl = baseUrl != null ? baseUrl
                        : env.get("LLM_BASE_URL") != null ? env.get("LLM_BASE_URL")
                        : "https://open.bigmodel.cn/api/paas/v4/";
                break;

            case "ollama":
                apiKey = apiKey != null ? apiKey : "ollama";
                baseUrl = baseUrl != null ? baseUrl
                        : env.get("LLM_BASE_URL") != null ? env.get("LLM_BASE_URL")
                        : "http://localhost:11434/v1";
                break;

            case "vllm":
                apiKey = apiKey != null ? apiKey : "vllm";
                baseUrl = baseUrl != null ? baseUrl
                        : env.get("LLM_BASE_URL") != null ? env.get("LLM_BASE_URL")
                        : "http://localhost:8000/v1";
                break;

            default: // "auto"
                apiKey = apiKey != null ? apiKey : env.get("LLM_API_KEY");
                baseUrl = baseUrl != null ? baseUrl : env.get("LLM_BASE_URL");
                break;
        }

        return new String[]{apiKey, baseUrl};
    }

    public static String autoDetectProvider(String apiKey, String baseUrl) {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();

        // 1. 检查特定提供商的环境变量（最高优先级）
        if (env.get("MODELSCOPE_API_KEY") != null || System.getenv("MODELSCOPE_API_KEY") != null)
            return "modelscope";
        if (env.get("OPENAI_API_KEY") != null || System.getenv("OPENAI_API_KEY") != null)
            return "openai";
        if (env.get("ZHIPU_API_KEY") != null || System.getenv("ZHIPU_API_KEY") != null)
            return "zhipu";

        // 获取实际的凭证
        String actualApiKey = apiKey != null ? apiKey : env.get("LLM_API_KEY");
        String actualBaseUrl = baseUrl != null ? baseUrl : env.get("LLM_BASE_URL");

        // 2. 根据 base_url 判断
        if (actualBaseUrl != null) {
            String baseUrlLower = actualBaseUrl.toLowerCase();
            if (baseUrlLower.contains("api-inference.modelscope.cn")) return "modelscope";
            if (baseUrlLower.contains("open.bigmodel.cn")) return "zhipu";
            if (baseUrlLower.contains("localhost") || baseUrlLower.contains("127.0.0.1")) {
                if (baseUrlLower.contains(":11434")) return "ollama";
                if (baseUrlLower.contains(":8000")) return "vllm";
                return "local";
            }
        }

        // 3. 根据 API 密钥格式辅助判断
        if (actualApiKey != null && actualApiKey.startsWith("ms-")) return "modelscope";

        // 4. 默认返回 auto
        return "auto";
    }

    public static void main(String[] args){
        try {
            HelloAgentsLLM llmClient = new HelloAgentsLLM();

            List<Map<String,String>> exampleMessages = new ArrayList<>();
            Map<String,String> systemMessage = new HashMap<>();
            systemMessage.put("role","system");
            systemMessage.put("content","You are a helpful assistant that writes java code.");

            Map<String,String> userMessage = new HashMap<>();
            userMessage.put("role","user");
            userMessage.put("content","写一个快速排序算法");


            exampleMessages.add(systemMessage);
            exampleMessages.add(userMessage);

            System.out.println("--- 调用LLM ---");
            String responseText = llmClient.think(exampleMessages,Float.valueOf(0));
            if(responseText != null){
                System.out.println("\n\n--- 完整模型响应 ---");
                System.out.println(responseText);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
