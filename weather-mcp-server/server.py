#!/usr/bin/env python3
"""天气查询 MCP 服务器 — 可发布到 Smithery 的独立版本。

基于 hello_agents MCPServer，提供 3 个工具：
  - get_weather: 查询指定城市实时天气
  - list_supported_cities: 列出支持的中文城市
  - get_server_info: 获取服务器元信息

数据源: wttr.in (免费天气 API, 无需 API Key)
"""

import json
import sys
import os
from datetime import datetime
from typing import Dict, Any

# 自动处理 hello_agents 导入路径
try:
    from hello_agents.protocols import MCPServer
except ImportError:
    # Docker / Smithery 环境: 通过 pip install hello-agents 安装
    raise ImportError(
        "需要 hello-agents 库。安装: pip install hello-agents"
    )

# 中文城市名 → 英文城市名映射
CITY_MAP = {
    "北京": "Beijing",   "上海": "Shanghai",   "广州": "Guangzhou",
    "深圳": "Shenzhen",  "杭州": "Hangzhou",   "成都": "Chengdu",
    "重庆": "Chongqing", "武汉": "Wuhan",      "西安": "Xi'an",
    "南京": "Nanjing",   "天津": "Tianjin",    "苏州": "Suzhou",
}

# ==================== 天气数据获取 (wttr.in) ====================

def get_weather_data(city: str) -> Dict[str, Any]:
    """从 wttr.in 免费 API 获取实时天气数据。"""
    import requests

    city_en = CITY_MAP.get(city, city)
    url = f"https://wttr.in/{city_en}?format=j1"

    response = requests.get(url, timeout=10)
    response.raise_for_status()

    data = response.json()
    current = data["current_condition"][0]

    return {
        "city": city,
        "temperature": float(current["temp_C"]),
        "feels_like": float(current["FeelsLikeC"]),
        "humidity": int(current["humidity"]),
        "condition": current["weatherDesc"][0]["value"],
        "wind_speed": round(float(current["windspeedKmph"]) / 3.6, 1),
        "visibility": float(current["visibility"]),
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }

# ==================== MCP 工具函数 ====================

def get_weather(city: str) -> str:
    """获取指定城市的当前天气。

    Args:
        city: 中文城市名（如 北京、上海、深圳）

    Returns:
        JSON 格式的天气数据，包含温度、湿度、天气状况、风速、能见度等
    """
    try:
        data = get_weather_data(city)
        return json.dumps(data, ensure_ascii=False, indent=2)
    except Exception as e:
        return json.dumps({"error": str(e), "city": city}, ensure_ascii=False)


def list_supported_cities() -> str:
    """列出所有支持的中文城市名。

    Returns:
        JSON 格式的城市列表和数量
    """
    result = {
        "cities": list(CITY_MAP.keys()),
        "count": len(CITY_MAP),
    }
    return json.dumps(result, ensure_ascii=False, indent=2)


def get_server_info() -> str:
    """获取 MCP 服务器元信息。

    Returns:
        JSON 格式的服务器信息
    """
    info = {
        "name": "Weather MCP Server",
        "version": "1.0.0",
        "protocol": "MCP (Model Context Protocol)",
        "data_source": "wttr.in",
        "tools": [
            {"name": "get_weather", "description": "获取指定城市的当前天气"},
            {"name": "list_supported_cities", "description": "列出所有支持的中文城市"},
            {"name": "get_server_info", "description": "获取服务器元信息"},
        ],
    }
    return json.dumps(info, ensure_ascii=False, indent=2)

# ==================== 创建并启动 MCP 服务器 ====================

weather_server = MCPServer(
    name="weather-server",
    description="真实天气查询服务 — 基于 wttr.in 免费 API，支持 12 个中文城市"
)

weather_server.add_tool(get_weather)
weather_server.add_tool(list_supported_cities)
weather_server.add_tool(get_server_info)

if __name__ == "__main__":
    weather_server.run()
