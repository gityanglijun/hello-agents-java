package com.example.agent.game;

import com.example.agent.LoadDotenvUtil;
import com.example.agent.client.SerpApiHttpClient;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolParameter;
import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

public class GameImageSearchTool extends Tool {

    private static final Gson GSON = new Gson();
    private final String serpapiApiKey;

    public GameImageSearchTool() {
        super("search_game_images",
              "搜索游戏截图和图片。输入游戏名称，返回该游戏的相关图片URL列表（包含原图URL、缩略图、标题、来源）。");
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();
        this.serpapiApiKey = env.getOrDefault("SERPAPI_API_KEY",
                System.getenv("SERPAPI_API_KEY") != null ? System.getenv("SERPAPI_API_KEY") : "");
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("game_name", "string", "游戏名称，用于搜索相关截图和图片", true),
            new ToolParameter("max_results", "integer", "返回图片的最大数量，默认5", false, 5)
        );
    }

    @Override
    public String run(Map<String, Object> parameters) {
        String gameName = (String) parameters.get("game_name");
        if (gameName == null || gameName.isBlank()) {
            return GSON.toJson(Map.of("error", "game_name 参数不能为空"));
        }

        int maxResults = 5;
        Object maxResultsObj = parameters.get("max_results");
        if (maxResultsObj != null) {
            if (maxResultsObj instanceof Number) {
                maxResults = ((Number) maxResultsObj).intValue();
            } else {
                try { maxResults = Integer.parseInt(maxResultsObj.toString()); } catch (NumberFormatException ignored) {}
            }
        }

        if (serpapiApiKey.isBlank()) {
            return GSON.toJson(Map.of("error", "SERPAPI_API_KEY 未配置，无法执行图片搜索"));
        }

        String query = gameName + " game screenshot";
        List<Map<String, String>> images = SerpApiHttpClient.searchImages(query, serpapiApiKey, maxResults);

        if (images.isEmpty()) {
            return GSON.toJson(Map.of(
                "game_name", gameName,
                "images", List.of(),
                "message", "未找到相关图片"
            ));
        }

        return GSON.toJson(Map.of(
            "game_name", gameName,
            "count", images.size(),
            "images", images
        ));
    }
}
