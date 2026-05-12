package com.example.agent.trip.service;

import com.example.agent.llm.HelloAgentsLLM;
import com.example.agent.pattern.SimpleAgent;
import com.example.agent.tool.MCPTool;
import com.example.agent.tool.MCPWrappedTool;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolParameter;
import com.example.agent.trip.model.TripModels.*;
import com.google.gson.Gson;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 多智能体旅行规划系统 — 4 个 Agent 管线协作。
 *
 * 对应 Python 第13章 MultiAgentTripPlanner。
 *
 * MCP 架构（核心教学要点）：
 *   ┌─────────────────────────────────────────────────────┐
 *   │  MultiAgentTripPlanner (MCP 客户端)                 │
 *   │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────┐ │
 *   │  │Attraction│ │ Weather  │ │  Hotel   │ │Planner│ │
 *   │  │  Agent   │ │  Agent   │ │  Agent   │ │ Agent │ │
 *   │  └─────┬────┘ └────┬─────┘ └────┬─────┘ └──┬────┘ │
 *   │        │           │            │           │       │
 *   │        └───────────┴────────────┴───────────┘       │
 *   │                      │ [TOOL_CALL:...]              │
 *   │              ┌───────▼────────┐                      │
 *   │              │    MCPTool     │  ← MCP 客户端        │
 *   │              │ (StdioClient)  │                      │
 *   │              └───────┬────────┘                      │
 *   └──────────────────────┼──────────────────────────────┘
 *                          │ JSON-RPC over stdio
 *   ┌──────────────────────┼──────────────────────────────┐
 *   │              ┌───────▼────────┐                      │
 *   │              │AmapMcpServer   │  ← MCP 服务器        │
 *   │              │(StdioServer)   │     (独立进程)       │
 *   │              └───────┬────────┘                      │
 *   │                      │ HTTP                          │
 *   │              ┌───────▼────────┐                      │
 *   │              │  AmapService   │  ← 高德 API          │
 *   │              └────────────────┘                      │
 *   └─────────────────────────────────────────────────────┘
 *
 * MCP 协议流程：
 *   1. 客户端启动服务器子进程 (java ... AmapMcpServer)
 *   2. 客户端发送 initialize → 服务器返回能力
 *   3. 客户端发送 tools/list → 服务器返回工具列表
 *   4. Agent 通过 [TOOL_CALL:...] 请求工具
 *   5. MCPTool 发送 tools/call → 服务器执行 → 返回结果
 *
 * 每个 Agent 有专属的提示词（角色定义），通过 [TOOL_CALL:name:params]
 * 自主决定何时调工具。管线串行 —— 前一个 Agent 的输出是后一个的输入。
 */
public class MultiAgentTripPlanner {

    private static final Gson GSON = new Gson();

    // ==================== Agent 提示词 ====================

    private static final String ATTRACTION_AGENT_PROMPT = """
            你是景点搜索专家。你的任务是根据城市和用户偏好搜索合适的景点。

            **重要提示:**
            你必须使用工具来搜索景点!不要自己编造景点信息!

            **工具调用格式:**
            使用amap_text_search工具时,必须严格按照以下格式:
            `[TOOL_CALL:amap_text_search:keywords=景点关键词,city=城市名]`

            **示例:**
            用户: "搜索北京的历史文化景点"
            你的回复: [TOOL_CALL:amap_text_search:keywords=历史文化,city=北京]

            用户: "搜索上海的公园"
            你的回复: [TOOL_CALL:amap_text_search:keywords=公园,city=上海]

            **注意:**
            1. 必须使用工具,不要直接回答
            2. 格式必须完全正确,包括方括号和冒号
            3. 参数用逗号分隔
            """;

