package com.example.agent.memory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.agent.nlp.EntityRelationExtractor;
import com.example.agent.tool.ToolRegistry;

public class MemoryToolTest {

    public static void main(String[] args) {
        testAddMemory();
        testSearchMemory();
        testSummaryAndStats();
        testUpdateAndRemove();
        testForget();
        testConsolidate();
        testClearAll();
        testToolFrameworkIntegration();
        testConvenienceExecute();
        testNlpEnhancedSearch();
    }

    // ==================== 操作1: add - 四种记忆类型 ====================

    static void testAddMemory() {
        System.out.println("==========================================");
        System.out.println("🧪 测试 add - 添加四种类型记忆");
        System.out.println("==========================================\n");

        MemoryTool tool = new MemoryTool();

        // 1. 工作记忆
        System.out.println(tool.execute("add", Map.of(
                "content", "用户刚才问了关于Python函数的问题",
                "memory_type", "working",
                "importance", 0.6
        )));

        // 2. 情景记忆
        System.out.println(tool.execute("add", Map.of(
                "content", "2024年3月15日，用户张三完成了第一个Python项目",
                "memory_type", "episodic",
                "importance", 0.8,
                "event_type", "milestone",
                "location", "在线学习平台"
        )));

        // 3. 语义记忆
        System.out.println(tool.execute("add", Map.of(
                "content", "Python是一种解释型、面向对象的编程语言",
                "memory_type", "semantic",
                "importance", 0.9,
                "knowledge_type", "factual"
        )));

        // 4. 感知记忆
        System.out.println(tool.execute("add", Map.of(
                "content", "用户上传了一张Python代码截图，包含函数定义",
                "memory_type", "perceptual",
                "importance", 0.7,
                "modality", "image",
                "file_path", "./uploads/code_screenshot.png"
        )));

        System.out.println();
    }

    // ==================== 操作2: search ====================

    @SuppressWarnings("unchecked")
    static void testSearchMemory() {
        System.out.println("==========================================");
        System.out.println("🔍 测试 search - 搜索记忆");
        System.out.println("==========================================\n");

        MemoryTool tool = new MemoryTool();

        // 预填数据
        tool.execute("add", Map.of(
                "content", "Python函数定义使用def关键字", "memory_type", "semantic", "importance", 0.9
        ));
        tool.execute("add", Map.of(
                "content", "用户今天学习了Python编程基础", "memory_type", "episodic", "importance", 0.7
        ));
        tool.execute("add", Map.of(
                "content", "用户明天计划学习Python面向对象", "memory_type", "working", "importance", 0.5
        ));
        tool.execute("add", Map.of(
                "content", "Java是一种静态类型的面向对象语言", "memory_type", "semantic", "importance", 0.8
        ));

        // 基础搜索
        System.out.println("--- 基础搜索: 'Python编程' ---");
        System.out.println(tool.execute("search", Map.of("query", "Python编程", "limit", 5)));

        // 指定记忆类型搜索
        System.out.println("\n--- 指定类型搜索: memory_type=episodic ---");
        System.out.println(tool.execute("search", Map.of(
                "query", "学习", "memory_type", "episodic", "limit", 3
        )));

        // 多类型搜索
        System.out.println("\n--- 多类型搜索: semantic+episodic, min_importance=0.5 ---");
        System.out.println(tool.execute("search", Map.of(
                "query", "函数",
                "memory_types", List.of("semantic", "episodic"),
                "min_importance", 0.5
        )));

        // 无结果搜索
        System.out.println("\n--- 无结果搜索 ---");
        System.out.println(tool.execute("search", Map.of("query", "量子计算")));

        System.out.println();
    }

    // ==================== 操作3+4: summary & stats ====================

