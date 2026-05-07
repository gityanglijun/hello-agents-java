package com.example.agent.app;

import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolParameter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 天气查询工具 — 调用 wttr.in 免费 API 获取实时天气。
 * 纯 Java 实现，无需 MCP 协议，可作为 Python 天气服务器的降级方案。
 */
public class WeatherTool extends Tool {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Map<String, String> CITY_MAP = new LinkedHashMap<>();
    static {
        CITY_MAP.put("北京", "Beijing");
        CITY_MAP.put("上海", "Shanghai");
        CITY_MAP.put("广州", "Guangzhou");
        CITY_MAP.put("深圳", "Shenzhen");
        CITY_MAP.put("杭州", "Hangzhou");
        CITY_MAP.put("成都", "Chengdu");
        CITY_MAP.put("重庆", "Chongqing");
        CITY_MAP.put("武汉", "Wuhan");
        CITY_MAP.put("西安", "Xi'an");
        CITY_MAP.put("南京", "Nanjing");
        CITY_MAP.put("天津", "Tianjin");
        CITY_MAP.put("苏州", "Suzhou");
    }

    public WeatherTool() {
        super("weather",
              "天气查询工具。支持 get_weather(查询指定城市天气), " +
              "list_supported_cities(列出支持的城市), get_server_info(获取工具信息)。" +
              "支持中文城市名：" + String.join(", ", CITY_MAP.keySet()));
    }

    @Override
    public String run(Map<String, Object> parameters) {
        String action = (String) parameters.getOrDefault("action", "get_weather");
        return switch (action) {
            case "get_weather"            -> handleGetWeather(parameters);
            case "list_supported_cities"  -> handleListCities();
            case "get_server_info"        -> handleServerInfo();
            default -> "❌ 不支持的操作: " + action;
        };
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("action", "string",
                "操作类型: get_weather, list_supported_cities, get_server_info",
                false, "get_weather"),
            new ToolParameter("city", "string",
                "城市名（中文，如 北京、上海）", false, null)
        );
    }

    // ==================== handlers ====================

    private String handleGetWeather(Map<String, Object> params) {
        String city = (String) params.getOrDefault("city", "北京");
        try {
            Map<String, Object> data = fetchWeather(city);
            return GSON.toJson(data);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("city", city);
            return GSON.toJson(error);
        }
    }

    private String handleListCities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cities", new ArrayList<>(CITY_MAP.keySet()));
        result.put("count", CITY_MAP.size());
        return GSON.toJson(result);
    }

    private String handleServerInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "Weather Tool (Java Native)");
        info.put("version", "1.0.0");
        info.put("protocol", "HTTP (wttr.in)");
        info.put("tools", List.of("get_weather", "list_supported_cities", "get_server_info"));
        return GSON.toJson(info);
    }

    // ==================== wttr.in API ====================

    @SuppressWarnings("unchecked")
    static Map<String, Object> fetchWeather(String city) throws Exception {
        String cityEn = CITY_MAP.getOrDefault(city, city);
        String url = "https://wttr.in/" + cityEn + "?format=j1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString());

        Map<String, Object> root = GSON.fromJson(response.body(),
                new TypeToken<Map<String, Object>>() {}.getType());
        Map<String, Object> current = ((List<Map<String, Object>>)
                root.get("current_condition")).get(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        result.put("temperature", toDouble(current.get("temp_C")));
        result.put("feels_like", toDouble(current.get("FeelsLikeC")));
        result.put("humidity", toInt(current.get("humidity")));
        result.put("condition", ((List<Map<String, Object>>)
                current.get("weatherDesc")).get(0).get("value"));
        result.put("wind_speed",
                Math.round(toDouble(current.get("windspeedKmph")) / 3.6 * 10.0) / 10.0);
        result.put("visibility", toDouble(current.get("visibility")));
        result.put("timestamp",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return result;
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }

    private static int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
