import java.util.List;
import java.util.Map;

public class  WorkingMemoryTest {

    public static void main(String[] args) {
        testAddAndCapacity();
        testTtlExpiry();
        testRetrieveWithTfidf();
        testTimeDecayEffect();
        testMaxCapacityEviction();
    }

    static MemoryManager.MemoryItem makeItem(String content, double importance) {
        return new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                content,
                "working",
                importance,
                Map.of("session_id", "test_session"),
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    // ==================== 测试1: 添加 + 容量 ====================

    static void testAddAndCapacity() {
        System.out.println("==========================================");
        System.out.println("测试 添加 + 容量上限");
        System.out.println("==========================================\n");

        WorkingMemory wm = new WorkingMemory(5, 60);
        System.out.println("配置: maxCapacity=5, maxAgeMinutes=60\n");

        for (int i = 1; i <= 7; i++) {
            String id = wm.add(makeItem("记忆内容 " + i, 0.5));
            System.out.println("添加第" + i + "条: " + id.substring(0, 8) + "... → 当前容量: " + wm.size());
        }

        System.out.println("\n最终容量: " + wm.size() + " (应不超过5)");
        System.out.println();
    }

    // ==================== 测试2: TTL 过期 ====================

    static void testTtlExpiry() {
        System.out.println("==========================================");
        System.out.println("测试 TTL 过期清理");
        System.out.println("==========================================\n");

        WorkingMemory wm = new WorkingMemory(10, 0); // maxAgeMinutes=0 立即过期
        wm.add(makeItem("立即过期的记忆", 0.8));

        System.out.println("添加前容量: " + wm.size());

        // 再次添加，触发过期清理
        wm.add(makeItem("新记忆", 0.5));

        System.out.println("添加后容量: " + wm.size() + " (之前的应该已被清理)");
        System.out.println();
    }

    // ==================== 测试3: TF-IDF + 关键词混合检索 ====================

    static void testRetrieveWithTfidf() {
        System.out.println("==========================================");
        System.out.println("测试 TF-IDF + 关键词混合检索");
        System.out.println("==========================================\n");

        WorkingMemory wm = new WorkingMemory(20, 60);

        // 构造语义相关的记忆集
        wm.add(makeItem("Python是一种解释型的编程语言", 0.9));
        wm.add(makeItem("Java是一种编译型的面向对象语言", 0.8));
        wm.add(makeItem("Python函数使用def关键字定义", 0.9));
        wm.add(makeItem("Java方法需要声明返回类型", 0.7));
        wm.add(makeItem("Python和Java都是流行的编程语言", 0.85));
        wm.add(makeItem("今天天气很好适合出去散步", 0.3));
        wm.add(makeItem("明天计划去图书馆学习数据结构", 0.5));
        wm.add(makeItem("编程语言中变量可以存储数据", 0.75));

        System.out.println("已添加 " + wm.size() + " 条记忆\n");

        // 搜索: Python相关
        System.out.println("--- 搜索: 'Python函数定义' ---");
        List<MemoryManager.MemoryItem> results = wm.retrieve("Python函数定义", 3);
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            String preview = r.content.length() > 50 ? r.content.substring(0, 50) + "..." : r.content;
            System.out.println((i + 1) + ". " + preview + " (imp=" + r.importance + ")");
        }

        // 搜索: Java相关
        System.out.println("\n--- 搜索: 'Java 面向对象' ---");
        results = wm.retrieve("Java 面向对象", 3);
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            String preview = r.content.length() > 50 ? r.content.substring(0, 50) + "..." : r.content;
            System.out.println((i + 1) + ". " + preview + " (imp=" + r.importance + ")");
        }

        // 搜索: 无关话题
        System.out.println("\n--- 搜索: '散步' ---");
        results = wm.retrieve("散步", 3);
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            String preview = r.content.length() > 50 ? r.content.substring(0, 50) + "..." : r.content;
            System.out.println((i + 1) + ". " + preview + " (imp=" + r.importance + ")");
        }

        System.out.println();
    }

    // ==================== 测试4: 时间衰减效应 ====================

    static void testTimeDecayEffect() {
        System.out.println("==========================================");
        System.out.println("测试 时间衰减对排序的影响");
        System.out.println("==========================================\n");

        WorkingMemory wm = new WorkingMemory(20, 300);

        // 同内容、同重要性，但不同时间戳（模拟新旧记忆）
        MemoryManager.MemoryItem oldItem = new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                "学习Python的基础知识",
                "working",
                0.8,
                Map.of(),
                java.time.LocalDateTime.now().minus(120, java.time.temporal.ChronoUnit.MINUTES)
                        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
        wm.add(oldItem);

        MemoryManager.MemoryItem newItem = new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                "学习Python的进阶知识",
                "working",
                0.8,
                Map.of(),
                java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
        wm.add(newItem);

        List<MemoryManager.MemoryItem> results = wm.retrieve("Python学习", 5);
        System.out.println("新旧记忆同重要性(0.8)，新记忆应排在前面:\n");
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            String preview = r.content.length() > 60 ? r.content.substring(0, 60) + "..." : r.content;
            System.out.println((i + 1) + ". " + preview + " | 创建时间: " + r.createdAt);
        }

        System.out.println();
    }

    // ==================== 测试5: 容量满时优先删低重要性 ====================

    static void testMaxCapacityEviction() {
        System.out.println("==========================================");
        System.out.println("测试 容量满时淘汰最低优先级记忆");
        System.out.println("==========================================\n");

        WorkingMemory wm = new WorkingMemory(3, 60);

        System.out.println("添加3条记忆填满容量:");
        wm.add(makeItem("高重要性记忆A", 0.9));
        wm.add(makeItem("中重要性记忆B", 0.5));
        wm.add(makeItem("低重要性记忆C", 0.1));

        System.out.println("当前容量: " + wm.size());
        System.out.println("最低重要性: " +
            wm.getAll().stream().mapToDouble(m -> m.importance).min().orElse(0));
        System.out.println("最高重要性: " +
            wm.getAll().stream().mapToDouble(m -> m.importance).max().orElse(0));

        System.out.println("\n再添加一条 → 应淘汰 importance=0.1 的:");
        wm.add(makeItem("新高重要性记忆D", 0.8));

        System.out.println("当前容量: " + wm.size());
        double minImp = wm.getAll().stream().mapToDouble(m -> m.importance).min().orElse(0);
        System.out.println("当前最低重要性: " + minImp + " (应 >= 0.5)");
        System.out.println(minImp >= 0.5 ? "✅ 低重要性记忆已被正确淘汰" : "❌ 淘汰逻辑可能有问题");

        System.out.println();
    }
}
