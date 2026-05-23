package com.example.agent.game;

import com.example.agent.LoadDotenvUtil;
import com.example.agent.client.SerpApiHttpClient;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolParameter;
import com.google.gson.Gson;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GameImageSearchTool extends Tool {

    private static final Gson GSON = new Gson();
    private final String serpapiApiKey;
    private final String searxngUrl;

    public GameImageSearchTool() {
        super("search_game_images",
              "搜索游戏截图和图片。自动多策略搜索（中英文关键词组合），SerpApi 搜不到自动降级 SearxNG 再试，返回去重后的图片URL列表。");
        Map<String, String> env = LoadDotenvUtil.loadEnvFile();
        this.serpapiApiKey = env.getOrDefault("SERPAPI_API_KEY",
                System.getenv("SERPAPI_API_KEY") != null ? System.getenv("SERPAPI_API_KEY") : "");

        // SearxNG URL：优先用 SEARXNG_URL，否则从 GAME_MCP_URL 推导（同主机 8088 端口）
        String searxng = env.getOrDefault("SEARXNG_URL",
                System.getenv("SEARXNG_URL") != null ? System.getenv("SEARXNG_URL") : "");
        if (searxng.isBlank()) {
            String mcpUrl = env.getOrDefault("GAME_MCP_URL",
                    System.getenv("GAME_MCP_URL") != null ? System.getenv("GAME_MCP_URL") : "");
            if (!mcpUrl.isBlank()) {
                // http://103.244.89.244:8080 → http://103.244.89.244:8088
                searxng = mcpUrl.replaceFirst(":\\d+$", ":8088");
            }
        }
        this.searxngUrl = searxng;
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
            new ToolParameter("game_name", "string", "游戏名称，支持中英文混合（如'艾尔登法环/Elden Ring'）", true),
            new ToolParameter("max_results", "integer", "返回图片的最大数量，默认6", false, 6)
        );
    }

    @Override
    public String run(Map<String, Object> parameters) {
        String gameName = (String) parameters.get("game_name");
        if (gameName == null || gameName.isBlank()) {
            return GSON.toJson(Map.of("error", "game_name 参数不能为空"));
        }

        int maxResults = 6;
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

        // 1. 从游戏名中提取短名称和英文名
        String shortName = extractShortName(gameName);
        String englishName = extractEnglishName(gameName);

        // 2. 构建多策略查询列表（从精确到宽泛）
        List<String> queries = buildQueryStrategies(shortName, englishName);

        // 3. 逐个尝试，收集去重结果
        Set<String> seenUrls = new LinkedHashSet<>();
        List<Map<String, String>> allImages = new ArrayList<>();
        List<String> triedQueries = new ArrayList<>();
        String bestQuery = null;

        for (String query : queries) {
            if (allImages.size() >= maxResults) break;

            List<Map<String, String>> batch = SerpApiHttpClient.searchImages(
                    query, serpapiApiKey, Math.min(maxResults, 8));
            triedQueries.add(query);

            int added = 0;
            for (Map<String, String> img : batch) {
                String url = img.get("original");
                if (url != null && !url.isBlank() && seenUrls.add(url)) {
                    allImages.add(img);
                    added++;
                }
            }

            if (added > 0 && bestQuery == null) {
                bestQuery = query;
            }
        }

        // 4. 截断到 maxResults
        if (allImages.size() > maxResults) {
            allImages = allImages.subList(0, maxResults);
        }

        // 4. SerpApi 未命中 → SearxNG 兜底
        int searxngAdded = 0;
        if (allImages.isEmpty() && !searxngUrl.isBlank()) {
            System.err.println("[GameImageSearch] SerpApi 未命中，降级到 SearxNG...");

            // 用英文名 + "game screenshot" 试一次（SearxNG 聚合多引擎，不需要像 SerpApi 那样多策略）
            String searxngQuery;
            if (englishName != null) {
                searxngQuery = englishName + " game screenshot";
            } else if (!shortName.isBlank()) {
                searxngQuery = shortName + " 游戏截图";
            } else {
                searxngQuery = gameName + " screenshot";
            }

            List<Map<String, String>> searxngResults = SearxNGImageClient.searchImages(
                    searxngUrl, searxngQuery, maxResults);
            triedQueries.add("searxng:" + searxngQuery);

            for (Map<String, String> img : searxngResults) {
                String url = img.get("original");
                if (url != null && !url.isBlank() && seenUrls.add(url)) {
                    allImages.add(img);
                    searxngAdded++;
                }
            }

            if (searxngAdded > 0 && bestQuery == null) {
                bestQuery = "searxng:" + searxngQuery;
            }
        }

        if (allImages.isEmpty()) {
            return GSON.toJson(Map.of(
                "game_name", gameName,
                "short_name", shortName,
                "english_name", englishName != null ? englishName : "",
                "images", List.of(),
                "tried_queries", triedQueries,
                "message", "SerpApi " + queries.size() + " 条策略 + SearxNG 均未找到图片。该游戏可能过于冷门，建议手动补充截图。"
            ));
        }

        if (allImages.size() > maxResults) {
            allImages = allImages.subList(0, maxResults);
        }

        return GSON.toJson(Map.of(
            "game_name", gameName,
            "short_name", shortName,
            "english_name", englishName != null ? englishName : "",
            "count", allImages.size(),
            "best_query", bestQuery != null ? bestQuery : "",
            "tried_queries", triedQueries,
            "images", allImages
        ));
    }

    // ==================== 名称提取 ====================

    /** 提取短标题：去掉括号备注、版本号、英文名部分 */
    private static String extractShortName(String title) {
        if (title == null || title.isBlank()) return "";
        // 去掉括号内的版本/描述信息
        String cleaned = title
                .replaceAll("[（(]?(全DLC|数字豪华版|更新|v\\d+\\.\\d+)[^）)]*[）)]?", "")
                .replaceAll("[（(][^）)]*[）)]", "")
                .trim();
        if (cleaned.isBlank()) return "";
        // 切掉尾部英文（如果中文在前，后面跟空格+英文）
        cleaned = cleaned.replaceAll("\\s+[A-Za-z0-9][A-Za-z0-9\\s:：\\-._]{2,}$", "");
        // 切掉斜杠及之后内容
        cleaned = cleaned.replaceAll("[/／].*", "");
        return cleaned.trim().isBlank() ? title.split("[/／]")[0].trim() : cleaned.trim();
    }

    /** 提取英文名：支持多种标题格式 */
    private static String extractEnglishName(String title) {
        if (title == null || title.isBlank()) return null;

        // 格式1: "中文（English Name）" 或 "中文(English Name)"
        Matcher m1 = Pattern.compile("[（(]([a-zA-Z0-9][^）)]{2,})[）)]").matcher(title);
        if (m1.find()) {
            String en = m1.group(1).replaceAll("[^a-zA-Z0-9\\s:：\\-._]", "").trim();
            if (en.length() >= 3) return en;
        }

        // 格式2: "中文/English Name"
        Matcher m2 = Pattern.compile("[/／]\\s*([A-Za-z][A-Za-z0-9\\s:：\\-._]{2,})").matcher(title);
        if (m2.find()) {
            String en = m2.group(1).trim().replaceAll("[^a-zA-Z0-9\\s:：\\-_.]$", "");
            if (en.length() >= 3) return en;
        }

        // 格式3: "English Name 中文" — 标题以英文开头，后面跟中文
        Matcher m3 = Pattern.compile("^([A-Za-z][A-Za-z0-9\\s:：\\-._]{3,}?)\\s+[\\u4e00-\\u9fff]").matcher(title);
        if (m3.find()) {
            return m3.group(1).trim();
        }

        // 格式4: 整个标题都是英文
        if (title.matches("^[A-Za-z0-9\\s:：\\-._]{3,}$")) {
            return title.trim();
        }

        return null;
    }

    // ==================== 多策略查询构建 ====================

    /**
     * 构建多级查询策略：只保留 3-4 条最精准的，节省 SerpApi 配额。
     * SerpApi 搜不到就降级 SearxNG，SearxNG 免费无限制所以不需要这么多 SerpApi 策略。
     */
    private static List<String> buildQueryStrategies(String shortName, String englishName) {
        List<String> queries = new ArrayList<>();

        // 策略1：英文名 + "game screenshot"（最精准，命中率最高）
        if (englishName != null) {
            queries.add(englishName + " game screenshot");
            queries.add(englishName + " gameplay screenshot");
        }

        // 策略2：中文短名 + 游戏截图
        if (!shortName.isBlank()) {
            queries.add(shortName + " 游戏截图");
            queries.add(shortName + " Steam 截图");
        }

        // 策略3：兜底 — 哪个名字有就用哪个裸搜
        if (englishName != null) {
            queries.add(englishName + " game");
        } else if (!shortName.isBlank()) {
            queries.add(shortName);
        }

        return queries;
    }
}
