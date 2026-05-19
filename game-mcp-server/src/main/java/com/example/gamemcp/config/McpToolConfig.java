package com.example.gamemcp.config;

import com.example.gamemcp.service.GameDataMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册配置。
 * 将 @Tool 注解的 Service 方法注册为 MCP Server 可调用的工具。
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider gameTools(GameDataMcpService service) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(service)
                .build();
    }
}
