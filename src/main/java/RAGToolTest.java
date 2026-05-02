import java.util.List;
import java.util.Map;

public class RAGToolTest {

    public static void main(String[] args) {
        testAddDocument();
        testSearch();
        testChunking();
        testListAndRemove();
        testStats();
        testToolRegistryIntegration();
        testExpandedSearch();
    }

    // ==================== 测试1: 添加文档 ====================

    static void testAddDocument() {
        System.out.println("==========================================");
        System.out.println("测试 添加文档");
        System.out.println("==========================================\n");

        RAGTool rag = new RAGTool();

        // 添加短文档
        String result = rag.run(Map.of(
                "action", "add_document",
                "title", "Python基础",
                "content", "Python是一种解释型的高级编程语言。"
                        + "它由Guido van Rossum于1991年创建。"
                        + "Python的设计哲学强调代码的可读性和简洁的语法。"
        ));
        System.out.println(result);

        // 添加长文档
        StringBuilder longContent = new StringBuilder();
        longContent.append("Java是一种广泛使用的计算机编程语言，拥有跨平台、面向对象、泛型编程的特性。\n\n");
        longContent.append("Java由Sun Microsystems公司于1995年推出，"
                + "是Java程序设计语言和Java平台的总称。\n\n");
        longContent.append("Java具有卓越的通用性、高效性、平台移植性和安全性，"
                + "广泛应用于数据中心、游戏主机、科学超级计算机、移动电话和互联网。");
        result = rag.run(Map.of(
                "action", "add_document",
                "title", "Java概述",
                "content", longContent.toString()
        ));
        System.out.println(result);

        System.out.println("当前文档数: " + rag.docCount());
        System.out.println("当前分块数: " + rag.chunkCount());
        System.out.println();
    }

    // ==================== 测试2: 语义检索 ====================

    static void testSearch() {
        System.out.println("==========================================");
        System.out.println("测试 语义检索");
        System.out.println("==========================================\n");

        RAGTool rag = new RAGTool();

        // 构建知识库
        rag.run(Map.of("action", "add_document", "title", "Python简介",
                "content", "        ，以简洁易读著称。"
                        + "它支持多种编程范式，包括面向对象、函数式和过程式编程。"
                        + "Python拥有丰富的标准库和第三方包，"
                        + "广泛应用于数据科学、Web开发和人工智能领域。"));

        rag.run(Map.of("action", "add_document", "title", "Java简介",
                "content", "Java是一种编译型语言，运行在JVM上。"
                        + "它以一次编写到处运行而闻名。"
                        + "Java严格遵循面向对象编程范式，"
                        + "广泛用于企业级应用开发和Android移动开发。"));

        rag.run(Map.of("action", "add_document", "title", "美食推荐",
                "content", "北京烤鸭是中国最著名的菜肴之一，"
                        + "以皮脆肉嫩著称。正宗的北京烤鸭需要经过多道工序，"
                        + "包括打气、烫皮、上色、风干和挂炉烤制。"));

        System.out.println("知识库: " + rag.docCount() + " 篇文档\n");

        // 检索 Python 相关
        System.out.println("--- 检索: 'Python 编程' ---");
        System.out.println(rag.run(Map.of("action", "search", "query", "Python 编程", "top_k", 2)));

        // 检索 Java 相关
        System.out.println("\n--- 检索: 'Java 企业开发' ---");
        System.out.println(rag.run(Map.of("action", "search", "query", "Java 企业开发", "top_k", 2)));

        // 检索无关话题
        System.out.println("\n--- 检索: '美食 烤鸭' ---");
        System.out.println(rag.run(Map.of("action", "search", "query", "美食 烤鸭", "top_k", 2)));

        System.out.println();
    }

    // ==================== 测试3: 长文档分块 ====================