    static void testSummaryAndStats() {
        System.out.println("==========================================");
        System.out.println("📊 测试 summary & stats");
        System.out.println("==========================================\n");

        MemoryTool tool = new MemoryTool();

        // 空记忆摘要
        System.out.println("--- 空记忆 ---");
        System.out.println(tool.execute("summary", Map.of()));

        // 添加一些记忆
        tool.execute("add", Map.of("content", "工作记忆1", "memory_type", "working", "importance", 0.3));
        tool.execute("add", Map.of("content", "情景记忆1：用户登录系统", "memory_type", "episodic", "importance", 0.6));
        tool.execute("add", Map.of("content", "语义记忆1：Java是面向对象的", "memory_type", "semantic", "importance", 0.9));

        System.out.println("\n--- 摘要 ---");
        System.out.println(tool.execute("summary", Map.of()));

        System.out.println("\n--- 统计 ---");
        System.out.println(tool.execute("stats", Map.of()));

        System.out.println();
    }

    // ==================== 操作5+6: update & remove ====================

    static void testUpdateAndRemove() {
        System.out.println("==========================================");
        System.out.println("✏️ 测试 update & remove");
        System.out.println("==========================================\n");

        MemoryTool tool = new MemoryTool();

        // 添加一条记忆用于后续操作
        Map<String, Object> addParams = new java.util.LinkedHashMap<>();
        addParams.put("content", "原始内容");
        addParams.put("memory_type", "working");
        addParams.put("importance", 0.5);
        String addResult = tool.execute("add", addParams);
        System.out.println(addResult);

        // 从结果中提取ID（格式: "✅ 记忆已添加 (ID: xxxxxxxx...)"）
        String id = extractId(addResult);
        System.out.println("提取到ID: " + id + "\n");

        // 更新
        System.out.println("--- 更新记忆 ---");
        System.out.println(tool.execute("update", Map.of(
                "id", id,
                "content", "更新后的内容",
                "importance", 0.9
        )));

        // 搜索验证更新
        System.out.println("\n搜索验证更新:");
        System.out.println(tool.execute("search", Map.of("query", "更新后")));

        // 删除
        System.out.println("\n--- 删除记忆 ---");
        System.out.println(tool.execute("remove", Map.of("id", id)));

        // 验证删除
        System.out.println("\n删除后统计:");
        System.out.println(tool.execute("stats", Map.of()));

        // 删除不存在的ID
        System.out.println("\n--- 删除不存在的ID ---");
        System.out.println(tool.execute("remove", Map.of("id", "nonexistent_id_12345")));

        System.out.println();
    }

    // ==================== 操作7: forget ====================

    static void testForget() {
        System.out.println("==========================================");
        System.out.println("🧹 测试 forget - 三种遗忘策略");
        System.out.println("==========================================\n");

        // --- 策略1: importance_based ---
        System.out.println("--- 策略1: 基于重要性遗忘 (threshold=0.4) ---");
        MemoryTool tool1 = new MemoryTool();
        tool1.execute("add", Map.of("content", "重要记忆A", "memory_type", "semantic", "importance", 0.9));
        tool1.execute("add", Map.of("content", "普通记忆B", "memory_type", "working", "importance", 0.5));
        tool1.execute("add", Map.of("content", "不重要记忆C", "memory_type", "working", "importance", 0.1));
        System.out.println("遗忘前: " + memoryCount(tool1) + " 条");
        System.out.println(tool1.execute("forget", Map.of("strategy", "importance_based", "threshold", 0.4)));
        System.out.println("遗忘后: " + memoryCount(tool1) + " 条\n");

        // --- 策略2: time_based ---
        System.out.println("--- 策略2: 基于时间遗忘 (max_age_days=0) ---");
        MemoryTool tool2 = new MemoryTool();
        tool2.execute("add", Map.of("content", "刚添加的记忆", "memory_type", "working", "importance", 0.8));
        System.out.println("遗忘前: " + memoryCount(tool2) + " 条");
        // max_age_days=0 会删除所有超过0天的记忆（即全部删除）
        System.out.println(tool2.execute("forget", Map.of("strategy", "time_based", "max_age_days", 0)));
        System.out.println("遗忘后: " + memoryCount(tool2) + " 条\n");

        // --- 策略3: capacity_based ---
        System.out.println("--- 策略3: 基于容量遗忘 (threshold=3, 最多保留3条) ---");
        MemoryTool tool3 = new MemoryTool();
        tool3.execute("add", Map.of("content", "记忆1", "memory_type", "working", "importance", 0.1));
        tool3.execute("add", Map.of("content", "记忆2", "memory_type", "working", "importance", 0.3));
        tool3.execute("add", Map.of("content", "记忆3", "memory_type", "working", "importance", 0.5));
        tool3.execute("add", Map.of("content", "记忆4", "memory_type", "working", "importance", 0.7));
        tool3.execute("add", Map.of("content", "记忆5", "memory_type", "working", "importance", 0.9));
        System.out.println("遗忘前: " + memoryCount(tool3) + " 条");
        System.out.println(tool3.execute("forget", Map.of("strategy", "capacity_based", "threshold", 3)));
        System.out.println("遗忘后: " + memoryCount(tool3) + " 条");
        System.out.println("剩余记忆重要性应该最高（保留了最重要的3条）\n");
    }

