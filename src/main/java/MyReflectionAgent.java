import java.util.Map;

public class MyReflectionAgent extends ReflectionAgent {

    public MyReflectionAgent(
            String name,
            HelloAgentsLLM llm,
            String systemPrompt,
            Config config,
            Map<String, String> customPrompts
    ) {
        super(name, llm, systemPrompt, config, customPrompts);
    }

    public MyReflectionAgent(String name, HelloAgentsLLM llm) {
        super(name, llm);
    }
}
