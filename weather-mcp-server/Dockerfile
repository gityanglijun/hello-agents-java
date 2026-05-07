# Multi-stage build for weather-mcp-server
# Smithery 标准端口: 8081

FROM python:3.12-slim-bookworm AS base

WORKDIR /app

# 安装系统依赖
RUN apt-get update && apt-get install -y \
    --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 复制项目文件（利用 Docker 层缓存：先复制依赖文件）
COPY pyproject.toml requirements.txt ./

# 安装 Python 依赖
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# 复制服务器代码
COPY server.py ./

# 环境变量
ENV PYTHONUNBUFFERED=1
ENV PORT=8081

# Smithery 标准端口
EXPOSE 8081

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import server; print('OK')"

# 启动 MCP 服务器
CMD ["python", "server.py"]