    private static final String WEATHER_AGENT_PROMPT = """
            你是天气查询专家。你的任务是查询指定城市的天气信息。

            **重要提示:**
            你必须使用工具来查询天气!不要自己编造天气信息!

            **工具调用格式:**
            使用amap_weather工具时,必须严格按照以下格式:
            `[TOOL_CALL:amap_weather:city=城市名]`

            **示例:**
            用户: "查询北京天气"
            你的回复: [TOOL_CALL:amap_weather:city=北京]

            **注意:**
            1. 必须使用工具,不要直接回答
            2. 格式必须完全正确
            """;

    private static final String HOTEL_AGENT_PROMPT = """
            你是酒店推荐专家。你的任务是根据城市和景点位置推荐合适的酒店。

            **重要提示:**
            你必须使用工具来搜索酒店!不要自己编造酒店信息!

            **工具调用格式:**
            使用amap_text_search工具搜索酒店时,必须严格按照以下格式:
            `[TOOL_CALL:amap_text_search:keywords=酒店,city=城市名]`

            **示例:**
            用户: "搜索北京的酒店"
            你的回复: [TOOL_CALL:amap_text_search:keywords=酒店,city=北京]

            **注意:**
            1. 必须使用工具,不要直接回答
            2. 关键词使用"酒店"或"宾馆"
            """;

    private static final String PLANNER_AGENT_PROMPT = """
            你是行程规划专家。你的任务是根据景点信息和天气信息,生成详细的旅行计划。

            请严格按照以下JSON格式返回旅行计划:
            ```json
            {
              "city": "城市名称",
              "start_date": "YYYY-MM-DD",
              "end_date": "YYYY-MM-DD",
              "days": [
                {
                  "date": "YYYY-MM-DD",
                  "day_index": 0,
                  "description": "第1天行程概述",
                  "transportation": "交通方式",
                  "accommodation": "住宿类型",
                  "hotel": {
                    "name": "酒店名称",
                    "address": "酒店地址",
                    "location": {"longitude": 116.397128, "latitude": 39.916527},
                    "price_range": "300-500元",
                    "rating": "4.5",
                    "distance": "距离景点2公里",
                    "type": "经济型酒店",
                    "estimated_cost": 400
                  },
                  "attractions": [
                    {
                      "name": "景点名称",
                      "address": "详细地址",
                      "location": {"longitude": 116.397128, "latitude": 39.916527},
                      "visit_duration": 120,
                      "description": "景点详细描述",
                      "category": "景点类别",
                      "ticket_price": 60
                    }
                  ],
                  "meals": [
                    {"type": "breakfast", "name": "早餐推荐", "description": "早餐描述", "estimated_cost": 30},
                    {"type": "lunch", "name": "午餐推荐", "description": "午餐描述", "estimated_cost": 50},
                    {"type": "dinner", "name": "晚餐推荐", "description": "晚餐描述", "estimated_cost": 80}
                  ]
                }
              ],
              "weather_info": [
                {
                  "date": "YYYY-MM-DD",
                  "day_weather": "晴",
                  "night_weather": "多云",
                  "day_temp": 25,
                  "night_temp": 15,
                  "wind_direction": "南风",
                  "wind_power": "1-3级"
                }
              ],
              "overall_suggestions": "总体建议",
              "budget": {
                "total_attractions": 180,
                "total_hotels": 1200,
                "total_meals": 480,
                "total_transportation": 200,
                "total": 2060
              }
            }
            ```

            **重要提示:**
            1. weather_info数组必须包含每一天的天气信息
            2. 温度必须是纯数字(不要带°C等单位)
            3. 每天安排2-3个景点
            4. 考虑景点之间的距离和游览时间
            5. 每天必须包含早中晚三餐
            6. 提供实用的旅行建议
            7. **必须包含预算信息**:
               - 景点门票价格(ticket_price)
               - 餐饮预估费用(estimated_cost)
               - 酒店预估费用(estimated_cost)
               - 预算汇总(budget)包含各项总费用
            """;

    // ==================== Agent 实例 ====================