    static void testChunking() {
        System.out.println("==========================================");
        System.out.println("测试 长文档分块");
        System.out.println("==========================================\n");

        RAGTool rag = new RAGTool(200, 30, 128); // 小块大小便于测试

        StringBuilder sb = new StringBuilder();
        sb.append("第一段：RAG（检索增强生成）是一种结合信息检索和文本生成的技术。\n\n");
        sb.append("第二段：它的核心思想是在生成回答之前，先从知识库中检索相关文档片段。\n\n");
        sb.append("第三段：然后将这些片段作为上下文提供给大语言模型，生成更准确的回答。\n\n");
        sb.append("第四段：RAG可以有效减少大模型的幻觉问题，"
                + "因为它将回答依据限制在检索到的真实文档中。");

        rag.run(Map.of("action", "add_document", "title", "RAG技术介绍",
                "content", sb.toString()));

        System.out.println("原始文档长度: " + sb.length() + " 字符");
        System.out.println("分块大小上限: 200 字符");
        System.out.println("实际分块数: " + rag.chunkCount());

        // 检索验证每个分块都能搜到
        System.out.println("\n检索 'RAG 检索增强' 验证所有分块:");
        System.out.println(rag.run(Map.of("action", "search", "query", "RAG 检索增强", "top_k", 5)));

        System.out.println();
    }

    // ==================== 测试4: 列表 + 删除 ====================

    static void testListAndRemove() {
        System.out.println("==========================================");
        System.out.println("测试 列表 + 删除文档");
        System.out.println("==========================================\n");

        RAGTool rag = new RAGTool();

        rag.run(Map.of("action", "add_document", "title", "文档A",
                "content", "这是文档A的内容。"));
        String addResult = rag.run(Map.of("action", "add_document", "title", "文档B",
                "content", "这是文档B的内容，稍后会被删除。"));
        rag.run(Map.of("action", "add_document", "title", "文档C",
                "content", "这是文档C的内容。"));

        System.out.println("--- 列表（删除前） ---");
        System.out.println(rag.run(Map.of("action", "list_documents")));

        // 提取文档B的ID并删除
        String docBId = extractDocId(addResult);
        System.out.println("--- 删除文档B (ID: " + docBId + ") ---");
        System.out.println(rag.run(Map.of("action", "remove_document", "doc_id", docBId)));

        System.out.println("\n--- 列表（删除后） ---");
        System.out.println(rag.run(Map.of("action", "list_documents")));

        System.out.println();
    }

    // ==================== 测试5: 统计 ====================

    static void testStats() {
        System.out.println("==========================================");
        System.out.println("测试 知识库统计");
        System.out.println("==========================================\n");

        RAGTool rag = new RAGTool();

        // 空库统计
        System.out.println("--- 空库 ---");
        System.out.println(rag.run(Map.of("action", "stats")));

        // 添加内容后统计
        rag.run(Map.of("action", "add_document", "title", "测试文档",
                "content", "这是一段测试内容。它包含了多个句子。"
                        + "这是第二个句子。这是第三个句子。"));

        System.out.println("\n--- 添加后 ---");
        System.out.println(rag.run(Map.of("action", "stats")));

        System.out.println();
    }

    // ==================== 测试6: ToolRegistry 集成 ====================

    static void testToolRegistryIntegration() {
        System.out.println("==========================================");
        System.out.println("测试 ToolRegistry 集成");
        System.out.println("==========================================\n");

        RAGTool rag = new RAGTool();
        ToolRegistry registry = new ToolRegistry();
        registry.registerTool(rag);

        System.out.println("已注册工具: " + registry.listTools());

        try {
            String result = registry.executeTool("rag",
                    "action=add_document, title=集成测试, content=通过ToolRegistry添加的文档内容");
            System.out.println(result);
        } catch (Exception e) {
            System.out.println("执行失败: " + e.getMessage());
        }

        System.out.println("\n最终文档数: " + rag.docCount());
        System.out.println();
    }

    // ==================== 测试7: 扩展检索 ====================