    // ==================== 操作8: consolidate ====================

    static void testConsolidate() {
        System.out.println("==========================================");
        System.out.println("🔄 测试 consolidate - 记忆整合");
        System.out.println("==========================================\n");

        MemoryTool tool = new MemoryTool();

        // 添加混合记忆
        tool.execute("add", Map.of("content", "重要工作记忆：用户偏好Python", "memory_type", "working", "importance", 0.8));
        tool.execute("add", Map.of("content", "普通工作记忆：当前温度23度", "memory_type", "working", "importance", 0.3));
        tool.execute("add", Map.of("content", "重要情景记忆：用户完成项目", "memory_type", "episodic", "importance", 0.9));
        tool.execute("add", Map.of("content", "普通情景记忆：用户喝了咖啡", "memory_type", "episodic", "importance", 0.4));

        System.out.println("整合前:");
        System.out.println(tool.execute("stats", Map.of()));

        // 整合：将重要的工作记忆转为情景记忆
        System.out.println("\n--- 整合: working → episodic (threshold=0.7) ---");
        System.out.println(tool.execute("consolidate", Map.of(
                "from_type", "working",
                "to_type", "episodic",
                "threshold", 0.7
        )));

        System.out.println("\n整合后:");
        System.out.println(tool.execute("stats", Map.of()));

        // 整合：将重要的情景记忆转为语义记忆
        System.out.println("\n--- 整合: episodic → semantic (threshold=0.8) ---");
        System.out.println(tool.execute("consolidate", Map.of(
                "from_type", "episodic",
                "to_type", "semantic",
                "threshold", 0.8
        )));

        System.out.println("\n最终状态:");
        System.out.println(tool.execute("stats", Map.of()));
        System.out.println();
    }

    // ==================== 操作9: clear_all ====================

    static void testClearAll() {
        System.out.println("==========================================");
        System.out.println("🗑️ 测试 clear_all");
        System.out.println("==========================================\n");

        MemoryTool tool = new MemoryTool();
        tool.execute("add", Map.of("content", "测试数据1", "memory_type", "working", "importance", 0.5));
        tool.execute("add", Map.of("content", "测试数据2", "memory_type", "episodic", "importance", 0.6));
        tool.execute("add", Map.of("content", "测试数据3", "memory_type", "semantic", "importance", 0.8));

        System.out.println("清空前: " + memoryCount(tool) + " 条");
        System.out.println(tool.execute("clear_all", Map.of()));
        System.out.println("清空后: " + memoryCount(tool) + " 条");
        System.out.println();
    }

    // ==================== Tool框架集成测试 ====================