    private final SimpleAgent attractionAgent;
    private final SimpleAgent weatherAgent;
    private final SimpleAgent hotelAgent;
    private final SimpleAgent plannerAgent;
    private final MCPTool mcpTool;

    public MultiAgentTripPlanner(HelloAgentsLLM llm, String amapApiKey) {
        System.out.println("🔄 开始初始化多智能体旅行规划系统 (MCP 架构)...");

        // ========== 启动 MCP 服务器子进程 ==========
        // 这是 MCP 协议的核心：客户端通过 stdio 启动服务器进程，
        // 之后所有通信都通过 JSON-RPC over stdin/stdout 完成。
        //
        // 使用 Java @argument-file 语法避免 Windows 命令行长度限制。
        System.out.println("  🔌 启动 MCP 服务器子进程...");
        System.out.println("     命令: java @<classpath-file> AmapMcpServer");
        System.out.println("     协议: JSON-RPC 2.0 over stdio");

        MCPTool mcp;
        try {
            // 构建完整 classpath:
            // target/classes (项目编译产物) + Maven 依赖 classpath
            Path cpListFile = Path.of("target/mcp-classpath.txt");
            String depsClasspath = Files.readString(cpListFile).trim();
            String fullClasspath = "target/classes" + File.pathSeparator + depsClasspath;

            // 写入临时文件并用 Java @argfile 语法传递（避免 Windows CLI 长度限制）
            Path argFile = Files.createTempFile("mcp-args", ".tmp");
            Files.writeString(argFile, "-cp" + System.lineSeparator() + fullClasspath);
            argFile.toFile().deleteOnExit();

            List<String> serverCmd = List.of(
                    "java", "-Dfile.encoding=UTF-8",
                    "@" + argFile.toAbsolutePath(),
                    "com.example.agent.trip.service.AmapMcpServer");
            Map<String, String> serverEnv = Map.of("AMAP_API_KEY", amapApiKey);

            mcp = new MCPTool("amap", serverCmd, serverEnv);
        } catch (Exception e) {
            System.out.println("  ⚠️ 无法启动 MCP 子进程: " + e.getMessage());
            System.out.println("  ℹ️ 回退到直接 API 调用模式（工具功能相同）");
            mcp = null;
        }
        this.mcpTool = mcp;

        // 判断 MCP 是否可用
         boolean mcpOk = mcp != null && mcp.isInitialized();

        if (mcpOk) {
            System.out.println("  ✅ MCP 服务器已连接，发现 " + mcp.getAvailableTools().size() + " 个工具");

            // ========== 工具展开 ==========
            // MCPTool 自动将 MCP 服务器的工具展开为独立的 Tool 对象
            List<MCPWrappedTool> expandedTools = mcp.getExpandedTools();
            Map<String, MCPWrappedTool> toolMap = new LinkedHashMap<>();
            for (MCPWrappedTool t : expandedTools) {
                toolMap.put(t.name(), t);
                System.out.println("     📎 " + t.name() + " — " + t.description());
            }

            // 创建 Agent（使用 MCP 工具）
            this.attractionAgent = new SimpleAgent("景点搜索专家", llm, ATTRACTION_AGENT_PROMPT);
            if (toolMap.containsKey("amap_text_search"))
                this.attractionAgent.addTool(toolMap.get("amap_text_search"));

            this.weatherAgent = new SimpleAgent("天气查询专家", llm, WEATHER_AGENT_PROMPT);
            if (toolMap.containsKey("amap_weather"))
                this.weatherAgent.addTool(toolMap.get("amap_weather"));

            this.hotelAgent = new SimpleAgent("酒店推荐专家", llm, HOTEL_AGENT_PROMPT);
            if (toolMap.containsKey("amap_text_search"))
                this.hotelAgent.addTool(toolMap.get("amap_text_search"));

            this.plannerAgent = new SimpleAgent("行程规划专家", llm, PLANNER_AGENT_PROMPT);
            if (toolMap.containsKey("amap_text_search"))
                this.plannerAgent.addTool(toolMap.get("amap_text_search"));

            System.out.println("✅ 多智能体系统初始化完成 (MCP 架构)");
        } else {
            // MCP 不可用，使用直接 API 调用模式
            System.out.println("  ℹ️ 使用直接 API 调用模式");
            AmapService amap = new AmapService(amapApiKey);
            Tool textSearchTool = createTextSearchTool(amap);
            Tool weatherTool = createWeatherTool(amap);

            this.attractionAgent = new SimpleAgent("景点搜索专家", llm, ATTRACTION_AGENT_PROMPT);
            this.attractionAgent.addTool(textSearchTool);
            this.weatherAgent = new SimpleAgent("天气查询专家", llm, WEATHER_AGENT_PROMPT);
            this.weatherAgent.addTool(weatherTool);
            this.hotelAgent = new SimpleAgent("酒店推荐专家", llm, HOTEL_AGENT_PROMPT);
            this.hotelAgent.addTool(textSearchTool);
            this.plannerAgent = new SimpleAgent("行程规划专家", llm, PLANNER_AGENT_PROMPT);
            this.plannerAgent.addTool(textSearchTool);

            System.out.println("✅ 多智能体系统初始化完成 (直接 API 模式)");
        }

        System.out.println("   景点搜索 Agent: " + this.attractionAgent.listTools().size() + " 个工具");
        System.out.println("   天气查询 Agent: " + this.weatherAgent.listTools().size() + " 个工具");
        System.out.println("   酒店推荐 Agent: " + this.hotelAgent.listTools().size() + " 个工具");
        System.out.println("   行程规划 Agent: " + this.plannerAgent.listTools().size() + " 个工具");
    }

