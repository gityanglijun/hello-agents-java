package com.example.agent.trip.model;

import java.util.List;
import java.util.Map;

/**
 * 旅行助手数据模型 — 对应 Python backend/app/models/schemas.py。
 * 使用 Java records 对齐 Pydantic models，Jackson 序列化。
 */
public final class TripModels {

    // ==================== 基础类型 ====================

    public record Location(double longitude, double latitude) {}

    public record Attraction(
            String name,
            String address,
            Location location,
            String visit_duration,
            String description,
            String category,
            Double rating,
            List<String> photos,
            String poi_id,
            String image_url,
            Double ticket_price
    ) {}

    public enum MealType { breakfast, lunch, dinner, snack }

    public record Meal(
            MealType type,
            String name,
            String address,
            Location location,
            String description,
            Double estimated_cost
    ) {}

    public record Hotel(
            String name,
            String address,
            Location location,
            String price_range,
            Double rating,
            String distance,
            String type,
            Double estimated_cost
    ) {}

    public record WeatherInfo(
            String date,
            String day_weather,
            String night_weather,
            String day_temp,
            String night_temp,
            String wind_direction,
            String wind_power
    ) {}

    public record Budget(
            Double total_attractions,
            Double total_hotels,
            Double total_meals,
            Double total_transportation,
            Double total
    ) {}

    // ==================== 核心行程 ====================

    public record DayPlan(
            String date,
            Integer day_index,
            String description,
            String transportation,
            String accommodation,
            Hotel hotel,
            List<Attraction> attractions,
            List<Meal> meals
    ) {}

    public record TripPlan(
            String city,
            String start_date,
            String end_date,
            List<DayPlan> days,
            List<WeatherInfo> weather_info,
            String overall_suggestions,
            Budget budget
    ) {}

    // ==================== 请求/响应 ====================

    public record TripRequest(
            String city,
            String start_date,
            String end_date,
            Integer travel_days,
            String transportation,
            String accommodation,
            List<String> preferences,
            String free_text_input
    ) {}

    public record TripPlanResponse(
            boolean success,
            String message,
            TripPlan data
    ) {
        public static TripPlanResponse ok(TripPlan data) {
            return new TripPlanResponse(true, "行程规划成功", data);
        }
        public static TripPlanResponse error(String msg) {
            return new TripPlanResponse(false, msg, null);
        }
    }

    public record POISearchResult(
            String id,
            String name,
            String address,
            Location location,
            String category,
            Double rating
    ) {}

    public record POISearchResponse(
            boolean success,
            String message,
            List<POISearchResult> data
    ) {}

    public record RouteRequest(
            String origin,
            String destination,
            String city,
            String route_type
    ) {}

    public record RouteResponse(
            boolean success,
            String message,
            Map<String, Object> data
    ) {}

    public record WeatherResponse(
            boolean success,
            String message,
            WeatherInfo data
    ) {}

    public record ErrorResponse(String error, String detail) {}
}
