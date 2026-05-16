package com.cybertown.agent;

import com.example.agent.llm.HelloAgentsLLM;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 批量 NPC 对话生成器 — 对应 Python agents_batch.py 中的 NPCBatchGenerator。
 *
 * 核心思路：一次 LLM 调用生成所有 NPC 的对话，降低 API 成本和延迟。
 * 类似"预制菜"模式，批量准备好，需要时直接使用。
 */
public class NpcBatchGenerator {

    private static NpcBatchGenerator instance;

    public static NpcBatchGenerator getInstance() {
        if (instance == null) {
            instance = new NpcBatchGenerator();
        }
        return instance;
    }

    private static final Gson GSON = new Gson();

    private final HelloAgentsLLM llm;
    private boolean enabled;
    private final Map<String, NpcConfig.NpcRole> npcConfigs;

    /** 预设对话库 — LLM 不可用时的降级方案 */
    private final Map<String, Map<String, String>> presetDialogues;

    // ==================== 构造 ====================

    public NpcBatchGenerator() {
        System.out.println("🎨 正在初始化批量对话生成器...");

        HelloAgentsLLM llmTemp = null;
        boolean llmOk = false;
        try {
            llmTemp = new HelloAgentsLLM();
            llmOk = true;
            System.out.println("✅ 批量生成器初始化成功");
        } catch (Exception e) {
            System.err.println("❌ 批量生成器初始化失败: " + e.getMessage());
            System.out.println("⚠️  将使用预设对话模式");
        }
        this.llm = llmTemp;
        this.enabled = llmOk;
        this.npcConfigs = NpcConfig.NPC_ROLES;
        this.presetDialogues = buildPresetDialogues();
    }

    private static Map<String, Map<String, String>> buildPresetDialogues() {
        Map<String, Map<String, String>> presets = new LinkedHashMap<>();

        Map<String, String> morning = new LinkedHashMap<>();
        morning.put("张三", "早上好!今天要继续优化那个多智能体系统的性能。");
        morning.put("李四", "新的一天开始了,先整理一下今天的会议安排。");
        morning.put("王五", "早!先来杯咖啡提提神,然后开始设计新界面。");
        presets.put("morning", morning);

        Map<String, String> noon = new LinkedHashMap<>();
        noon.put("张三", "写了一上午代码,终于把那个bug修复了!");
        noon.put("李四", "上午的需求评审会很顺利,下午继续推进。");
        noon.put("王五", "这个配色方案看起来不错,再调整一下细节。");
        presets.put("noon", noon);

        Map<String, String> afternoon = new LinkedHashMap<>();
        afternoon.put("张三", "下午继续写代码,这个算法还需要优化一下。");
        afternoon.put("李四", "正在准备下周的产品规划会,需求文档快完成了。");
        afternoon.put("王五", "设计稿基本完成了,等会儿发给大家看看。");
        presets.put("afternoon", afternoon);

        Map<String, String> evening = new LinkedHashMap<>();
        evening.put("张三", "今天的代码提交完成,明天继续!");
        evening.put("李四", "今天的工作差不多了,整理一下明天的待办事项。");
        evening.put("王五", "设计工作告一段落,明天再继续优化。");
        presets.put("evening", evening);

        return presets;
    }

    // ==================== 核心：批量生成 ====================

    /**
     * 批量生成所有 NPC 的对话。
     * @param context 场景上下文（如"上午工作时间"、"午餐时间"等），null 则自动推断
     * @return NPC名称 → 对话内容的映射
     */
    public Map<String, String> generateBatchDialogues(String context) {
        if (!enabled || llm == null) {
            return getPresetDialogues();
        }

        try {
            String prompt = buildBatchPrompt(context);

            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content",
                            "你是一个游戏NPC对话生成器,擅长创作自然真实的办公室对话。"),
                    Map.of("role", "user", "content", prompt)
            );

            String response = llm.think(messages);
            Map<String, String> dialogues = parseResponse(response);

