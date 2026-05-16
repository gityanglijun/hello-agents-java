package com.cybertown.agent;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.SimpleAgent;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NPC 好感度管理器 — 对应 Python relationship_manager.py。
 *
 * 功能: 管理 NPC 与玩家的好感度 (0-100)、使用 LLM 分析对话情感、自动更新好感度。
 */
public class RelationshipManager {

    private static final Gson GSON = new Gson();
    public static final double DEFAULT_AFFINITY = 50.0;

    private final HelloAgentsLLM llm;
    private final SimpleAgent analyzerAgent;

    /** 存储: {npc_name: {player_id: affinity_score}} */
    private final Map<String, Map<String, Double>> affinityScores = new LinkedHashMap<>();

    // ==================== 构造 ====================

    public RelationshipManager(HelloAgentsLLM llm) {
        this.llm = llm;
        this.analyzerAgent = new SimpleAgent(
                "AffinityAnalyzer",
                llm,
                buildAnalyzerPrompt()
        );
        System.out.println("💖 好感度管理系统已初始化");
    }

    /** 无 LLM 时使用占位模式 */
    public RelationshipManager() {
        this.llm = null;
        this.analyzerAgent = null;
        System.out.println("💖 好感度管理系统已初始化 (占位模式)");
    }

    // ==================== 分析器提示词 ====================

    /** 创建情感分析 Agent 的系统提示词 — 对应 Python _create_analyzer_prompt() */
    private static String buildAnalyzerPrompt() {
        return """
你是一个情感分析专家,负责分析对话中的情感倾向,判断是否应该改变NPC对玩家的好感度。

【任务】
分析玩家与NPC的对话,判断是否应该改变好感度,以及改变的幅度。

【分析维度】
1. **玩家态度**: 友好/中立/不友好
2. **对话内容**: 积极/中立/消极
3. **互动质量**: 深入/一般/敷衍
4. **情感倾向**: 赞美/批评/中性

【好感度变化规则】
- 赞美、感谢、请教: +3 到 +8
- 友好问候、正常交流: +1 到 +3
- 普通闲聊、中性话题: 0
- 批评、质疑、不耐烦: -3 到 -8
- 侮辱、攻击、恶意: -8 到 -15

【输出格式】(严格遵守JSON格式,不要添加任何其他文字)
{
    "should_change": true/false,
    "change_amount": -15到+10之间的整数,
    "reason": "简短说明原因(10字以内)",
    "sentiment": "positive/neutral/negative"
}

【示例1】
玩家: "你好,很高兴认识你!"
NPC: "你好!我也很高兴认识你。"
输出: {"should_change": true, "change_amount": 5, "reason": "友好问候", "sentiment": "positive"}

【示例2】
玩家: "你这个设计太丑了!"
NPC: "抱歉,我会改进的..."
输出: {"should_change": true, "change_amount": -8, "reason": "批评工作", "sentiment": "negative"}

【示例3】
玩家: "今天天气不错"
NPC: "是啊,挺好的。"
输出: {"should_change": false, "change_amount": 0, "reason": "普通闲聊", "sentiment": "neutral"}

【示例4】
玩家: "你的代码写得真棒!"
NPC: "谢谢!我最近在研究新技术。"
输出: {"should_change": true, "change_amount": 8, "reason": "赞美工作", "sentiment": "positive"}

【示例5】
玩家: "能教教我吗?"
NPC: "当然可以!我很乐意分享。"
输出: {"should_change": true, "change_amount": 6, "reason": "请教学习", "sentiment": "positive"}

【重要】
- 只输出JSON,不要添加任何解释或其他文字
- change_amount必须是整数
- reason必须简短(10字以内)
- sentiment必须是positive/neutral/negative之一
""";
    }

    // ==================== 好感度 CRUD ====================

    public double getAffinity(String npcName, String playerId) {
        return affinityScores
                .getOrDefault(npcName, Collections.emptyMap())
                .getOrDefault(playerId, DEFAULT_AFFINITY);
    }

    public void setAffinity(String npcName, double affinity, String playerId) {
        affinity = Math.max(0.0, Math.min(100.0, affinity));
        affinityScores.computeIfAbsent(npcName, k -> new LinkedHashMap<>())
                      .put(playerId, affinity);
    }

    // ==================== 好感度等级 & 修饰词 ====================

    /** 对应 Python get_affinity_level() */
    public String getAffinityLevel(double affinity) {
        if (affinity >= 80) return "挚友";
        if (affinity >= 60) return "亲密";
        if (affinity >= 40) return "友好";
        if (affinity >= 20) return "熟悉";
        return "陌生";
    }

    /** 对应 Python get_affinity_modifier() */
    public String getAffinityModifier(double affinity) {
        if (affinity >= 80) return "非常热情友好,像老朋友一样亲切,愿意分享私人话题";
        if (affinity >= 60) return "友好热情,愿意多聊,会主动关心对方";
        if (affinity >= 40) return "礼貌友善,正常交流,保持专业";
        if (affinity >= 20) return "礼貌但略显生疏,回答简洁";
        return "冷淡疏离,不太愿意多说,回答简短";
    }

