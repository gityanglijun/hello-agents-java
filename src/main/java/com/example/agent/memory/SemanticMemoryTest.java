package com.example.agent.memory;
import java.util.List;
import java.util.Map;

public class SemanticMemoryTest {

    public static void main(String[] args) {
        testAddAndEntities();
        testGraphSearch();
        testMixedRetrieval();
        testEntityLinking();
    }

    static MemoryManager.MemoryItem makeItem(String content, double importance) {
        return new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                content, "semantic", importance,
                Map.of("importance", String.valueOf(importance)),
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    // ==================== 测试1: 添加 + 实体提取 ====================

    static void testAddAndEntities() {
        System.out.println("==========================================");
        System.out.println("测试 添加 + 实体提取");
        System.out.println("==========================================\n");

        SemanticMemory mem = new SemanticMemory();

        mem.add(makeItem("Python是一种解释型的编程语言，广泛用于机器学习", 0.9));
        mem.add(makeItem("Java是一种编译型的面向对象语言，常用于企业应用开发", 0.85));
        mem.add(makeItem("Python和Java都支持面向对象编程范式", 0.8));
        mem.add(makeItem("深度学习框架如TensorFlow和PyTorch使用Python作为主要语言", 0.9));

        System.out.println("总记忆数: " + mem.size());
        System.out.println("提取的实体数: " + mem.entityCount());
        System.out.println("关系数: " + mem.relationCount());

        System.out.println("\n所有实体:");
        for (SemanticMemory.Entity e : mem.getAllEntities()) {
            System.out.println("  " + e.name + " [" + e.type + "]");
        }

        System.out.println();
    }

    // ==================== 测试2: 图检索 ====================

    static void testGraphSearch() {
        System.out.println("==========================================");
        System.out.println("测试 图检索（实体关联）");
        System.out.println("==========================================\n");

        SemanticMemory mem = new SemanticMemory();

        mem.add(makeItem("Python使用缩进来定义代码块", 0.9));
        mem.add(makeItem("Java使用大括号来定义代码块", 0.85));
        mem.add(makeItem("Python和Java是两种流行的编程语言", 0.8));
        mem.add(makeItem("学习Python有助于理解编程概念", 0.7));
        mem.add(makeItem("今天天气很好适合户外运动", 0.3));

        // 通过实体找到关联记忆
        System.out.println("--- 通过实体 'Python' 查找 ---");
        SemanticMemory.Entity pyEntity = null;
        for (SemanticMemory.Entity e : mem.getAllEntities()) {
            if (e.name.equals("Python")) { pyEntity = e; break; }
        }
        if (pyEntity != null) {
            List<MemoryManager.MemoryItem> linked = mem.getByEntity(pyEntity.entityId);
            System.out.println("找到 " + linked.size() + " 条关联记忆:");
            for (MemoryManager.MemoryItem item : linked) {
                System.out.println("  " + item.content);
            }
        }

        System.out.println();
    }

    // ==================== 测试3: 混合检索 ====================

    static void testMixedRetrieval() {
        System.out.println("==========================================");
        System.out.println("测试 混合检索（向量 + 图）");
        System.out.println("==========================================\n");

        SemanticMemory mem = new SemanticMemory(128);

        mem.add(makeItem("Python是一种解释型的编程语言，广泛用于机器学习和数据科学", 0.9));
        mem.add(makeItem("Java是一种编译型的面向对象语言，常用于企业级应用", 0.85));
        mem.add(makeItem("Python使用缩进定义代码块，语法简洁易读", 0.8));
        mem.add(makeItem("机器学习和深度学习是人工智能的重要分支", 0.9));
        mem.add(makeItem("数据库系统如MySQL和PostgreSQL使用SQL作为查询语言", 0.75));
        mem.add(makeItem("Java的Spring框架用于构建企业级Web应用", 0.8));
        mem.add(makeItem("Python的Django和Flask是流行的Web框架", 0.85));

        System.out.println("已添加 " + mem.size() + " 条记忆\n");

        // 搜索 Python 相关
        System.out.println("--- 搜索: 'Python 机器学习' ---");
        List<MemoryManager.MemoryItem> results = mem.retrieve("Python 机器学习", 4);
        for (int i = 0; i < results.size(); i++) {
            System.out.println((i + 1) + ". " + results.get(i).content);
        }

        // 搜索 Java 相关
        System.out.println("\n--- 搜索: 'Java Spring框架' ---");
        results = mem.retrieve("Java Spring框架", 4);
        for (int i = 0; i < results.size(); i++) {
            System.out.println((i + 1) + ". " + results.get(i).content);
        }

        // 搜索跨领域
        System.out.println("\n--- 搜索: 'Web框架' ---");
        results = mem.retrieve("Web框架", 4);
        for (int i = 0; i < results.size(); i++) {
            System.out.println((i + 1) + ". " + results.get(i).content);
        }

        System.out.println();
    }

    // ==================== 测试4: 重要性评分影响 ====================

    static void testEntityLinking() {
        System.out.println("==========================================");
        System.out.println("测试 重要性对排名的影响");
        System.out.println("==========================================\n");

        SemanticMemory mem = new SemanticMemory(128);

        mem.add(makeItem("Python基础知识包括变量、函数和类", 0.4));
        mem.add(makeItem("Python核心概念包括变量、函数和类", 0.95));

        // 语义几乎相同、重要性不同 → 高重要性应排前
        List<MemoryManager.MemoryItem> results = mem.retrieve("Python 变量 函数 类", 5);
        System.out.println("高重要性(0.95)应排在低重要性(0.4)前面:\n");
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            System.out.println((i + 1) + ". " + r.content + " (importance=" + r.importance + ")");
        }

        System.out.println();
    }
}