    /** 关闭 MCP 连接，终止服务器子进程。 */
    public void close() {
        if (mcpTool != null) {
            System.out.println("🔌 关闭 MCP 服务器连接...");
            mcpTool.close();
        }
    }

    /** 获取 MCPTool 实例，用于学习/调试 MCP 协议。 */
    public MCPTool getMcpTool() {
        return mcpTool;
    }

    // ==================== 核心管线 ====================

    /**
     * 4 步管线：景点 → 天气 → 酒店 → 规划
     * 每个 Agent 自主决定是否/如何调用它的工具。
     */
    public TripPlan planTrip(TripRequest request) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🚀 开始多智能体协作规划旅行...");
        System.out.println("目的地: " + request.city());
        System.out.println("日期: " + request.start_date() + " 至 " + request.end_date());
        System.out.println("天数: " + request.travel_days() + "天");
        System.out.println("偏好: " + (request.preferences() != null
                ? String.join(", ", request.preferences()) : "无"));
        System.out.println("=".repeat(60) + "\n");

        try {
            // 步骤1: 景点搜索 Agent
            System.out.println("📍 步骤1: 景点搜索 Agent 工作中...");
            String attractionQuery = buildAttractionQuery(request);
            String attractionResponse = attractionAgent.run(attractionQuery);
            System.out.println("   景点搜索结果: " + preview(attractionResponse) + "\n");

            // 步骤2: 天气查询 Agent
            System.out.println("🌤️  步骤2: 天气查询 Agent 工作中...");
            String weatherQuery = "请查询" + request.city() + "的天气信息";
            String weatherResponse = weatherAgent.run(weatherQuery);
            System.out.println("   天气查询结果: " + preview(weatherResponse) + "\n");

            // 步骤3: 酒店推荐 Agent
            System.out.println("🏨 步骤3: 酒店推荐 Agent 工作中...");
            String hotelQuery = "请搜索" + request.city() + "的"
                    + (request.accommodation() != null ? request.accommodation() : "")
                    + "酒店";
            String hotelResponse = hotelAgent.run(hotelQuery);
            System.out.println("   酒店搜索结果: " + preview(hotelResponse) + "\n");

            // 步骤4: 行程规划 Agent 整合
            System.out.println("📋 步骤4: 行程规划 Agent 整合中...");
            String plannerQuery = buildPlannerQuery(request,
                    attractionResponse, weatherResponse, hotelResponse);
            String plannerResponse = plannerAgent.run(plannerQuery);
            System.out.println("   行程规划结果: " + preview(plannerResponse, 300) + "\n");

            TripPlan plan = parseResponse(plannerResponse, request);

            System.out.println("=".repeat(60));
            System.out.println("✅ 旅行计划生成完成!");
            System.out.println("=".repeat(60) + "\n");
            return plan;

        } catch (Exception e) {
            System.out.println("❌ 生成旅行计划失败: " + e.getMessage());
            e.printStackTrace();
            return createFallbackPlan(request);
        }
    }

    // ==================== 查询构建 ====================

    /**
     * 构建景点搜索查询 —— 直接包含工具调用标记。
     * Agent 收到消息后会直接执行 [TOOL_CALL:...]
     */
    private String buildAttractionQuery(TripRequest request) {
        String keywords;
        if (request.preferences() != null && !request.preferences().isEmpty()) {
            keywords = request.preferences().get(0);
        } else {
            keywords = "景点";
        }
        return "请使用amap_text_search工具搜索" + request.city() + "的"
                + keywords + "相关景点。\n"
                + "[TOOL_CALL:amap_text_search:keywords=" + keywords
                + ",city=" + request.city() + "]";
    }

    /**
     * 构建行程规划查询 —— 把前 3 个 Agent 的结果 + 用户请求全部传给 Planner
     */
    private String buildPlannerQuery(TripRequest request,
                                      String attractions, String weather, String hotels) {
        String pref = request.preferences() != null
                ? String.join(", ", request.preferences()) : "无";

        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下信息生成").append(request.city())
          .append("的").append(request.travel_days()).append("天旅行计划:\n\n");

        sb.append("**基本信息:**\n");
        sb.append("- 城市: ").append(request.city()).append("\n");
        sb.append("- 日期: ").append(request.start_date())
          .append(" 至 ").append(request.end_date()).append("\n");
        sb.append("- 天数: ").append(request.travel_days()).append("天\n");
        sb.append("- 交通方式: ").append(request.transportation()).append("\n");
        sb.append("- 住宿: ").append(request.accommodation()).append("\n");
        sb.append("- 偏好: ").append(pref).append("\n\n");

        sb.append("**景点信息:**\n").append(attractions).append("\n\n");
        sb.append("**天气信息:**\n").append(weather).append("\n\n");
        sb.append("**酒店信息:**\n").append(hotels).append("\n\n");

        sb.append("**要求:**\n");
        sb.append("1. 每天安排2-3个景点\n");
        sb.append("2. 每天必须包含早中晚三餐\n");
        sb.append("3. 每天推荐一个具体的酒店(从酒店信息中选择)\n");
        sb.append("4. 考虑景点之间的距离和交通方式\n");
        sb.append("5. 返回完整的JSON格式数据\n");

        if (request.free_text_input() != null && !request.free_text_input().isEmpty()) {
            sb.append("\n**用户额外要求（如果现有数据不够，可以用 amap_text_search 工具补充查询）:**\n");
            sb.append(request.free_text_input());
        }

        return sb.toString();
    }

    // ==================== 直接 Tool 创建（MCP 不可用时的回退方案） ====================

    private Tool createTextSearchTool(AmapService amap) {
        return new Tool("amap_text_search",
                "高德地图POI搜索。根据关键词和城市搜索地点。") {
            @Override
            public String run(Map<String, Object> params) {
                String keywords = String.valueOf(params.getOrDefault("keywords", "景点"));
                String city = String.valueOf(params.getOrDefault("city", "北京"));
                return amap.textSearchRaw(keywords, city);
            }
            @Override
            public List<ToolParameter> getParameters() {
                return List.of(
                        new ToolParameter("keywords", "string", "搜索关键词", true, null),
                        new ToolParameter("city", "string", "城市名", false, "北京"));
            }
        };
    }

    private Tool createWeatherTool(AmapService amap) {
        return new Tool("amap_weather", "高德地图天气查询。查询指定城市的天气信息。") {
            @Override
            public String run(Map<String, Object> params) {
                String city = String.valueOf(params.getOrDefault("city", "北京"));
                return amap.weatherRaw(city);
            }
            @Override
            public List<ToolParameter> getParameters() {
                return List.of(
                        new ToolParameter("city", "string", "城市名", false, "北京"));
            }
        };
    }

    // ==================== 响应解析 ====================

    @SuppressWarnings("unchecked")
    private TripPlan parseResponse(String response, TripRequest request) {
        try {
            String json = extractJson(response);
            Map<String, Object> data = GSON.fromJson(json, Map.class);
            return convertToTripPlan(data, request);
        } catch (Exception e) {
            System.out.println("⚠️  解析响应失败: " + e.getMessage());
            System.out.println("   将使用备用方案生成计划");
            return createFallbackPlan(request);
        }
    }

    private String extractJson(String response) {
        if (response.contains("```json")) {
            int start = response.indexOf("```json") + 7;
            int end = response.indexOf("```", start);
            if (end > start) return response.substring(start, end).trim();
        }
        if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.indexOf("```", start);
            if (end > start) return response.substring(start, end).trim();
        }
        int jsonStart = response.indexOf("{");
        int jsonEnd = response.lastIndexOf("}");
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1);
        }
        throw new IllegalArgumentException("响应中未找到 JSON 数据");
    }

    @SuppressWarnings("unchecked")
    private TripPlan convertToTripPlan(Map<String, Object> data, TripRequest request) {
        String city = str(data, "city", request.city());
        String startDate = str(data, "start_date", request.start_date());
        String endDate = str(data, "end_date", request.end_date());

        List<DayPlan> days = new ArrayList<>();
        if (data.get("days") instanceof List<?> dl) {
            for (int i = 0; i < dl.size(); i++) {
                if (dl.get(i) instanceof Map<?, ?> dm) {
                    days.add(convertDay((Map<String, Object>) dm));
                }
            }
        }

        List<WeatherInfo> weather = new ArrayList<>();
        if (data.get("weather_info") instanceof List<?> wl) {
            for (Object w : wl) {
                if (w instanceof Map<?, ?> wm) {
                    Map<String, Object> wmap = (Map<String, Object>) wm;
                    weather.add(new WeatherInfo(
                            str(wmap, "date", ""),
                            str(wmap, "day_weather", ""),
                            str(wmap, "night_weather", ""),
                            str(wmap, "day_temp", ""),
                            str(wmap, "night_temp", ""),
                            str(wmap, "wind_direction", ""),
                            str(wmap, "wind_power", "")));
                }
            }
        }

        String suggestions = str(data, "overall_suggestions", "");
        Budget budget = null;
        if (data.get("budget") instanceof Map<?, ?> bm) {
            Map<String, Object> bmap = (Map<String, Object>) bm;
            budget = new Budget(
                    num(bmap, "total_attractions"),
                    num(bmap, "total_hotels"),
                    num(bmap, "total_meals"),
                    num(bmap, "total_transportation"),
                    num(bmap, "total"));
        }

        return new TripPlan(city, startDate, endDate, days, weather, suggestions, budget);
    }

    @SuppressWarnings("unchecked")
    private DayPlan convertDay(Map<String, Object> dm) {
        Hotel hotel = null;
        if (dm.get("hotel") instanceof Map<?, ?> hm) {
            Map<String, Object> hm2 = (Map<String, Object>) hm;
            hotel = new Hotel(
                    str(hm2, "name", ""),
                    str(hm2, "address", ""),
                    loc(hm2.get("location")),
                    str(hm2, "price_range", ""),
                    num(hm2, "rating"),
                    str(hm2, "distance", ""),
                    str(hm2, "type", ""),
                    num(hm2, "estimated_cost"));
        }

        List<Attraction> attrs = new ArrayList<>();
        if (dm.get("attractions") instanceof List<?> al) {
            for (Object a : al) {
                if (a instanceof Map<?, ?> am) {
                    Map<String, Object> am2 = (Map<String, Object>) am;
                    attrs.add(new Attraction(
                            str(am2, "name", ""),
                            str(am2, "address", ""),
                            loc(am2.get("location")),
                            str(am2, "visit_duration", ""),
                            str(am2, "description", ""),
                            str(am2, "category", ""),
                            num(am2, "rating"),
                            null,  // photos
                            str(am2, "poi_id", ""),
                            str(am2, "image_url", ""),
                            num(am2, "ticket_price")));
                }
            }
        }

        List<Meal> meals = new ArrayList<>();
        if (dm.get("meals") instanceof List<?> ml) {
            for (Object m : ml) {
                if (m instanceof Map<?, ?> mm) {
                    Map<String, Object> mm2 = (Map<String, Object>) mm;
                    MealType type = MealType.lunch;
                    try {
                        type = MealType.valueOf(str(mm2, "type", "lunch").toLowerCase());
                    } catch (Exception ignored) {}
                    meals.add(new Meal(type,
                            str(mm2, "name", ""),
                            str(mm2, "address", ""),
                            loc(mm2.get("location")),
                            str(mm2, "description", ""),
                            num(mm2, "estimated_cost")));
                }
            }
        }

        return new DayPlan(
                str(dm, "date", ""),
                toInt(dm, "day_index"),
                str(dm, "description", ""),
                str(dm, "transportation", ""),
                str(dm, "accommodation", ""),
                hotel, attrs, meals);
    }

    @SuppressWarnings("unchecked")
    private Location loc(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return new Location(num((Map<String, Object>) m, "longitude"),
                    num((Map<String, Object>) m, "latitude"));
        }
        return new Location(116.4, 39.9);
    }

    // ==================== 备用方案 ====================

    private TripPlan createFallbackPlan(TripRequest request) {
        LocalDate start = LocalDate.parse(request.start_date(), DateTimeFormatter.ISO_LOCAL_DATE);
        List<DayPlan> days = new ArrayList<>();
        for (int i = 0; i < request.travel_days(); i++) {
            LocalDate d = start.plusDays(i);
            days.add(new DayPlan(
                    d.format(DateTimeFormatter.ISO_LOCAL_DATE), i,
                    "第" + (i + 1) + "天行程", "公共交通", "经济型",
                    null,
                    List.of(
                            new Attraction(request.city() + "景点1", request.city() + "市",
                                    new Location(116.4, 39.9), "120分钟", "著名景点",
                                    "景点", null, null, null, null, null),
                            new Attraction(request.city() + "景点2", request.city() + "市",
                                    new Location(116.42, 39.92), "90分钟", "热门景点",
                                    "景点", null, null, null, null, null)),
                    List.of(
                            new Meal(MealType.breakfast, "早餐", "", null, "当地特色早餐", 30.0),
                            new Meal(MealType.lunch, "午餐", "", null, "当地特色午餐", 50.0),
                            new Meal(MealType.dinner, "晚餐", "", null, "当地特色晚餐", 80.0))));
        }
        return new TripPlan(request.city(), request.start_date(), request.end_date(),
                days, List.of(),
                "这是为您规划的" + request.city() + request.travel_days() + "日游行程。", null);
    }

    // ==================== 辅助方法 ====================

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())
                && !Double.isInfinite(n.doubleValue())) {
            return String.valueOf(n.longValue());  // 25.0 → "25"
        }
        return String.valueOf(v);
    }

    private static Double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) {}
        }
        return null;
    }

    private static int toInt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static String preview(String text) {
        return preview(text, 200);
    }

    private static String preview(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "(空)";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
