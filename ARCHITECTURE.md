# HelloAgents-Java 项目架构

## 包结构一览

```
src/main/java/
├── com/example/agent/                    # 基础设施层
│   ├── Agent.java                        # Agent 抽象基类
│   ├── Config.java                       # 全局配置 Builder
│   ├── Message.java                      # 标准化对话消息
│   ├── LoadDotenvUtil.java               # .env 环境变量加载
│   └── Memory.java                       # (遗留) 简单记忆
│
├── com/example/agent/llm/                # LLM 层
│   ├── HelloAgentsLLM.java               # OpenAI 兼容客户端
│   └── MyLLM.java                        # Builder 模式封装
│
├── com/example/agent/tool/               # 工具框架层
│   ├── Tool.java                         # Tool 抽象基类
│   ├── ToolParameter.java                # 工具参数定义
│   ├── ToolRegistry.java                 # 工具注册中心
│   ├── ToolExecutor.java                 # (遗留) 反射工具执行器
│   ├── ToolChain.java                    # 顺序工具管道
│   ├── ToolChainManager.java             # 命名管道管理
│   ├── AsyncToolExecutor.java            # 并行异步工具执行
│   ├── SearchTool.java                   # 网络搜索 (Tavily/SerpApi)
│   ├── CalculatorTool.java               # 简单算术计算器
│   ├── MyCalculatorTool.java             # 增强计算器
│   └── MyAdvancedSearchTool.java         # 多源搜索编排
│
├── com/example/agent/memory/             # 记忆系统层
│   ├── BaseMemory.java                   # 记忆系统公共接口
│   ├── MemoryConfig.java                 # 统一配置 Builder
│   ├── MemoryManager.java                # 统一记忆调度器
│   ├── MemoryTool.java                   # 记忆管理工具 (9种操作)
│   ├── WorkingMemory.java                # 工作记忆 (TF-IDF + 时间衰减)
│   ├── EpisodicMemory.java               # 情景记忆 (向量 + 结构化过滤)
│   ├── SemanticMemory.java               # 语义记忆 (向量 + 知识图谱)
│   └── PerceptualMemory.java             # 感知记忆 (多模态)
│
├── com/example/agent/rag/                # RAG 系统层
│   ├── RAGTool.java                      # 检索增强生成工具
│   ├── MarkdownChunker.java              # 标题感知智能分块
│   └── DocumentReader.java               # Tika 通用文档读取
│
├── com/example/agent/store/              # 持久化存储层
│   ├── VectorStore.java                  # 向量存储 (内存+JSON文件)
│   ├── GraphStore.java                   # 图存储 (实体/关系+JSON文件)
│   └── DocumentStore.java               # SQLite 文档存储
│
├── com/example/agent/embedding/          # 嵌入服务层
│   ├── EmbedderProvider.java             # 多后端自动降级调度器
│   ├── BGEOnnxEmbedding.java             # BGE 本地 ONNX (512维)
│   ├── BailianEmbeddingClient.java       # 阿里云百炼 (1536维)
│   └── LLMEmbeddingClient.java           # OpenAI 兼容嵌入 API
│
├── com/example/agent/nlp/                # NLP 层
│   └── EntityRelationExtractor.java      # 实体/关系提取 (OpenNLP + 词典)
│
├── com/example/agent/client/             # HTTP 客户端层
│   ├── TavilyHttpClient.java             # Tavily 搜索 API
│   └── SerpApiHttpClient.java            # SerpApi 搜索 API
│
├── com/example/agent/pattern/            # Agent 模式层
│   ├── SimpleAgent.java                  # 基础问答 Agent
│   ├── ReActAgent.java                   # 思考-行动-观察 循环
│   ├── ReflectionAgent.java              # 初始→反思→精炼
│   ├── PlanAndSolveAgent.java            # 规划→执行
│   ├── PlanAndSolveAgentLegacy.java      # (遗留) 旧版规划器
│   ├── Planner.java                      # 规划生成器
│   └── Executor.java                     # 计划执行器
│
└── com/example/agent/app/                # 应用层
    ├── PDFLearningAssistant.java          # PDF 智能学习助手
    └── MyMain.java                        # 入口演示
```

