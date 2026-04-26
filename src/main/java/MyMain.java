import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MyMain {

    public static void main (String[] args){
        MyLLM llm = new MyLLM.Builder()
                .provider("vllm")
                .model("Qwen/Qwen1.5-0.5B-Chat") //# 需与服务启动时指定的模型一致
                .baseUrl("http://localhost:8000/v1")
                .apiKey("vllm")
                .build();// # 本地服务通常不需要真实API Key，可填任意非空字符串


        /*MyLLM llm = new MyLLM.Builder()
                .provider("modelscope")
                .build();*/

        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content","你好，请介绍一下你自己。")
        );

        String responseStream = llm.think(messages);

        System.out.println("ModelScope Response");

    }
}
