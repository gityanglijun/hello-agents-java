package com.cybertown;

import com.cybertown.agent.NpcAgentManager;
import com.cybertown.config.AppConfig;
import com.cybertown.service.StateManager;
import com.cybertown.util.Logger;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.*;

/**
 * 赛博小镇 — 后端主入口 (Javalin HTTP 服务器)。
 * 对应 Python main.py。
 */
public class CyberTownApp {

    private static final Gson GSON = new Gson();

    private static NpcAgentManager npcManager;
    private static StateManager stateManager;

    public static void main(String[] args) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🎮 赛博小镇后端服务启动中...");
        System.out.println("=".repeat(60));

        // 验证配置
        AppConfig.validate();

        // 初始化管理器
        npcManager = NpcAgentManager.getInstance();
        stateManager = StateManager.getInstance(AppConfig.NPC_UPDATE_INTERVAL);
        stateManager.start();

        // TODO: 等 Godot 前端就绪后改为 start()
        // stateManager.start();  // 启动定时 NPC 状态更新

        // 创建 Javalin 服务
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
            config.http.defaultContentType = "application/json";
        });

        registerRoutes(app);

        app.start(AppConfig.API_HOST, AppConfig.API_PORT);

        System.out.println("\n✅ 所有服务已启动!");
        System.out.println("📡 API地址: http://" + AppConfig.API_HOST + ":" + AppConfig.API_PORT);
        System.out.println("=".repeat(60) + "\n");

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 正在关闭服务...");
            stateManager.stop();
            app.stop();
            System.out.println("✅ 服务已关闭\n");
        }));
    }

    // ==================== 路由注册 ====================

    private static void registerRoutes(Javalin app) {
        // 根路径
        app.get("/", CyberTownApp::root);

        // 健康检查
        app.get("/health", CyberTownApp::health);

        // 对话
        app.post("/chat", CyberTownApp::chatWithNpc);

        // NPC 列表
        app.get("/npcs", CyberTownApp::listNpcs);

        // NPC 状态 (批量生成结果)
        app.get("/npcs/status", CyberTownApp::getNpcsStatus);
        app.post("/npcs/status/refresh", CyberTownApp::refreshNpcsStatus);

        // 单个 NPC
        app.get("/npcs/{npcName}", CyberTownApp::getNpcInfo);

        // 记忆
        app.get("/npcs/{npcName}/memories", CyberTownApp::getNpcMemories);
        app.delete("/npcs/{npcName}/memories", CyberTownApp::clearNpcMemories);

        // 好感度
        app.get("/npcs/{npcName}/affinity", CyberTownApp::getNpcAffinity);
        app.put("/npcs/{npcName}/affinity", CyberTownApp::setNpcAffinity);
        app.get("/affinities", CyberTownApp::getAllAffinities);
    }

    // ==================== API 处理 ====================

    private static void root(Context ctx) {
        ctx.json(Map.of(
                "service", AppConfig.API_TITLE,
                "version", AppConfig.API_VERSION,
                "status", "running",
                "features", List.of("AI对话", "NPC记忆系统", "好感度系统", "批量状态更新"),
                "endpoints", Map.of(
                        "chat", "/chat",
                        "npcs", "/npcs",
                        "npcs_status", "/npcs/status",
                        "npc_memories", "/npcs/{npcName}/memories",
                        "npc_affinity", "/npcs/{npcName}/affinity",
                        "all_affinities", "/affinities"
                )
        ));
    }

    private static void health(Context ctx) {
        ctx.json(Map.of("status", "healthy", "timestamp",
                java.time.LocalDateTime.now().toString()));
    }

    /** POST /chat — 与 NPC 对话 */
    private static void chatWithNpc(Context ctx) {
        Map<String, String> body = GSON.fromJson(ctx.body(),
                new TypeToken<Map<String, String>>() {}.getType());

        String npcName = body.get("npc_name");
        String message = body.get("message");

        if (npcName == null || message == null) {
            ctx.status(400).json(Map.of("error", "缺少 npc_name 或 message"));
            return;
        }

        Map<String, String> npcInfo = npcManager.getNpcInfo(npcName);
        if (npcInfo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "NPC '" + npcName + "' 不存在"));
            return;
        }

        try {
            Logger.dialogueStart(npcName, message);

            String responseText = npcManager.chat(npcName, message);

            Logger.dialogueEnd();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("npc_name", npcName);
            result.put("npc_title", npcInfo.get("title"));
            result.put("message", responseText);
            result.put("success", true);
            result.put("timestamp", java.time.LocalDateTime.now().toString());
            ctx.json(result);

        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "对话处理失败: " + e.getMessage()));
        }
    }

    /** GET /npcs — 获取所有 NPC 列表 */
    private static void listNpcs(Context ctx) {
        List<Map<String, String>> npcs = npcManager.getAllNpcs();
        ctx.json(Map.of("npcs", npcs, "total", npcs.size()));
    }

    /** GET /npcs/status — 获取所有 NPC 当前状态 */
    private static void getNpcsStatus(Context ctx) {
        Map<String, Object> state = stateManager.getCurrentState();
        ctx.json(state);
    }

    /** POST /npcs/status/refresh — 强制刷新 NPC 状态 */
    private static void refreshNpcsStatus(Context ctx) {
        stateManager.forceUpdate();
        Map<String, Object> state = stateManager.getCurrentState();
        ctx.json(Map.of("message", "NPC状态已刷新", "dialogues", state.get("dialogues")));
    }

    /** GET /npcs/{npcName} — 获取指定 NPC 信息 */
    private static void getNpcInfo(Context ctx) {
        String npcName = ctx.pathParam("npcName");
        Map<String, String> npcInfo = npcManager.getNpcInfo(npcName);

        if (npcInfo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "NPC '" + npcName + "' 不存在"));
            return;
        }

        npcInfo.put("current_dialogue", stateManager.getNpcDialogue(npcName));
        ctx.json(npcInfo);
    }

    /** GET /npcs/{npcName}/memories — 获取 NPC 记忆 */
    private static void getNpcMemories(Context ctx) {
        String npcName = ctx.pathParam("npcName");
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);

        Map<String, String> npcInfo = npcManager.getNpcInfo(npcName);
        if (npcInfo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "NPC '" + npcName + "' 不存在"));
            return;
        }

        try {
            List<Map<String, Object>> memories = npcManager.getNpcMemories(npcName, "player", limit);
            ctx.json(Map.of("npc_name", npcName, "memories", memories, "total", memories.size()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "获取记忆失败: " + e.getMessage()));
        }
    }

    /** DELETE /npcs/{npcName}/memories — 清空 NPC 记忆 */
    private static void clearNpcMemories(Context ctx) {
        String npcName = ctx.pathParam("npcName");

        Map<String, String> npcInfo = npcManager.getNpcInfo(npcName);
        if (npcInfo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "NPC '" + npcName + "' 不存在"));
            return;
        }

        try {
            npcManager.clearNpcMemory(npcName);
            ctx.json(Map.of("message", "已清空" + npcName + "的记忆",
                    "npc_name", npcName, "memory_type", "all"));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "清空记忆失败: " + e.getMessage()));
        }
    }

    /** GET /npcs/{npcName}/affinity — 获取好感度 */
    private static void getNpcAffinity(Context ctx) {
        String npcName = ctx.pathParam("npcName");
        String playerId = ctx.queryParam("player_id") != null
                ? ctx.queryParam("player_id") : "player";

        Map<String, String> npcInfo = npcManager.getNpcInfo(npcName);
        if (npcInfo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "NPC '" + npcName + "' 不存在"));
            return;
        }

        try {
            Map<String, Object> affinity = new LinkedHashMap<>(npcManager.getNpcAffinity(npcName, playerId));
            affinity.put("npc_name", npcName);
            affinity.put("player_id", playerId);
            ctx.json(affinity);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "获取好感度失败: " + e.getMessage()));
        }
    }

    /** PUT /npcs/{npcName}/affinity — 设置好感度 */
    private static void setNpcAffinity(Context ctx) {
        String npcName = ctx.pathParam("npcName");
        String playerId = ctx.queryParam("player_id") != null
                ? ctx.queryParam("player_id") : "player";
        String affinityStr = ctx.queryParam("affinity");

        if (affinityStr == null) {
            ctx.status(400).json(Map.of("error", "缺少 affinity 参数"));
            return;
        }

        double affinity;
        try {
            affinity = Double.parseDouble(affinityStr);
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "affinity 必须是数字"));
            return;
        }

        if (affinity < 0 || affinity > 100) {
            ctx.status(400).json(Map.of("error", "好感度必须在0-100之间"));
            return;
        }

        Map<String, String> npcInfo = npcManager.getNpcInfo(npcName);
        if (npcInfo.isEmpty()) {
            ctx.status(404).json(Map.of("error", "NPC '" + npcName + "' 不存在"));
            return;
        }

        try {
            npcManager.setNpcAffinity(npcName, affinity, playerId);
            Map<String, Object> affinityInfo = new LinkedHashMap<>(npcManager.getNpcAffinity(npcName, playerId));
            affinityInfo.put("message", "已设置" + npcName + "对玩家的好感度");
            affinityInfo.put("npc_name", npcName);
            affinityInfo.put("player_id", playerId);
            ctx.json(affinityInfo);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "设置好感度失败: " + e.getMessage()));
        }
    }

    /** GET /affinities — 获取所有 NPC 的好感度 */
    private static void getAllAffinities(Context ctx) {
        String playerId = ctx.queryParam("player_id") != null
                ? ctx.queryParam("player_id") : "player";

        try {
            Map<String, Map<String, Object>> affinities = npcManager.getAllAffinities(playerId);
            ctx.json(Map.of("player_id", playerId, "affinities", affinities));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "获取好感度失败: " + e.getMessage()));
        }
    }
}