## 架构全景图

```
HelloAgents-Java
│
├── Agent模式层 (Agent Patterns Layer)
│   ├── Agent (抽象基类) — LLM + 对话历史 + 系统提示词
│   │   ├── SimpleAgent — 基础问答 + 可选工具调用
│   │   ├── ReActAgent — Thought-Action-Observation 循环
│   │   ├── ReflectionAgent — 初始→反思→精炼 三阶段
│   │   └── PlanAndSolveAgent — 规划→执行 两阶段
│   └── Message, Config — 对话消息 · 配置管理
│
├── 工具框架层 (Tool Framework Layer)
│   ├── Tool (抽象基类) — 统一的 run(params) 接口
│   │   ├── SearchTool — 网络搜索 (Tavily + SerpApi)
│   │   ├── MemoryTool — 统一记忆管理 (9种操作)
│   │   └── RAGTool — 检索增强生成 (文档→分块→向量→问答)
│   ├── ToolRegistry — 工具注册中心 (生成OpenAI function-calling Schema)
│   ├── ToolChain — 顺序多步骤工具管道 ({variable}模板插值)
│   ├── ToolChainManager — 命名管道注册管理
│   └── AsyncToolExecutor — CompletableFuture并行工具执行
│
├── 记忆系统层 (Memory System Layer)
│   ├── BaseMemory — 公共接口 (add/retrieve/size/clear)
│   ├── MemoryConfig — 统一配置 Builder (容量/维度/衰减参数)
│   ├── MemoryManager — 统一调度 (关键词检索 + 三种遗忘策略)
│   ├── MemoryItem — 标准化记忆项 (id/content/type/importance/metadata)
│   │
│   ├── WorkingMemory — 工作记忆
│   │   └── TF-IDF向量 + 关键词混合 · 指数时间衰减 · 容量上限+TTL
│   │
│   ├── EpisodicMemory — 情景记忆
│   │   └── TextEmbedder (TF-IDF) · 向量(0.8)+时序(0.2) · 结构化过滤
│   │
│   ├── SemanticMemory — 语义记忆
│   │   └── 向量(0.7)+知识图谱(0.3) · 实体提取 · 关系推断 · 一跳邻居
│   │
│   └── PerceptualMemory — 感知记忆
│       └── 多模态存储 (text/image/audio) · 模态推断 · 哈希编码
│
├── 嵌入服务层 (Embedding Service Layer)
│   ├── EmbedderProvider — 多后端自动降级调度器
│   │   ├── ① BGEOnnxEmbedding — 本地ONNX (免费·中文最优·512维)
│   │   ├── ② LLMEmbeddingClient — LLM提供商API (复用LLM配置)
│   │   ├── ③ BailianEmbeddingClient — 阿里云百炼 text-embedding-v2 (1536维)
│   │   └── ④ EpisodicMemory.TextEmbedder — TF-IDF本地 (永远兜底)
│   └── Markdown预处理 (去代码块/链接/HTML/格式符)
│
├── 文档处理层 (Document Processing Layer)
│   ├── DocumentReader — Apache Tika通用文档读取 (PDF/Office/HTML/EPUB/图片OCR)
│   ├── MarkdownChunker — 标题感知智能分块 (CJK token估算 · 重叠窗口)
│   └── EntityRelationExtractor — NLP实体/关系提取
│       ├── OpenNLP NER (6种英文实体 · 模型文件可选)
│       └── 增强词典 (153条目 · 8种实体类型 · 6种关系谓语)
│
├── LLM/基础设施层 (Infrastructure Layer)
│   ├── HelloAgentsLLM — OpenAI兼容客户端 (DeepSeek/OpenAI/智谱/Ollama/vLLM)
│   │   └── MyLLM — Builder模式封装 (自动检测提供商)
│   ├── LoadDotenvUtil — .env环境变量加载
│   ├── Config — 全局配置 Builder
│   └── Message — 标准化对话消息
│
├── HTTP客户端层 (HTTP Client Layer)
│   ├── TavilyHttpClient — Tavily搜索API
│   ├── SerpApiHttpClient — SerpApi搜索API
│   ├── BailianEmbeddingClient — 阿里云百炼嵌入API
│   └── LLMEmbeddingClient — OpenAI兼容嵌入API
│
├── 持久化存储层 (Storage Layer)
│   ├── DocumentStore — SQLite 统一文档存储 (memory_items/episodes/documents/chunks)
│   ├── VectorStore — 内存检索 + JSON文件持久化 (余弦相似度搜索)
│   └── GraphStore — 实体-关系图存储 + JSON文件持久化 (实体去重/一跳邻居)
│
└── 应用层 (Application Layer)
    ├── PDFLearningAssistant — PDF智能学习助手
    │   └── RAGTool + MemoryTool 组合 · 会话管理 · 学习报告生成
    └── CalculatorTool / MyCalculatorTool — 算术计算工具
```