    static void testToolFrameworkIntegration() {
        System.out.println("==========================================");
        System.out.println("🔧 测试 Tool框架集成 (ToolRegistry)");
        System.out.println("==========================================\n");

        MemoryTool tool = new MemoryTool();
        ToolRegistry registry = new ToolRegistry();
        registry.registerTool(tool);

        System.out.println("已注册工具: " + registry.listTools());
        System.out.println("工具描述:\n" + registry.describeTools());

        // 通过 ToolRegistry 执行
        try {
            String result = registry.executeTool("memory",
                    "action=add, content=通过ToolRegistry添加的记忆, memory_type=working, importance=0.7");
            System.out.println("\n执行结果: " + result);
        } catch (Exception e) {
            System.out.println("执行失败: " + e.getMessage());
        }

        System.out.println("\n最终摘要:");
        System.out.println(tool.execute("summary", Map.of()));
        System.out.println();
    }

    // ==================== 便捷方法测试 ====================

    static void testConvenienceExecute() {
        System.out.println("==========================================");
        System.out.println("⚡ 测试便捷 execute(action, keyValues...)");
        System.out.println("==========================================\n");

        MemoryTool tool = new MemoryTool();

        // 使用键值对交替传参
        System.out.println(tool.execute("add",
                "content", "通过便捷方法添加的记忆",
                "memory_type", "semantic",
                "importance", 0.85
        ));

             System.out.println(tool.execute("search",
                "query", "便捷方法",
                "limit", 3
        ));

        System.out.println();
    }

    // ==================== NLP增强检索测试 ====================

