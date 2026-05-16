package com.cybertown.agent;

import com.cybertown.util.Logger;
import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.memory.MemoryManager;
import com.example.agent.memory.MemoryManager.MemoryItem;
import com.example.agent.pattern.SimpleAgent;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * NPC Agent 管理器 — 对应 Python agents.py 中的 NPCAgentManager。
 * 为每个 NPC 创建独立 Agent + 记忆系统 + 好感度。
 */
public class NpcAgentManager {

    private static NpcAgentManager instance;

    public static NpcAgentManager getInstance() {
        if (instance == null) {
            instance = new NpcAgentManager();
        }
        return instance;
    }

    // ==================== 内部状态 ====================

    private final HelloAgentsLLM llm;
    private final Map<String, SimpleAgent> agents = new LinkedHashMap<>();
    private final Map<String, MemoryManager> memories = new LinkedHashMap<>();
    private final RelationshipManager relationshipManager;

    // ==================== 构造 ====================

    public NpcAgentManager() {
        System.out.println("🤖 正在初始化NPC Agent系统...");

        HelloAgentsLLM llmTemp;
        try {
            llmTemp = new HelloAgentsLLM();
            System.out.println("✅ LLM初始化成功");
        } catch (Exception e) {
            System.err.println("❌ LLM初始化失败: " + e.getMessage());
            System.out.println("⚠️  将使用模拟模式运行");
            llmTemp = null;
        }
        this.llm = llmTemp;
        this.relationshipManager = (llm != null) ? new RelationshipManager(llm) : null;

        createAgents();
    }

    // ==================== Agent 创建 ====================

    private void createAgents() {
        for (var entry : NpcConfig.NPC_ROLES.entrySet()) {
            String name = entry.getKey();
            NpcConfig.NpcRole role = entry.getValue();

            try {
                String systemPrompt = NpcConfig.buildSystemPrompt(name, role);

                SimpleAgent agent = null;
                if (llm != null) {
                    agent = new SimpleAgent(name + "-" + role.title, llm, systemPrompt);
                }
                agents.put(name, agent);

                MemoryManager mm = createMemoryManager(name);
                memories.put(name, mm);

                System.out.println("✅ " + name + "(" + role.title + ") Agent创建成功 (记忆系统已启用)");

            } catch (Exception e) {
                System.err.println("❌ " + name + " Agent创建失败: " + e.getMessage());
                agents.put(name, null);
                memories.put(name, null);
            }
        }
    }

    /** 为 NPC 创建独立的记忆管理器 — 每个 NPC 一个 SQLite 数据库 */
    private MemoryManager createMemoryManager(String npcName) {
        String dbDir = Paths.get("cyber-town", "memory_data", npcName).toString();
        try {
            java.nio.file.Files.createDirectories(Paths.get(dbDir));
        } catch (Exception e) {
            System.err.println("  ⚠️ 创建记忆目录失败: " + e.getMessage());
        }

        String dbPath = Paths.get(dbDir, npcName + ".db").toString();
        MemoryManager mm = new MemoryManager(dbPath);
        System.out.println("  💾 " + npcName + "的记忆系统已初始化 (存储路径: " + dbPath + ")");
        return mm;
    }

    // ==================== 核心对话 ====================

