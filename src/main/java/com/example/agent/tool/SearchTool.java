package com.example.agent.tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.agent.LoadDotenvUtil;
import com.example.agent.client.SerpApiHttpClient;
import com.example.agent.client.TavilyHttpClient;

public class SearchTool extends Tool {

    public enum Backend { HYBRID, TAVILY, SERPAPI }

    private final Backend backend;
    private final List<String> availableBackends;

    public SearchTool(String backend) {
        super("search", "一个智能网页搜索引擎。支持混合搜索模式，自动选择最佳搜索源。");
        this.backend = backend != null ? Backend.valueOf(backend.toUpperCase()) : Backend.HYBRID;
        this.availableBackends = new ArrayList<>();
        setupBackends();
    }

    public SearchTool() {
        this(null);
    }

    private void setupBackends() {
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();

        if (env.getOrDefault("TAVILY_API_KEY", System.getenv("TAVILY_API_KEY")) != null) {
            availableBackends.add("tavily");
            System.out.println("✅ Tavily搜索源已启用");
        } else {
            // 检查 SerpApi
            if (env.getOrDefault("SERPAPI_API_KEY", System.getenv("SERPAPI_API_KEY")) != null) {
                availableBackends.add("serpapi");
                System.out.println("✅ SerpApi搜索源已启用");
            }
        }

        if (availableBackends.isEmpty()) {
            System.out.println("⚠️ 没有可用的搜索源，请配置TAVILY_API_KEY或SERPAPI_API_KEY");
        } else {
            System.out.println("🔧 可用搜索源: " + String.join(", ", availableBackends));
        }
    }

    @Override
    public String run(Map<String, Object> parameters) {
        String query = parameters.containsKey("query")
                ? (String) parameters.get("query")
                : (String) parameters.getOrDefault("input", "");

        if (query == null || query.isBlank()) {
            return "❌ 搜索查询不能为空";
        }

        if (availableBackends.isEmpty()) {
            return "没有可用的搜索源，请配置API密钥:\n"
                    + "1. Tavily API: 设置环境变量 TAVILY_API_KEY\n"
                    + "   获取地址: https://tavily.com/\n"
                    + "2. SerpAPI: 设置环境变量 SERPAPI_API_KEY\n"
                    + "   获取地址: https://serpapi.com/";
        }

        System.out.println("🔍 开始搜索: " + query);

        // 根据 backend 选择搜索源
        List<String> sources = new ArrayList<>();
        if (backend == Backend.HYBRID) {
            sources.addAll(availableBackends);
        } else if (backend == Backend.TAVILY && availableBackends.contains("tavily")) {
            sources.add("tavily");
        } else if (backend == Backend.SERPAPI && availableBackends.contains("serpapi")) {
            sources.add("serpapi");
        } else {
            sources.addAll(availableBackends);
        }

        for (String source : sources) {
            try {
                String result;
                if ("tavily".equals(source)) {
                    result = TavilyHttpClient.search(query);
                } else {
                    result = SerpApiHttpClient.search(query);
                }
                if (result != null && !result.contains("错误") && !result.contains("未找到")) {
                    return "📊 " + (source.equals("tavily") ? "Tavily AI" : "SerpApi Google") + "搜索结果:\n\n" + result;
                }
            } catch (Exception e) {
                System.out.println("⚠️ " + source + " 搜索失败: " + e.getMessage());
            }
        }

        return "❌ 所有搜索源都失败了，请检查网络连接和API密钥配置";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(new ToolParameter("query", "string", "搜索关键词"));
    }
}
