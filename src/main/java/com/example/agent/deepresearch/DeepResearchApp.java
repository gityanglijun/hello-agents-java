package com.example.agent.deepresearch;

import com.example.agent.deepresearch.model.DeepResearchModels.*;
import com.example.agent.llm.HelloAgentsLLM;
import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;

import java.util.*;

/**
 * 深度研究助手 HTTP 服务入口。
 * 对应 Python main.py。
 *
 * 启动: mvn exec:java -Dexec.mainClass=com.example.agent.deepresearch.DeepResearchApp
 */
public class DeepResearchApp {

    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        DeepResearchConfig config = DeepResearchConfig.fromEnv();
        DeepResearchAgent agent = new DeepResearchAgent(config);

        System.out.println("===== HelloAgents 深度研究助手 =====");
        System.out.println("LLM 模型: " + config.getLlmModelId());
        System.out.println("搜索引擎: " + config.getSearchApi());
        System.out.println("笔记功能: " + (config.isEnableNotes() ? "启用" : "禁用"));
        System.out.println("服务端口: " + config.getServerPort());

        Javalin app = Javalin.create(jc -> {
            jc.http.defaultContentType = "application/json";
            jc.jetty.modifyServer(server -> {
                for (var connector : server.getConnectors()) {
                    if (connector instanceof org.eclipse.jetty.server.ServerConnector sc) {
                        sc.setIdleTimeout(300_000); // 5 min
                    }
                }
            });
            jc.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> rule.anyHost());
            });
        }).start(config.getServerPort());

        // 健康检查
        app.get("/api/healthz", ctx -> ctx.json(Map.of("status", "ok")));

        // 同步深度研究
        app.post("/api/research", ctx -> {
            ResearchRequest req = parseRequest(ctx);
            if (req.getTopic() == null || req.getTopic().isBlank()) {
                ctx.status(400).json(ResearchResponse.error("请提供研究主题").toMap());
                return;
            }

            // 如果请求指定了 search_api，覆盖配置
            if (req.getSearchApi() != null && !req.getSearchApi().isBlank()) {
                config.setSearchApi(req.getSearchApi());
            }

            System.out.println("\n📨 收到研究请求: " + req.getTopic());
            SummaryStateOutput output = agent.run(req.getTopic());

            List<Map<String, Object>> taskMaps = new ArrayList<>();
            for (TodoItem t : output.getTodoItems()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("id", t.getId());
                tm.put("title", t.getTitle());
                tm.put("intent", t.getIntent());
                tm.put("query", t.getQuery());
                tm.put("status", t.getStatus());
                tm.put("summary", t.getSummary());
                tm.put("sources_summary", t.getSourcesSummary());
                taskMaps.add(tm);
            }

            ResearchResponse resp = ResearchResponse.ok(output.getReportMarkdown(),
                    output.getTodoItems());
            ctx.json(resp.toMap());
        });

        // 流式深度研究
        app.post("/api/research/stream", ctx -> {
            ResearchRequest req = parseRequest(ctx);
            if (req.getTopic() == null || req.getTopic().isBlank()) {
                ctx.status(400).json(ResearchResponse.error("请提供研究主题").toMap());
                return;
            }

            if (req.getSearchApi() != null && !req.getSearchApi().isBlank()) {
                config.setSearchApi(req.getSearchApi());
            }

            ctx.contentType("text/event-stream");
            ctx.header("Cache-Control", "no-cache");
            ctx.header("Connection", "keep-alive");

            try {
                agent.runStream(req.getTopic(), eventJson -> {
                    try {
                        ctx.outputStream().write(("data: " + eventJson + "\n\n").getBytes("UTF-8"));
                        ctx.outputStream().flush();
                    } catch (Exception ignored) {}
                });
                ctx.outputStream().write("data: [DONE]\n\n".getBytes("UTF-8"));
                ctx.outputStream().flush();
            } catch (Exception e) {
                String errorJson = GSON.toJson(Map.of(
                        "type", "error",
                        "message", e.getMessage()));
                try {
                    ctx.outputStream().write(("data: " + errorJson + "\n\n").getBytes("UTF-8"));
                    ctx.outputStream().write("data: [DONE]\n\n".getBytes("UTF-8"));
                    ctx.outputStream().flush();
                } catch (Exception ignored) {}
            }
        });

        System.out.println("\n🚀 深度研究助手已启动: http://localhost:" + config.getServerPort());
        System.out.println("   - POST /research        同步研究");
        System.out.println("   - POST /research/stream 流式研究 (SSE)");
        System.out.println("   - GET  /healthz         健康检查");
    }

    private static ResearchRequest parseRequest(Context ctx) {
        try {
            String body = ctx.body();
            return GSON.fromJson(body, ResearchRequest.class);
        } catch (Exception e) {
            ResearchRequest req = new ResearchRequest();
            String topic = ctx.queryParam("topic");
            if (topic == null) {
                topic = ctx.formParam("topic");
            }
            req.setTopic(topic);
            req.setSearchApi(ctx.queryParam("search_api"));
            return req;
        }
    }
}