            if (dialogues != null && !dialogues.isEmpty()) {
                System.out.println("✅ 批量生成成功: " + dialogues.size() + "个NPC对话");
                return dialogues;
            } else {
                System.out.println("⚠️  解析失败,使用预设对话");
                return getPresetDialogues();
            }

        } catch (Exception e) {
            System.err.println("❌ 批量生成失败: " + e.getMessage());
            return getPresetDialogues();
        }
    }

    /** 无参快捷调用 */
    public Map<String, String> generateBatchDialogues() {
        return generateBatchDialogues(null);
    }

    // ==================== 提示词构建 ====================

    private String buildBatchPrompt(String context) {
        if (context == null || context.isBlank()) {
            context = getCurrentContext();
        }

        StringBuilder npcDesc = new StringBuilder();
        for (var entry : npcConfigs.entrySet()) {
            String name = entry.getKey();
            NpcConfig.NpcRole cfg = entry.getValue();
            npcDesc.append(String.format(
                    "- %s(%s): 在%s%s,性格%s\n",
                    name, cfg.title, cfg.location, cfg.activity, cfg.personality));
        }

        return String.format("""
                请为Datawhale办公室的3个NPC生成当前的对话或行为描述。

                【场景】%s

                【NPC信息】
                %s
                【生成要求】
                1. 每个NPC生成1句话(20-40字)
                2. 内容要符合角色设定、当前活动和场景氛围
                3. 可以是自言自语、工作状态描述、或简单的思考
                4. 要自然真实,像真实的办公室同事
                5. 可以体现一些个性化特点和情绪
                6. **必须严格按照JSON格式返回**

                【输出格式】(严格遵守)
                {"张三": "...", "李四": "...", "王五": "..."}

                【示例输出】
                {"张三": "这个bug真是见鬼了,已经调试两小时了...", "李四": "嗯,这个功能的优先级需要重新评估一下。", "王五": "这杯咖啡的拉花真不错,灵感来了!"}

                请生成(只返回JSON,不要其他内容):
                """, context, npcDesc.toString());
    }

    // ==================== 响应解析 ====================

    /** 解析 LLM 返回的 JSON — 对应 Python _parse_response() */
    private Map<String, String> parseResponse(String response) {
        if (response == null || response.isBlank()) return null;

        // 1. 直接解析
        try {
            Map<String, String> result = GSON.fromJson(response,
                    new TypeToken<Map<String, String>>() {}.getType());
            if (result != null && result.keySet().containsAll(npcConfigs.keySet())) {
                return result;
            }
            System.out.println("⚠️  JSON格式不正确(缺少NPC): " + result);
        } catch (Exception e) {
            // 2. 尝试提取 JSON 部分
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start != -1 && end > start) {
                try {
                    String jsonStr = response.substring(start, end + 1);
                    Map<String, String> result = GSON.fromJson(jsonStr,
                            new TypeToken<Map<String, String>>() {}.getType());
                    if (result != null) return result;
                } catch (Exception ignored) {}
            }
            System.out.println("⚠️  无法解析响应: " + response.substring(0, Math.min(100, response.length())) + "...");
        }
        return null;
    }

    // ==================== 时间场景推断 ====================

    /** 根据当前时间推断场景上下文 — 对应 Python _get_current_context() */
    static String getCurrentContext() {
        int hour = LocalDateTime.now().getHour();

        if (6 <= hour && hour < 9)   return "清晨时分,大家陆续到达办公室,准备开始新的一天";
        if (9 <= hour && hour < 12)  return "上午工作时间,大家都在专注工作,办公室氛围专注而忙碌";
        if (12 <= hour && hour < 14) return "午餐时间,大家在休息放松,聊聊天或者看看手机";
        if (14 <= hour && hour < 17) return "下午工作时间,继续推进项目,偶尔需要喝杯咖啡提神";
        if (17 <= hour && hour < 19) return "傍晚时分,准备收尾今天的工作,整理明天的计划";
        return "夜晚时分,办公室安静下来,偶尔还有人在加班";
    }

    // ==================== 降级方案 ====================

    /** 返回当前时段的预设对话 — 对应 Python _get_preset_dialogues() */
    public Map<String, String> getPresetDialogues() {
        int hour = LocalDateTime.now().getHour();

        String period;
        if (6 <= hour && hour < 12)       period = "morning";
        else if (12 <= hour && hour < 14) period = "noon";
        else if (14 <= hour && hour < 18) period = "afternoon";
        else                              period = "evening";

        return presetDialogues.getOrDefault(period, presetDialogues.get("morning"));
    }

    // ==================== 访问器 ====================

    public boolean isEnabled() { return enabled; }
}
