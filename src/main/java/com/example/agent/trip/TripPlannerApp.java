package com.example.agent.trip;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.trip.model.TripModels.*;
import com.example.agent.trip.service.MultiAgentTripPlanner;
import com.example.agent.trip.service.UnsplashService;
import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

import java.util.Map;

/**
 * 智能旅行助手 HTTP 服务入口。
 *
 * 对应 Python backend/app/api/main.py + routes/*.
 *
 * 启动: mvn exec:java -Dexec.mainClass=com.example.agent.trip.TripPlannerApp
 */
public class TripPlannerApp {

    private static final Gson GSON = new Gson();
    private static MultiAgentTripPlanner planner;
    private static UnsplashService unsplash;

    public static void main(String[] args) {
        // 加载配置（自动读 .env 和 .env.example）
        TripPlannerConfig config = TripPlannerConfig.load();

        // 初始化 LLM
        HelloAgentsLLM llm;
        try {
            llm = new HelloAgentsLLM();
            config.setModelName(llm.model);
        } catch (Exception e) {
            System.out.println("❌ LLM 初始化失败: " + e.getMessage());
            return;
        }

        // 初始化规划器和 Unsplash
        planner = new MultiAgentTripPlanner(llm, config.getAmapApiKey());
        if (config.getUnsplashAccessKey() != null && !config.getUnsplashAccessKey().isEmpty()) {
            unsplash = new UnsplashService(config.getUnsplashAccessKey());
        }

        // 启动 HTTP 服务
        Javalin app = Javalin.create(jc -> {
            jc.http.defaultContentType = "application/json";
            // 增大 Jetty idle timeout 到 5 分钟，避免长时间 LLM 调用时连接断开
            jc.jetty.modifyServer(server -> {
                for (var connector : server.getConnectors()) {
                    if (connector instanceof org.eclipse.jetty.server.ServerConnector sc) {
                        sc.setIdleTimeout(300_000);
                    }
                }
            });
            jc.bundledPlugins.enableCors(cors -> {
                for (String origin : config.getCorsOrigins()) {
                    cors.addRule(rule -> rule.allowHost(origin));
                }
                cors.addRule(rule -> rule.allowHost("http://localhost:5173"));
                cors.addRule(rule -> rule.allowHost("http://127.0.0.1:5173"));
            });
            if (java.nio.file.Files.isDirectory(java.nio.file.Path.of("frontend/dist"))) {
                jc.staticFiles.add("frontend/dist", Location.EXTERNAL);
            }
        });

        // ==================== 健康检查 ====================
        app.get("/", ctx -> ctx.json(Map.of(
                "app", "HelloAgents Trip Planner",
                "version", "1.0.0",
                "model", config.getModelName())));

        app.get("/health", ctx -> ctx.json(Map.of("status", "ok")));

        app.get("/api/trip/health", ctx -> ctx.json(Map.of("status", "ok", "planner", "ready")));

        // ==================== 核心: 行程规划 ====================
        app.post("/api/trip/plan", TripPlannerApp::handlePlanTrip);

        // ==================== 地图代理 ====================
        app.get("/api/map/poi", ctx -> ctx.json(POISearchResponse.class));
        app.get("/api/map/weather", ctx -> ctx.json(WeatherResponse.class));
        app.post("/api/map/route", ctx -> ctx.json(RouteResponse.class));

        // ==================== POI 详情 ====================
        app.get("/api/poi/detail/{poi_id}", ctx ->
                ctx.json(Map.of("success", true, "data", Map.of())));

        app.get("/api/poi/search", ctx ->
                ctx.json(Map.of("success", true, "data", java.util.List.of())));

        app.get("/api/poi/photo", TripPlannerApp::handlePhotoSearch);

        // ==================== 启动 ====================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🚀 HelloAgents 智能旅行助手已启动");
        System.out.println("   API: http://" + config.getHost() + ":" + config.getPort());
        System.out.println("   前端: http://" + config.getHost() + ":" + config.getPort());
        System.out.println("   高德 MCP: " + (config.getAmapApiKey() != null ? "✅ 已配置" : "❌ 未配置"));
        System.out.println("   Unsplash: " + (unsplash != null ? "✅ 已配置" : "⚠️ 未配置"));
        System.out.println("=".repeat(60) + "\n");

        app.start(config.getHost(), config.getPort());
    }

    // ==================== 路由处理器 ====================

    @SuppressWarnings("unchecked")
    private static void handlePlanTrip(Context ctx) {
        try {
            Map<String, Object> body = GSON.fromJson(ctx.body(), Map.class);
            TripRequest request = parseTripRequest(body);

            TripPlan plan = planner.planTrip(request);
            ctx.status(200).json(TripPlanResponse.ok(plan));

        } catch (Exception e) {
            System.err.println("[DEBUG] handlePlanTrip 异常: " + e.getClass().getName() + " - " + e.getMessage());
            ctx.status(500).json(TripPlanResponse.error("规划失败: " + e.getMessage()));
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static TripRequest parseTripRequest(Map<String, Object> body) {
        return new TripRequest(
                (String) body.getOrDefault("city", "北京"),
                (String) body.getOrDefault("start_date", "2025-01-01"),
                (String) body.getOrDefault("end_date", "2025-01-03"),
                toInt(body, "travel_days", 3),
                (String) body.getOrDefault("transportation", "公共交通"),
                (String) body.getOrDefault("accommodation", "经济型酒店"),
                (java.util.List<String>) body.getOrDefault("preferences", java.util.List.of()),
                (String) body.getOrDefault("free_text_input", ""));
    }

    private static void handlePhotoSearch(Context ctx) {
        String name = ctx.queryParam("name");
        if (name == null || name.isEmpty()) {
            ctx.json(Map.of("success", false, "message", "缺少 name 参数"));
            return;
        }

        if (unsplash == null) {
            ctx.json(Map.of("success", false, "message", "Unsplash 未配置"));
            return;
        }

        String url = unsplash.getPhotoUrl(name);
        if (url != null) {
            ctx.json(Map.of("success", true, "url", url));
        } else {
            ctx.json(Map.of("success", false, "message", "未找到图片"));
        }
    }

    private static int toInt(Map<String, Object> map, String key, int defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) {}
        }
        return defaultValue;
    }
}
