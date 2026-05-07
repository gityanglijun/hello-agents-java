# Weather MCP Server

[![Smithery](https://smithery.ai/badge/weather-mcp-server)](https://smithery.ai/server/weather-mcp-server)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

基于 [wttr.in](https://wttr.in) 免费天气 API 和 [HelloAgents](https://github.com/helloagents) 框架的 **MCP (Model Context Protocol)** 天气查询服务器。

## 功能

| 工具 | 说明 |
|------|------|
| `get_weather` | 查询指定城市的实时天气（温度、湿度、天气状况、风速、能见度） |
| `list_supported_cities` | 列出所有支持的中文城市（12个） |
| `get_server_info` | 获取服务器版本和元信息 |

## 支持的城市

北京、上海、广州、深圳、杭州、成都、重庆、武汉、西安、南京、天津、苏州

## 安装和使用

### 方式 1: Smithery CLI

```bash
npm install -g @smithery/cli
smithery install weather-mcp-server
```

### 方式 2: Claude Desktop

```json
{
  "mcpServers": {
    "weather": {
      "command": "smithery",
      "args": ["run", "weather-mcp-server"]
    }
  }
}
```

### 方式 3: HelloAgents (Python)

```python
from hello_agents.tools import MCPTool

weather_tool = MCPTool(
    server_command=["smithery", "run", "weather-mcp-server"]
)
```

### 方式 4: HelloAgents (Java)

```java
MCPTool weather = new MCPTool("weather",
    List.of("smithery", "run", "weather-mcp-server"),
    Map.of());
```

### 本地运行

```bash
pip install hello-agents requests
python server.py
```

## 数据源

天气数据来自 [wttr.in](https://wttr.in)，一个免费、无需 API Key 的天气查询服务。

## 项目结构

```
weather-mcp-server/
├── server.py          # MCP 服务器主文件
├── pyproject.toml     # Python 项目配置
├── requirements.txt   # Python 依赖
├── smithery.yaml      # Smithery 平台配置
├── Dockerfile         # Docker 构建配置
├── LICENSE            # MIT 许可证
└── README.md          # 项目说明
```

## 许可证

MIT License