    // ==================== 核心：分析并更新好感度 ====================

    /**
     * 分析对话并更新好感度 — 对应 Python analyze_and_update_affinity()。
     */
    public Map<String, Object> analyzeAndUpdateAffinity(
            String npcName, String playerMessage, String npcResponse, String playerId) {

        // 占位模式: 无 LLM 时返回无变化
        if (analyzerAgent == null) {
            double current = getAffinity(npcName, playerId);
            return Map.of("changed", false, "affinity", current,
                    "new_affinity", current, "change_amount", 0.0, "sentiment", "neutral");
        }

        String prompt = String.format("""
                请分析以下对话:

                玩家: %s
                %s: %s

                请判断是否应该改变好感度,并给出变化量。
                """, playerMessage, npcName, npcResponse);

        try {
            String response = analyzerAgent.run(prompt);
            analyzerAgent.clearHistory();

            Map<String, Object> analysis = parseAnalysis(response);

            boolean shouldChange = (boolean) analysis.get("should_change");
            if (shouldChange) {
                double currentAffinity = getAffinity(npcName, playerId);
                double changeAmount = ((Number) analysis.get("change_amount")).doubleValue();
                double newAffinity = Math.max(0.0, Math.min(100.0, currentAffinity + changeAmount));

                setAffinity(npcName, newAffinity, playerId);

                String oldLevel = getAffinityLevel(currentAffinity);
                String newLevel = getAffinityLevel(newAffinity);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("changed", true);
                result.put("old_affinity", currentAffinity);
                result.put("new_affinity", newAffinity);
                result.put("change_amount", changeAmount);
                result.put("reason", analysis.getOrDefault("reason", ""));
                result.put("sentiment", analysis.getOrDefault("sentiment", "neutral"));
                result.put("old_level", oldLevel);
                result.put("new_level", newLevel);
                return result;
            } else {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("changed", false);
                result.put("affinity", getAffinity(npcName, playerId));
                result.put("reason", analysis.getOrDefault("reason", ""));
                result.put("sentiment", analysis.getOrDefault("sentiment", "neutral"));
                return result;
            }

        } catch (Exception e) {
            System.err.println("❌ 好感度分析失败: " + e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("changed", false);
            result.put("affinity", getAffinity(npcName, playerId));
            result.put("reason", "分析失败");
            result.put("sentiment", "neutral");
            return result;
        }
    }

    // ==================== 响应解析 ====================

    /**
     * 解析 LLM 分析结果 — 对应 Python _parse_analysis()。
     * 优先级: 直接JSON → 提取JSON子串 → 正则提取字段
     */
    private Map<String, Object> parseAnalysis(String response) {
        // 1. 直接解析 JSON
        try {
            Map<String, Object> result = GSON.fromJson(response,
                    new TypeToken<Map<String, Object>>() {}.getType());
            if (result.containsKey("should_change")) return result;
        } catch (Exception e) { /* fall through */ }

        // 2. 提取 JSON 子串
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start != -1 && end > start) {
            try {
                String jsonStr = response.substring(start, end + 1);
                Map<String, Object> result = GSON.fromJson(jsonStr,
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (result.containsKey("should_change")) return result;
            } catch (Exception ignored) {}
        }

        // 3. 正则提取各字段
        boolean shouldChange = matchBool(response, "should_change");
        double changeAmount = matchDouble(response, "change_amount");
        String reason = matchString(response, "reason");
        String sentiment = matchString(response, "sentiment");

        if (matchPattern(response, "should_change") != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("should_change", shouldChange);
            result.put("change_amount", changeAmount);
            result.put("reason", reason != null ? reason : "未知");
            result.put("sentiment", sentiment != null ? sentiment : "neutral");
            return result;
        }

        // 4. 解析失败，返回默认值
        System.out.println("⚠️  JSON解析失败,使用默认值。原始响应: "
                + response.substring(0, Math.min(100, response.length())) + "...");
        return Map.of("should_change", false, "change_amount", 0.0,
                "reason", "解析失败", "sentiment", "neutral");
    }

    // ==================== 正则辅助 ====================

    private static String matchPattern(String text, String key) {
        Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*(true|false|(-?\\d+(?:\\.\\d+)?)|\\\"([^\\\"]+)\\\")",
                Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(0) : null;
    }

    private static boolean matchBool(String text, String key) {
        Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() && "true".equalsIgnoreCase(m.group(1));
    }

    private static double matchDouble(String text, String key) {
        Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(text);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); }
            catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }

    private static String matchString(String text, String key) {
        Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    // ==================== 批量查询 ====================

    public Map<String, Map<String, Object>> getAllAffinities(String playerId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String npc : NpcConfig.NPC_ROLES.keySet()) {
            double aff = getAffinity(npc, playerId);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("affinity", aff);
            info.put("level", getAffinityLevel(aff));
            info.put("modifier", getAffinityModifier(aff));
            result.put(npc, info);
        }
        return result;
    }
}