## 与 Python 参考架构的对应关系

| Python | Java | 差异 |
|--------|------|------|
| MemoryManager | MemoryManager | ✅ 对齐 |
| MemoryItem | MemoryManager.MemoryItem | ✅ 对齐 (内部类) |
| MemoryConfig | MemoryConfig | ✅ Builder 模式，集中管理所有记忆配置 |
| BaseMemory | BaseMemory | ✅ 公共接口 (add/retrieve/size/clear) |
| WorkingMemory | WorkingMemory | ✅ 对齐 |
| EpisodicMemory | EpisodicMemory | ✅ 对齐 |
| SemanticMemory | SemanticMemory | ✅ 对齐 |
| PerceptualMemory | PerceptualMemory | ✅ 对齐 |
| **QdrantVectorStore** | **VectorStore** | ✅ 内存检索 + JSON文件持久化 |
| **Neo4jGraphStore** | **GraphStore** | ✅ 实体-关系图 + JSON文件持久化 |
| **SQLiteDocumentStore** | **DocumentStore** | ✅ SQLite (sqlite-jdbc) |
| DashScopeEmbedding | BailianEmbeddingClient | ✅ 对齐 |
| LocalTransformerEmbedding | BGEOnnxEmbedding | ✅ 对齐 (ONNX BGE) |
| TFIDFEmbedding | TextEmbedder | ✅ 对齐 |
| — | LLMEmbeddingClient | ➕ Java 新增 (复用LLM配置) |
| RAGTool | RAGTool | ✅ 对齐 |
| MemoryTool | MemoryTool | ✅ 对齐 |
| — | Agent / SimpleAgent / ReActAgent | ➕ Java 新增 (完整Agent模式) |
| — | Tool / ToolRegistry / ToolChain | ➕ Java 新增 (完整工具框架) |
| — | PDFLearningAssistant | ➕ Java 新增 (应用层) |
| — | EntityRelationExtractor | ➕ Java 新增 (NLP层) |

## 关键设计决策

1. **可插拔存储后端** — VectorStore（内存+JSON文件）、GraphStore（实体关系图+JSON文件）、DocumentStore（SQLite）。默认内存模式零依赖启动，可选文件持久化。生产可替换为 Qdrant/Neo4j 等外部服务

2. **嵌入降级链** — BGE(本地免费) → LLM API → 百炼 API → TF-IDF(兜底)。保证在任何配置下系统都能跑

3. **Tool 抽象** — 所有工具统一 `run(Map<String,Object>)` 接口，ToolRegistry 生成 OpenAI function-calling Schema，零耦合接入任意 Agent 模式

4. **NLP 降级** — OpenNLP NER 模型可选，未安装时自动使用 153 条目的增强词典

5. **中文优先** — BGE bge-small-zh 嵌入 · BERT中文分词 · MarkdownChunker CJK token 估算 · EntityRelationExtractor 中英文混合匹配
