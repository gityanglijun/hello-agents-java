package com.example.gamemcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 游戏数据 MCP 服务 — 通过 @Tool 注解将方法暴露为 MCP 工具。
 *
 * Agent 端（hello-agents-java）通过 MCP 协议调用这些工具来保存游戏研究数据。
 * 你可以将这些方法的实现替换为实际的数据库操作（调用 Repository 或已有的 Service）。
 */
@Service
public class GameDataMcpService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // TODO: 注入你的实际 Repository 或 Service
    // @Autowired private GameInfoRepository gameInfoRepo;
    // @Autowired private GameGuideRepository gameGuideRepo;
    // @Autowired private GameScreenshotRepository screenshotRepo;

    @Tool(name = "save_game_info", description = "保存游戏基本信息，包括游戏名称、描述、类型、开发商、发行商和支持平台")
    public String saveGameInfo(
            @ToolParam(description = "游戏名称") String gameName,
            @ToolParam(description = "游戏详细介绍") String description,
            @ToolParam(description = "游戏类型（如 RPG、FPS、动作冒险 等）") String genre,
            @ToolParam(description = "开发商名称") String developer,
            @ToolParam(description = "发行商名称") String publisher,
            @ToolParam(description = "支持平台（如 PC, PS5, Xbox, Switch）") String platforms) {

        // TODO: 替换为实际的数据库保存逻辑
        // GameInfo info = new GameInfo();
        // info.setGameName(gameName);
        // info.setDescription(description);
        // ...
        // gameInfoRepo.save(info);

        System.out.println("[MCP] 保存游戏信息: " + gameName);
        System.out.println("  类型: " + genre);
        System.out.println("  开发商: " + developer);
        System.out.println("  发行商: " + publisher);
        System.out.println("  平台: " + platforms);
        System.out.println("  介绍: " + (description != null ? description.substring(0, Math.min(100, description.length())) + "..." : ""));

        return "{\"success\": true, \"message\": \"游戏信息已保存\", \"game_name\": \"" + gameName + "\"}";
    }

    @Tool(name = "save_game_guide", description = "保存游戏攻略或指南内容，支持多种攻略类型")
    public String saveGameGuide(
            @ToolParam(description = "游戏名称") String gameName,
            @ToolParam(description = "攻略类型：walkthrough(攻略), tips(技巧), review(评测), beginner_guide(新手指南)") String guideType,
            @ToolParam(description = "攻略标题") String title,
            @ToolParam(description = "攻略正文内容") String content,
            @ToolParam(description = "来源URL") String sourceUrl) {

        // TODO: 替换为实际的数据库保存逻辑
        // GameGuide guide = new GameGuide();
        // guide.setGameName(gameName);
        // ...
        // gameGuideRepo.save(guide);

        System.out.println("[MCP] 保存游戏攻略: " + gameName);
        System.out.println("  类型: " + guideType);
        System.out.println("  标题: " + title);
        System.out.println("  来源: " + sourceUrl);

        return "{\"success\": true, \"message\": \"攻略已保存\", \"game_name\": \"" + gameName + "\", \"guide_type\": \"" + guideType + "\"}";
    }

    @Tool(name = "save_game_screenshots", description = "批量保存游戏截图信息，接收一个JSON数组格式的截图列表")
    public String saveGameScreenshots(
            @ToolParam(description = "游戏名称") String gameName,
            @ToolParam(description = "截图JSON数组字符串，每个元素包含: original(原图URL), thumbnail(缩略图URL), title(标题), source(来源网站), link(来源页面URL)") String screenshots) {

        int savedCount = 0;
        try {
            List<Map<String, String>> screenshotList = objectMapper.readValue(
                    screenshots, new TypeReference<List<Map<String, String>>>() {});

            for (Map<String, String> s : screenshotList) {
                // TODO: 替换为实际的数据库保存逻辑
                // GameScreenshot entity = new GameScreenshot();
                // entity.setGameName(gameName);
                // entity.setImageUrl(s.get("original"));
                // entity.setThumbnailUrl(s.get("thumbnail"));
                // ...
                // screenshotRepo.save(entity);
                savedCount++;
            }

            System.out.println("[MCP] 保存游戏截图: " + gameName + " (" + savedCount + " 张)");

        } catch (Exception e) {
            return "{\"success\": false, \"message\": \"截图数据解析失败: " + e.getMessage() + "\"}";
        }

        return "{\"success\": true, \"message\": \"已保存 " + savedCount + " 张截图\", \"game_name\": \"" + gameName + "\"}";
    }
}
