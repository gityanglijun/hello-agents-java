package com.example.agent.tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.agent.LoadDotenvUtil;
import com.example.agent.client.SerpApiHttpClient;
import com.example.agent.client.TavilyHttpClient;

public class MyAdvancedSearchTool {

    private final List<String> searchSources;

    public MyAdvancedSearchTool() {
        this.searchSources = new ArrayList<>();
        setupSearchSources();
    }

    private void setupSearchSources() {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();

        if (env.getOrDefault("TAVILY_API_KEY", System.getenv("TAVILY_API_KEY")) != null) {
            searchSources.add("tavily");
            System.out.println("✅ Tavily搜索源已启用");
        }

        if (env.getOrDefault("SERPAPI_API_KEY", System.getenv("SERPAPI_API_KEY")) != null) {
            searchSources.add("serpapi");
            System.out.println("✅ SerpApi搜索源已启用");
        }

        if (!searchSources.isEmpty()) {
            System.out.println("🔧 可用搜索源: " + String.join(", ", searchSources));
        } else {
            System.out.println("⚠️ 没有可用的搜索源，请配置API密钥");
        }
    }

    public String search(String query) {
        if (query == null || query.isBlank()) {
            return "❌ 错误:搜索查询不能为空";
        }

        if (searchSources.isEmpty()) {
            return "没有可用的搜索源，请配置API密钥:\n"
                    + "1. Tavily API: 设置环境变量 TAVILY_API_KEY\n"
                    + "   获取地址: https://tavily.com/\n"
                    + "2. SerpAPI: 设置环境变量 SERPAPI_API_KEY\n"
                    + "   获取地址: https://serpapi.com/";
        }

        System.out.println("🔍 开始智能搜索: " + query);

        for (String source : searchSources) {
            try {
                String result;
                if ("tavily".equals(source)) {
                    result = TavilyHttpClient.search(query);
                } else {
                    result = SerpApiHttpClient.search(query);
                }

                if (result != null && !result.contains("未找到")) {
                    String label = "tavily".equals(source) ? "Tavily AI搜索结果" : "SerpApi Google搜索结果";
                    return "📊 " + label + ":\n\n" + result;
                }
            } catch (Exception e) {
                System.out.println("⚠️ " + source + " 搜索失败: " + e.getMessage());
            }
        }

        return "❌ 所有搜索源都失败了，请检查网络连接和API密钥配置";
    }

    public static ToolRegistry createAdvancedSearchRegistry() {
        ToolRegistry registry = new ToolRegistry();
        MyAdvancedSearchTool searchTool = new MyAdvancedSearchTool();
        registry.registerFunction(
                "advanced_search",
                "高级搜索工具，整合Tavily和SerpAPI多个搜索源，提供更全面的搜索结果",
                searchTool::search
        );
        return registry;
    }
}