    /**
     * 与指定 NPC 对话 — 支持记忆和好感度。
     * 对应 Python NPCAgentManager.chat()。
     */
    public String chat(String npcName, String message, String playerId) {
        if (!agents.containsKey(npcName)) {
            return "错误: NPC '" + npcName + "' 不存在";
        }

        SimpleAgent agent = agents.get(npcName);
        MemoryManager memoryManager = memories.get(npcName);
        NpcConfig.NpcRole role = NpcConfig.NPC_ROLES.get(npcName);

        if (agent == null) {
            return "你好!我是" + npcName + ",一名" + role.title + "。(当前为模拟模式,请配置API_KEY以启用AI对话)";
        }

        try {
            Logger.dialogueStart(npcName, message);

            // 1. 获取好感度
            String affinityContext = "";
            if (relationshipManager != null) {
                double affinity = relationshipManager.getAffinity(npcName, playerId);
                String affinityLevel = relationshipManager.getAffinityLevel(affinity);
                String affinityModifier = relationshipManager.getAffinityModifier(affinity);

                affinityContext = String.format("""
                        【当前关系】
                        你与玩家的关系: %s (好感度: %.0f/100)
                        【对话风格】%s

                        """, affinityLevel, affinity, affinityModifier);

                Logger.affinity(npcName, affinity, affinityLevel);
            }

            // 2. 检索相关记忆
            List<MemoryItem> relevantMemories = List.of();
            if (memoryManager != null) {
                relevantMemories = memoryManager.retrieveMemories(
                        message, 5, List.of(MemoryManager.TYPE_WORKING, MemoryManager.TYPE_EPISODIC), 0.3);
                Logger.memoryRetrieval(npcName, relevantMemories.size(), relevantMemories);
            }

            // 3. 构建增强提示词
            String memoryContext = buildMemoryContext(relevantMemories);

            StringBuilder enhancedMessage = new StringBuilder();
            enhancedMessage.append(affinityContext);
            if (!memoryContext.isBlank()) {
                enhancedMessage.append(memoryContext).append("\n\n");
            }
            enhancedMessage.append("【当前对话】\n玩家: ").append(message);

            // 4. Agent 生成回复
            Logger.generatingResponse();
            String response = agent.run(enhancedMessage.toString());
            Logger.npcResponse(npcName, response);

            // 5. 分析并更新好感度
            Logger.analyzingAffinity();
            Map<String, Object> affinityResult;
            if (relationshipManager != null) {
                affinityResult = relationshipManager.analyzeAndUpdateAffinity(
                        npcName, message, response, playerId);
                Logger.affinityChange(affinityResult);
            } else {
                affinityResult = Map.of("changed", false, "affinity", 50.0,
                        "new_affinity", 50.0, "change_amount", 0.0, "sentiment", "neutral");
            }

            // 6. 保存对话到记忆
            if (memoryManager != null) {
                saveConversationToMemory(memoryManager, npcName, message, response,
                        playerId, affinityResult);
                Logger.memorySaved(npcName);
            }

            Logger.dialogueEnd();
            return response;

        } catch (Exception e) {
            System.err.println("❌ " + npcName + "对话失败: " + e.getMessage());
            e.printStackTrace();
            return "抱歉,我现在有点忙,等会儿再聊吧。(错误: " + e.getMessage() + ")";
        }
    }

    /** 简化版 chat — 玩家 ID 默认为 "player" */
    public String chat(String npcName, String message) {
        return chat(npcName, message, "player");
    }

    // ==================== 记忆上下文构建 ====================