    static void testNlpEnhancedSearch() {
        System.out.println("==========================================");
        System.out.println("测试 NLP增强检索（EntityRelationExtractor）");
        System.out.println("==========================================\n");

        // 直接用 SemanticMemory 以获得实体/关系统计访问权限
        SemanticMemory semMem = new SemanticMemory(128);

        // 构建知识库（模拟真实技术文档）
        String[][] docs = {
            {"Python简介",
             "Python是一种解释型编程语言，由Guido van Rossum于1991年创建。"
             + "它以简洁易读著称，支持面向对象和函数式编程。"
             + "Python广泛应用于机器学习、数据科学和Web开发领域。"},

            {"Java与Spring生态",
             "Java是一种编译型语言，运行在JVM上。它由Sun Microsystems于1995年推出。"
             + "Spring是Java生态的核心框架，Spring Boot简化了微服务开发。"
             + "开发者使用Maven管理依赖，使用Docker部署应用。"},

            {"数据库与中间件",
             "PostgreSQL是一个关系型数据库，Redis是一个高性能缓存数据库。"
             + "MySQL广泛用于Web应用，Elasticsearch用于全文搜索。"
             + "Docker和Kubernetes构成现代云原生架构的基础。"},

            {"深度学习框架",
             "TensorFlow和PyTorch是两个主流的深度学习框架。"
             + "它们都支持GPU加速和自动微分。"
             + "Keras提供了更高层的API，Scikit-learn适用于传统机器学习。"},

            {"Python高级特性",
             "Python装饰器可以在不修改原函数的情况下增加功能。"
             + "Python生成器使用yield关键字实现惰性求值。"
             + "Python上下文管理器使用with语句管理资源。"},
        };

        // 添加到语义记忆
        for (int i = 0; i < docs.length; i++) {
            MemoryManager.MemoryItem item = new MemoryManager.MemoryItem(
                    "nlp-doc-" + i, docs[i][1], MemoryManager.TYPE_SEMANTIC,
                    0.8, null, java.time.Instant.now().toString());
            semMem.add(item);
        }

        // === 子测试1: 实体提取数量与类型 ===
        System.out.println("--- 1. 知识库统计 ---");
        System.out.println("记忆数: " + semMem.size());
        System.out.println("实体数: " + semMem.entityCount());
        System.out.println("关系数: " + semMem.relationCount());
        System.out.println("实体词典容量: " + EntityRelationExtractor.dictSize() + " 条目\n");

        // === 子测试2: 提取的实体列表 ===
        System.out.println("--- 2. 已提取实体（按类型分组） ---");
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (SemanticMemory.Entity e : semMem.getAllEntities()) {
            byType.computeIfAbsent(e.type, k -> new ArrayList<>()).add(e.name);
        }
        for (Map.Entry<String, List<String>> entry : byType.entrySet()) {
            System.out.println("  [" + entry.getKey() + "] " + entry.getValue());
        }

        // === 子测试3: 图检索（不同查询角度） ===
        System.out.println("\n--- 3. 图检索对比 ---");

        // 3a: 语言 + 框架查询
        System.out.println(">>> 查询: 'Python 深度学习 框架'");
        List<MemoryManager.MemoryItem> r1 = semMem.retrieve("Python 深度学习 框架", 3);
        for (MemoryManager.MemoryItem r : r1) {
            System.out.println("  " + r.id + ": " + truncate(r.content, 70));
        }

        // 3b: 工具/容器查询
        System.out.println("\n>>> 查询: 'Docker 容器 部署'");
        List<MemoryManager.MemoryItem> r2 = semMem.retrieve("Docker 容器 部署", 3);
        for (MemoryManager.MemoryItem r : r2) {
            System.out.println("  " + r.id + ": " + truncate(r.content, 70));
        }

        // 3c: 数据库查询
        System.out.println("\n>>> 查询: '关系型数据库 缓存'");
        List<MemoryManager.MemoryItem> r3 = semMem.retrieve("关系型数据库 缓存", 3);
        for (MemoryManager.MemoryItem r : r3) {
            System.out.println("  " + r.id + ": " + truncate(r.content, 70));
        }

        // 3d: 跨领域查询（Python + 数据库）
        System.out.println("\n>>> 查询: 'Python Web开发 数据库 MySQL'");
        List<MemoryManager.MemoryItem> r4 = semMem.retrieve("Python Web开发 数据库 MySQL", 3);
        for (MemoryManager.MemoryItem r : r4) {
            System.out.println("  " + r.id + ": " + truncate(r.content, 70));
        }

        // === 子测试4: 通过 MemoryTool 执行（集成验证） ===
        System.out.println("\n--- 4. MemoryTool 集成 ---");
        MemoryTool tool = new MemoryTool();
        tool.execute("add", Map.of(
                "content", "RAG（检索增强生成）结合了信息检索和文本生成。"
                        + "它使用向量数据库存储文档嵌入，通过语义搜索找到相关片段，"
                        + "然后交给大语言模型生成回答。",
                "memory_type", "semantic",
                "importance", 0.9));

        // 通过 tool 检索语义记忆
        String searchResult = tool.execute("search", Map.of(
                "query", "RAG 向量数据库 语义搜索",
                "memory_types", List.of("semantic"),
                "limit", 2));
        System.out.println(searchResult);

        // === 子测试5: 无 OpenNLP 模型的降级信息 ===
        System.out.println("--- 5. 当前工作模式 ---");
        System.out.println("OpenNLP 模型: " + (new java.io.File("models/opennlp").isDirectory()
                ? "目录存在（若模型齐全则启用 NER）" : "未安装（使用增强词典 150+ 条目）"));
        System.out.println("实体类型覆盖: " + EntityRelationExtractor.entityTypes());
        System.out.println();

        // 清理
        semMem.clear();
    }

    // ==================== 辅助方法 ====================

    private static String extractId(String result) {
        // 从 "✅ 记忆已添加 (ID: xxxxxxxx...)" 提取ID前缀
        int start = result.indexOf("ID: ");
        if (start == -1) return "";
        start += 4;
        int end = result.indexOf("...", start);
        return end == -1 ? result.substring(start) : result.substring(start, end);
    }

    private static int memoryCount(MemoryTool tool) {
        return tool.getMemoryManager().totalCount();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
