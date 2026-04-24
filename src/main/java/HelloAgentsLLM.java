import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class HelloAgentsLLM {

    public String model;
    public OpenAIClient client;


    // 供子类使用的受保护构造方法：直接设置 model 和 client，绕过验证
    protected HelloAgentsLLM(String model, OpenAIClient client) {
        this.model = model;
        this.client = client;
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
            return null;
        }
    }

    public String think(List<Map<String,String>> messages){
        return think(messages, (float) 0);
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