    /** 将记忆列表格式化为上下文文本 — 对应 Python _build_memory_context() */
    private String buildMemoryContext(List<MemoryItem> memories) {
        if (memories == null || memories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("【之前的对话记忆】\n");
        for (MemoryItem mem : memories) {
            String timeStr = mem.createdAt != null && mem.createdAt.length() >= 16
                    ? mem.createdAt.substring(11, 16)  // "HH:mm"
                    : "";
            sb.append("[").append(timeStr).append("] ").append(mem.content).append("\n");
        }
        return sb.toString();
    }

    // ==================== 记忆保存 ====================

    private void saveConversationToMemory(
            MemoryManager memoryManager,
            String npcName,
            String playerMessage,
            String npcResponse,
            String playerId,
            Map<String, Object> affinityInfo) {

        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        double affinity = ((Number) affinityInfo.getOrDefault("new_affinity",
                affinityInfo.getOrDefault("affinity", 50.0))).doubleValue();
        double affinityChange = ((Number) affinityInfo.getOrDefault("change_amount", 0.0)).doubleValue();
        String sentiment = (String) affinityInfo.getOrDefault("sentiment", "neutral");

        // 构建 metadata
        Map<String, String> playerMeta = new LinkedHashMap<>();
        playerMeta.put("speaker", "player");
        playerMeta.put("player_id", playerId);
        playerMeta.put("timestamp", now);
        playerMeta.put("affinity", String.valueOf(affinity));
        playerMeta.put("affinity_change", String.valueOf(affinityChange));
        playerMeta.put("sentiment", sentiment);
        playerMeta.put("interaction_type", "dialogue");
        playerMeta.put("npc_name", npcName);

        Map<String, String> npcMeta = new LinkedHashMap<>();
        npcMeta.put("speaker", npcName);
        npcMeta.put("player_id", playerId);
        npcMeta.put("timestamp", now);
        npcMeta.put("affinity", String.valueOf(affinity));
        npcMeta.put("sentiment", sentiment);
        npcMeta.put("interaction_type", "dialogue");
        npcMeta.put("npc_name", npcName);

        // 保存玩家消息
        memoryManager.addMemory(
                "玩家说: " + playerMessage,
                MemoryManager.TYPE_WORKING,
                0.5,
                playerMeta,
                false);

        // 保存 NPC 回复
        memoryManager.addMemory(
                "我说: " + npcResponse,
                MemoryManager.TYPE_WORKING,
                0.6,
                npcMeta,
                false);
    }

    // ==================== 信息查询 ====================

    /** 获取单个 NPC 信息 */
    public Map<String, String> getNpcInfo(String npcName) {
        NpcConfig.NpcRole role = NpcConfig.NPC_ROLES.get(npcName);
        if (role == null) return Collections.emptyMap();

        Map<String, String> info = new LinkedHashMap<>();
        info.put("name", npcName);
        info.put("title", role.title);
        info.put("location", role.location);
        info.put("activity", role.activity);
        info.put("available", String.valueOf(agents.get(npcName) != null));
        return info;
    }

    /** 获取所有 NPC 信息 */
    public List<Map<String, String>> getAllNpcs() {
        List<Map<String, String>> result = new ArrayList<>();
        for (String name : NpcConfig.NPC_ROLES.keySet()) {
            result.add(getNpcInfo(name));
        }
        return result;
    }

    /** 获取 NPC 记忆列表 */
    public List<Map<String, Object>> getNpcMemories(String npcName, String playerId, int limit) {
        MemoryManager mm = memories.get(npcName);
        if (mm == null) return Collections.emptyList();

        try {
            List<MemoryItem> memList = mm.retrieveMemories(
                    "", limit,
                    List.of(MemoryManager.TYPE_WORKING, MemoryManager.TYPE_EPISODIC),
                    0.0);

            List<Map<String, Object>> result = new ArrayList<>();
            for (MemoryItem mem : memList) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", mem.id);
                entry.put("content", mem.content);
                entry.put("type", mem.memoryType);
                entry.put("importance", mem.importance);
                entry.put("timestamp", mem.createdAt);
                entry.put("metadata", mem.metadata);
                result.add(entry);
            }
            return result;
        } catch (Exception e) {
            System.err.println("❌ 获取" + npcName + "记忆失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 好感度 ====================

    public Map<String, Object> getNpcAffinity(String npcName, String playerId) {
        if (relationshipManager == null) {
            return Map.of("affinity", 50.0, "level", "熟悉",
                    "modifier", "礼貌友善,正常交流,保持专业");
        }
        double aff = relationshipManager.getAffinity(npcName, playerId);
        return Map.of("affinity", aff,
                "level", relationshipManager.getAffinityLevel(aff),
                "modifier", relationshipManager.getAffinityModifier(aff));
    }

    public Map<String, Map<String, Object>> getAllAffinities(String playerId) {
        if (relationshipManager == null) return Collections.emptyMap();
        return relationshipManager.getAllAffinities(playerId);
    }

    public void setNpcAffinity(String npcName, double affinity, String playerId) {
        if (relationshipManager == null) {
            System.out.println("❌ 好感度系统未初始化");
            return;
        }
        relationshipManager.setAffinity(npcName, affinity, playerId);
        String level = relationshipManager.getAffinityLevel(affinity);
        System.out.println("✅ 已设置" + npcName + "对玩家的好感度: " + affinity + " (" + level + ")");
    }

    // ==================== 记忆管理 ====================

    /** 清空 NPC 记忆 */
    public void clearNpcMemory(String npcName) {
        MemoryManager mm = memories.get(npcName);
        if (mm == null) {
            System.out.println("❌ NPC '" + npcName + "' 不存在或没有记忆系统");
            return;
        }
        int count = mm.clearAll();
        System.out.println("✅ 已清空" + npcName + "的所有记忆 (" + count + " 条)");
    }

    // ==================== 访问器 ====================

    public Map<String, SimpleAgent> getAgents() { return agents; }
    public Map<String, MemoryManager> getMemories() { return memories; }
    public RelationshipManager getRelationshipManager() { return relationshipManager; }
}
