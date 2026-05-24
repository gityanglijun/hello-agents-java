# Hello Agents (Java)

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

> 借鉴 Python 版 [hello-agents](https://github.com/datawhalechina/hello-agents) 的 Java 移植实现，面向 Java 开发者的 AI Agent 框架参考。

## 项目简介

Hello Agents (Java) 是一个 **教学与实践并重**的 AI Agent 框架，完整实现了原版 hello-agents 的核心设计模式与工具生态。项目基于 OpenAI 兼容 API 协议（支持 DeepSeek、OpenAI、ModelScope、智谱、Ollama、vLLM 等），提供了从入门到进阶的 Agent 开发基础设施，包括多种经典 Agent 设计模式、多后端搜索降级链、MCP 协议集成、记忆系统、RAG、向量存储、图存储等。

## 核心特性

- **LLM 接入**：基于 OpenAI SDK，自动适配 6 种服务商，支持 Function Calling 与流式输出
- **Agent 模式**：SimpleAgent / FunctionCallAgent / ReAct / Reflection / PlanAndSolve 等经典模式
- **工具生态**：多后端搜索（Tavily → SerpApi → DuckDuckGo → SearxNG 自动降级）、MCP 协议、A2A 协议
- **记忆系统**：工作记忆 + 情景记忆 + 语义记忆 + 感知记忆，四层记忆架构
- **存储后端**：向量存储（内存 / Qdrant）、图存储（内存 / Neo4j）、文档存储（SQLite）
- **RAG**：文档读取 → 智能分块 → 向量检索 → 上下文构建，完整流程
- **嵌入服务**：BGE ONNX 本地推理 → LLM API → 阿里百炼 → TF-IDF，多级降级
- **NLP**：OpenNLP 实体关系抽取 + 结巴中文分词
- **应用案例**：游戏研究 Agent、深度研究助手、旅行规划助手、AI 小镇

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+

### 配置

复制环境变量模板，填入你的 API Key：

```bash
# LLM 配置（必填）
LLM_API_KEY=sk-your-api-key
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL_ID=deepseek-v4-flash

# 搜索引擎（可选，至少配一个）
TAVILY_API_KEY=tvly-dev-xxx
SERPAPI_API_KEY=xxx

# 自建 SearxNG（可选，免费聚合搜索引擎降级兜底）
SEARXNG_URL=http://your-server:8088
```

### 运行

```bash
# 编译
mvn clean compile

# 运行游戏研究 Agent（Demo 模式，不连后端）
mvn exec:java -Dexec.mainClass=com.example.agent.game.GameResearchDemo -Dexec.args="demo 'Elden Ring'"

# 运行交互式 Agent
mvn exec:java -Dexec.mainClass=com.example.agent.game.GameResearchDemo
```

## 架构总览

```
hello-agents-java
├── Agent（抽象基类）
│   ├── SimpleAgent          → 基于标记解析，兼容任意 LLM
│   ├── FunctionCallAgent    → 基于 OpenAI Tools API，标准 agentic loop
│   ├── ReActAgent           → Thought → Action → Observation 循环
│   ├── ReflectionAgent      → 初始回答 → 反思 → 改进 三阶段迭代
│   ├── PlanAndSolveAgent    → Plan（规划） → Execute（执行）
│   └── ContextAwareAgent    → 上下文感知增强
│
├── Tool（工具体系）
│   ├── SearchTool           → Tavily/SerpApi/DuckDuckGo/SearxNG 多后端搜索
│   ├── MCPTool              → Model Context Protocol 客户端（Stdio/SSE）
│   ├── A2ATool              → Google Agent-to-Agent 协议
│   ├── NoteTool             → Markdown + YAML 笔记系统
│   ├── TerminalTool         → 安全终端命令执行
│   └── RAGTool              → 检索增强生成工具
│
├── Memory（记忆系统）
│   ├── WorkingMemory        → 短期工作记忆（TF-IDF + 时间衰减）
│   ├── EpisodicMemory       → 情景记忆（向量存储）
│   ├── SemanticMemory       → 语义记忆（向量 + 知识图谱）
│   └── PerceptualMemory     → 感知记忆（多模态）
│
├── Store（存储抽象）
│   ├── InMemoryVectorStore  → 内存向量存储
│   ├── QdrantVectorStore    → Qdrant 向量数据库
│   ├── InMemoryGraphStore   → 内存图存储
│   ├── Neo4jGraphStore      → Neo4j 图数据库
│   └── DocumentStore        → SQLite 文档存储
│
├── Embedding（嵌入服务）
│   ├── BGE ONNX（本地 512D，免费离线）
│   ├── LLM Embedding API（复用 LLM Key）
│   ├── 阿里百炼 API（1536D）
│   └── TF-IDF（兜底）
│
└── NLP
    ├── EntityRelationExtractor → 12 种命名实体 + 关系抽取
    └── ChineseTokenizer        → 结巴中文分词
```

## Agent 设计模式

| 模式 | 类名 | 核心机制 |
|------|------|----------|
| 简单 Agent | `SimpleAgent` | `[TOOL_CALL:name:params]` 标记解析，兼容任意 LLM |
| 函数调用 Agent | `FunctionCallAgent` | 原生 OpenAI tools API，标准 agentic loop |
| 思考行动 Agent | `ReActAgent` | Thought → Action → Observation 循环 |
| 反思 Agent | `ReflectionAgent` | 回答 → 反思 → 改进，最多 3 轮 |
| 规划求解 Agent | `PlanAndSolveAgent` | LLM 生成步骤计划 → 逐步执行 |

每个模式都有对应的 `My*` 示例类，可直接运行体验。

## 工具生态

### 搜索工具 (SearchTool)

支持 **4 种搜索后端**，按优先级自动降级：

```
Tavily → SerpApi → DuckDuckGo（免费）→ SearxNG（自建免费聚合）
```

特性：
- 结构化与文本双模式输出
- 可配置 `hybrid` 混合模式或指定单后端
- SearxNG 聚合 Google / Bing / Baidu 等多引擎，无配额限制

### MCP 协议 (MCPTool)

支持 Model Context Protocol，可连接任何 MCP Server：
- **Stdio 模式**：本地子进程通信
- **SSE 模式**：远程 HTTP 连接
- 自动发现 MCP 工具并展开为本地 Tool

### 游戏图片搜索 (GameImageSearchTool)

多策略搜索 + SearxNG 降级兜底：
- 从游戏标题自动提取中英文名称
- 多关键词组合搜索
- SerpApi 搜不到自动降级 SearxNG 图片搜索

## 应用案例

### 1. 游戏研究 Agent (`GameResearchDemo`)

自动收集游戏信息并通过 MCP 回传数据：

```
# 批量模式（全量处理）
mvn exec:java -Dexec.mainClass=com.example.agent.game.GameResearchDemo -Dexec.args="batch"

# Top N 模式（只处理缺失最多的前 10 款）
mvn exec:java -Dexec.mainClass=com.example.agent.game.GameResearchDemo -Dexec.args="top 10"
```

工作流：获取待研究游戏列表 → 搜索基本信息 → 搜索攻略评测 → 搜索截图 → MCP 回传后端

### 2. 深度研究助手 (`deepresearch/`)

基于 Javalin 的 HTTP 服务，支持 SSE 流式推送研究进度：
- `/api/research` — 同步研究
- `/api/research/stream` — SSE 流式研究
- PlanningService → SearchService → SummarizationService → ReportingService 完整链路

### 3. 旅行规划助手 (`trip/`)

智能旅行规划 HTTP 服务，集成高德地图 + Unsplash 图片：
- 多 Agent 协作旅行规划
- 高德地图 MCP Server 封装

### 4. AI 小镇 (`cyber-town/`)

Godot 4.5 + FastAPI + HaloAgents 的分层架构 AI 小镇。

## 记忆系统

四层记忆架构，由 `MemoryManager` 统一调度：

```
感知记忆（多模态向量）→ 工作记忆（短期上下文）→ 情景记忆（结构化事件）→ 语义记忆（知识图谱）
```

- **consolidateMemories()**：跨层记忆固化（短期 → 长期）
- **MemoryTool**：可供 LLM 调用的 9 种记忆管理操作

## 存储后端配置

```bash
# 向量存储（默认 inmemory）
VECTOR_STORE_TYPE=qdrant
QDRANT_URL=http://localhost:6333

# 图存储（默认 inmemory）
GRAPH_STORE_TYPE=neo4j
NEO4J_URI=bolt://localhost:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=password
```

## Docker 部署

```bash
# 准备配置
cp prod.env.example prod.env
vim prod.env

# 构建并运行
docker compose up -d --build

# 设置每日定时任务
crontab -e
# 每天凌晨 3:00 处理前 10 款游戏
0 3 * * * cd /path/to/hello-agents-java && docker compose run --rm game-agent top 10
```

## 项目结构

```
src/main/java/com/example/agent/
├── Agent.java              # Agent 抽象基类
├── Config.java             # 全局配置（Builder 模式）
├── Message.java            # 标准化消息模型
├── LoadDotenvUtil.java     # 环境变量加载
├── llm/                    # LLM 接入层
│   ├── HelloAgentsLLM.java     # OpenAI 兼容 LLM 客户端
│   └── StreamChunk.java        # 流式输出分块
├── tool/                   # 工具框架
│   ├── Tool.java               # 工具抽象基类
│   ├── ToolRegistry.java       # 工具注册中心
│   ├── SearchTool.java         # 多后端搜索工具
│   ├── MCPTool.java            # MCP 协议客户端
│   ├── A2ATool.java            # A2A 协议客户端
│   ├── TerminalTool.java       # 安全终端工具
│   └── ...                     # 更多工具
├── pattern/                # Agent 设计模式
│   ├── FunctionCallAgent.java  # 函数调用 Agent
│   ├── ReActAgent.java         # 思考行动 Agent
│   ├── ReflectionAgent.java    # 反思 Agent
│   └── PlanAndSolveAgent.java  # 规划求解 Agent
├── memory/                 # 记忆系统
│   ├── MemoryManager.java      # 记忆管理器
│   └── ...                     # 四种记忆实现
├── store/                  # 存储抽象
│   ├── VectorStore.java        # 向量存储接口
│   ├── GraphStore.java         # 图存储接口
│   └── DocumentStore.java      # 文档存储
├── rag/                    # RAG 系统
├── embedding/              # 嵌入服务
├── nlp/                    # NLP 处理
├── client/                 # 外部 API 客户端
├── app/                    # 应用示例
├── game/                   # 游戏研究 Agent
├── deepresearch/           # 深度研究助手
├── trip/                   # 旅行规划助手
└── helloagentsAITown/      # AI 小镇
```

## 支持的服务商

| 服务商 | 环境变量 | 说明 |
|--------|---------|------|
| DeepSeek（默认） | `LLM_API_KEY` / `LLM_BASE_URL` | 支持思维链推理 |
| OpenAI | `OPENAI_API_KEY` / `OPENAI_BASE_URL` | GPT-4o / GPT-4 系列 |
| ModelScope | `MODELSCOPE_API_KEY` | 阿里魔搭社区 |
| 智谱 AI | `ZHIPU_API_KEY` | GLM 系列 |
| Ollama | `OLLAMA_BASE_URL` | 本地部署，免费 |
| vLLM | `VLLM_BASE_URL` | 本地部署，高性能推理 |

## 主要依赖

| 依赖 | 用途 |
|------|------|
| openai-java 4.31.0 | OpenAI 兼容 API 客户端 |
| MCP SDK 1.1.2 | Model Context Protocol |
| Javalin 6.4.0 | 嵌入式 HTTP 框架 |
| Apache Tika 2.9.2 | 通用文档解析 |
| ONNX Runtime 1.20.0 | BGE 嵌入模型本地推理 |
| OpenNLP 2.5.7 | 命名实体识别 |
| JGraphT 1.5.2 | 图算法 |
| Neo4j Driver 5.28.0 | 图数据库 |
| SQLite JDBC 3.45.3 | 本地持久化 |

## 参考

- 原版 Python 项目：[hello-agents](https://github.com/datawhalechina/hello-agents) — Datawhale 推出的 AI Agent 开源教程
- [OpenAI Function Calling](https://platform.openai.com/docs/guides/function-calling)
- [Model Context Protocol (MCP)](https://modelcontextprotocol.io/)
- [Spring AI](https://spring.io/projects/spring-ai)

## License

MIT