    static void testExpandedSearch() {
        System.out.println("==========================================");
        System.out.println("测试 扩展检索 (MQE + HyDE)");
        System.out.println("==========================================\n");

        RAGTool rag = new RAGTool(500, 50, 128);

        // 构建知识库
        rag.run(Map.of("action", "add_document", "title", "Python学习路径",
                "content", "Python入门教程从基本语法开始。"
                        + "学习Python需要掌握变量、数据类型、控制流等基础知识。"
                        + "Python学习方法包括看视频教程、阅读官方文档、做编程练习。"
                        + "Python编程指南建议从简单项目开始，逐步深入复杂项目。"));

        rag.run(Map.of("action", "add_document", "title", "Java教程",
                "content", "Java是一种强类型语言，需要先理解面向对象的概念。"
                        + "Java入门推荐阅读Thinking in Java。"
                        + "Java编程需要安装JDK和配置IDE环境。"));

        rag.run(Map.of("action", "add_document", "title", "数据库基础",
                "content", "数据库是存储和管理数据的系统。"
                        + "关系型数据库使用SQL语言。"
                        + "常见的数据库包括MySQL、PostgreSQL和Oracle。"
                        + "数据库索引可以加快查询速度。"));

        rag.run(Map.of("action", "add_document", "title", "Python高级特性",
                "content", "Python装饰器是一种高阶函数，可以在不修改原函数的情况下增加功能。"
                        + "Python生成器使用yield关键字实现惰性求值。"
                        + "Python上下文管理器使用with语句管理资源。"
                        + "这些高级特性有助于编写更简洁高效的Python代码。"));

        System.out.println("知识库: " + rag.docCount() + " 篇文档\n");

        // 1. 基础扩展检索（不启用 MQE/HyDE → 退化为普通检索）
        System.out.println("--- 1. expanded_search (无扩展策略) ---");
        String r1 = rag.run(Map.of(
                "action", "expanded_search",
                "query", "学习Python的推荐方法",
                "top_k", 3
        ));
        System.out.println(r1);

        // 2. 启用 MQE
        System.out.println("\n--- 2. expanded_search (enable_mqe=true, ×3) ---");
        String r2 = rag.run(Map.of(
                "action", "expanded_search",
                "query", "如何学习编程",
                "top_k", 3,
                "enable_mqe", true,
                "mqe_expansions", 3
        ));
        System.out.println(r2);

        // 3. MQE + HyDE 同时启用，候选池×4
        System.out.println("\n--- 3. expanded_search (MQE + HyDE, 候选池×6) ---");
        String r3 = rag.run(Map.of(
                "action", "expanded_search",
                "query", "数据库性能优化",
                "top_k", 3,
                "enable_mqe", true,
                "mqe_expansions", 2,
                "enable_hyde", true,
                "candidate_pool_multiplier", 6
        ));
        System.out.println(r3);

        // 4. 对比普通 search 和 expanded_search
        System.out.println("\n--- 4. 对比: 普通 search vs expanded_search ---");
        System.out.println(">>> 普通 search 'Python进阶':");
        System.out.println(rag.run(Map.of("action", "search", "query", "Python进阶", "top_k", 3)));
        System.out.println(">>> expanded_search 'Python进阶' (MQE):");
        System.out.println(rag.run(Map.of(
                "action", "expanded_search",
                "query", "Python进阶",
                "top_k", 3,
                "enable_mqe", true,
                "mqe_expansions", 2
        )));

        // 5. 嵌入式后端信息
        System.out.println("\n--- 嵌入后端信息 ---");
        System.out.println("活跃后端: " + rag.getEmbedder().getActiveBackend());
        System.out.println("向量维度: " + rag.getEmbedder().getDimension());

        System.out.println();
    }

    // ==================== 辅助 ====================

    private static String extractDocId(String addResult) {
        // 从 "✅ 文档已添加\n  ID: xxxxxxxx..." 提取ID前缀
        int start = addResult.indexOf("ID: ");
        if (start == -1) return "";
        start += 4;
        int end = addResult.indexOf("...", start);
        return end == -1 ? addResult.substring(start).trim() : addResult.substring(start, end).trim();
    }
}
