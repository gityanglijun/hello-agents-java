package com.example.agent.trip.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 高德地图 REST API 服务 — 直接 HTTP 调用，无需 MCP/Python。
 *
 * API 文档: https://lbs.amap.com/api/webservice/summary
 */
public class AmapService {

    private static final String BASE_URL = "https://restapi.amap.com/v3";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    private final String apiKey;

    public AmapService(String apiKey) {
        this.apiKey = apiKey;
    }

    /** POI 关键词搜索 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> textSearch(String keywords, String city) {
        return get("/place/text", Map.of(
                "keywords", keywords,
                "city", city != null ? city : "北京",
                "offset", "10"
        ));
    }

    /** 天气查询 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> weather(String city) {
        return get("/weather/weatherInfo", Map.of(
                "city", city != null ? city : "北京",
                "extensions", "all"
        ));
    }

    /** 逆地理编码（坐标→地址） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> geocode(String address, String city) {
        return get("/geocode/geo", Map.of(
                "address", address,
                "city", city != null ? city : "北京"
        ));
    }

    /** 步行路线规划（按地址） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> walkingDirection(String origin, String destination) {
        return get("/direction/walking", Map.of(
                "origin", origin,
                "destination", destination
        ));
    }

    /** 驾车路线规划 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> drivingDirection(String origin, String destination) {
        return get("/direction/driving", Map.of(
                "origin", origin,
                "destination", destination
        ));
    }

    /** 公交路线规划 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> transitDirection(String origin, String destination, String city) {
        return get("/direction/transit/integrated", Map.of(
                "origin", origin,
                "destination", destination,
                "city", city != null ? city : "北京"
        ));
    }

    /** 返回原始 JSON 字符串，供 Agent 直接使用 */
    public String textSearchRaw(String keywords, String city) {
        Map<String, Object> result = textSearch(keywords, city);
        return GSON.toJson(result);
    }

    public String weatherRaw(String city) {
        Map<String, Object> result = weather(city);
        return GSON.toJson(result);
    }

    // ==================== 内部 HTTP ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, Map<String, String> params) {
        try {
            StringBuilder url = new StringBuilder(BASE_URL + path + "?key=" + apiKey);
            for (var entry : params.entrySet()) {
                url.append("&")
                   .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                   .append("=")
                   .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                Map<String, Object> result = GSON.fromJson(resp.body(),
                        new TypeToken<Map<String, Object>>() {}.getType());
                return result;
            }

            return Map.of("status", "error", "code", resp.statusCode(),
                    "message", resp.body());

        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}
