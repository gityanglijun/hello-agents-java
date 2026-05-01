import java.util.List;
import java.util.Map;

public class EpisodicMemoryTest {

    public static void main(String[] args) {
        testAddAndSessionIndex();
        testStructuredFilter();
        testVectorSearch();
        testEpisodeScoring();
    }

    static MemoryManager.MemoryItem makeItem(String content, double importance,
                                              String sessionId, String eventType) {
        return new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                content,
                "episodic",
                importance,
                Map.of("session_id", sessionId, "event_type", eventType, "importance", String.valueOf(importance)),
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    // ==================== 测试1: 添加 + 会话索引 ====================

    static void testAddAndSessionIndex() {
        System.out.println("==========================================");
        System.out.println("测试 添加 + 会话索引");
        System.out.println("==========================================\n");

        EpisodicMemory mem = new EpisodicMemory();

        mem.add(makeItem("用户登录了系统", 0.6, "session_a", "login"));
        mem.add(makeItem("用户查询了Python文档", 0.7, "session_a", "query"));
        mem.add(makeItem("用户保存了代码片段", 0.8, "session_a", "save"));
        mem.add(makeItem("用户浏览了首页", 0.5, "session_b", "browse"));
        mem.add(makeItem("用户修改了个人设置", 0.6, "session_b", "update"));

        System.out.println("总记忆数: " + mem.size());
        System.out.println("会话列表: " + mem.listSessions());
        System.out.println("session_a 记忆数: " + mem.getBySession("session_a").size());
        System.out.println("session_b 记忆数: " + mem.getBySession("session_b").size());
        System.out.println();
    }

    // ==================== 测试2: 结构化过滤 ====================

    static void testStructuredFilter() {
        System.out.println("==========================================");
        System.out.println("测试 结构化过滤");
        System.out.println("==========================================\n");

        EpisodicMemory mem = new EpisodicMemory();

        String yesterday = java.time.LocalDateTime.now().minus(24, java.time.temporal.ChronoUnit.HOURS)
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // 不同会话的记忆
        MemoryManager.MemoryItem oldItem = new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                "用户昨天学习了Python基础", "episodic", 0.8,
                Map.of("session_id", "s1", "importance", "0.8"),
                yesterday
        );
        mem.add(oldItem);

        MemoryManager.MemoryItem newItem = new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                "用户刚才提问了Java问题", "episodic", 0.7,
                Map.of("session_id", "s1", "importance", "0.7"),
                now
        );
        mem.add(newItem);

        mem.add(makeItem("用户登出了系统", 0.5, "s2", "logout"));

        // 按会话过滤
        System.out.println("--- 过滤 session_id=s1 ---");
        List<MemoryManager.MemoryItem> results = mem.retrieve(
                "学习", 5,
                Map.of("session_id", "s1")
        );
        for (MemoryManager.MemoryItem r : results) {
            System.out.println("  " + r.content + " (session=" + r.metadata.get("session_id") + ")");
        }

        // 按重要性过滤
        System.out.println("\n--- 过滤 min_importance=0.75 ---");
        results = mem.retrieve("用户", 5, Map.of("min_importance", 0.75));
        for (MemoryManager.MemoryItem r : results) {
            System.out.println("  " + r.content + " (imp=" + r.importance + ")");
        }

        System.out.println();
    }

    // ==================== 测试3: 向量语义检索 ====================

    static void testVectorSearch() {
        System.out.println("==========================================");
        System.out.println("测试 向量语义检索");
        System.out.println("==========================================\n");

        EpisodicMemory mem = new EpisodicMemory(128);

        mem.add(makeItem("Python函数使用def关键字定义", 0.9, "s1", "study"));
        mem.add(makeItem("Java方法需要声明返回类型", 0.8, "s1", "study"));
        mem.add(makeItem("用户在图书馆借了一本书", 0.5, "s2", "activity"));
        mem.add(makeItem("Python装饰器可以修改函数行为", 0.85, "s1", "study"));
        mem.add(makeItem("今天中午吃了牛肉面", 0.3, "s2", "activity"));
        mem.add(makeItem("使用列表推导式可以简化Python代码", 0.8, "s1", "study"));

        System.out.println("已添加 " + mem.size() + " 条记忆\n");

        System.out.println("--- 搜索: 'Python 函数' ---");
        List<MemoryManager.MemoryItem> results = mem.retrieve("Python 函数", 3);
        for (int i = 0; i < results.size(); i++) {
            System.out.println((i + 1) + ". " + results.get(i).content);
        }

        System.out.println("\n--- 搜索: '吃饭 活动' ---");
        results = mem.retrieve("吃饭 活动", 3);
        for (int i = 0; i < results.size(); i++) {
            System.out.println((i + 1) + ". " + results.get(i).content);
        }

        System.out.println();
    }

    // ==================== 测试4: 评分公式验证 ====================

    static void testEpisodeScoring() {
        System.out.println("==========================================");
        System.out.println("测试 评分公式 (vec×0.8 + recency×0.2) × importanceWeight");
        System.out.println("==========================================\n");

        EpisodicMemory mem = new EpisodicMemory(128);

        // 同内容、不同重要性
        MemoryManager.MemoryItem highImp = new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                "用户完成了Python课程的所有练习", "episodic", 0.95,
                Map.of("session_id", "s1", "importance", "0.95"),
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
        mem.add(highImp);

        MemoryManager.MemoryItem lowImp = new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                "用户完成了Python课程的一个小测验", "episodic", 0.3,
                Map.of("session_id", "s1", "importance", "0.3"),
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
        mem.add(lowImp);

        List<MemoryManager.MemoryItem> results = mem.retrieve("完成 Python 课程", 5);
        System.out.println("高重要性(0.95)应排在低重要性(0.3)前面:\n");
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            System.out.println((i + 1) + ". " + r.content + " (importance=" + r.importance + ")");
        }

        System.out.println();
    }
}
