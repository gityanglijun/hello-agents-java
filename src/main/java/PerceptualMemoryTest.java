import java.util.List;
import java.util.Map;

public class PerceptualMemoryTest {

    public static void main(String[] args) {
        testAddMultiModality();
        testModalityFiltering();
        testModalityInference();
        testRecencyScoring();
    }

    static MemoryManager.MemoryItem makeItem(String content, String modality,
                                              String filePath, double importance) {
        Map<String, String> meta = new java.util.LinkedHashMap<>();
        meta.put("modality", modality);
        if (filePath != null) meta.put("raw_data", filePath);
        meta.put("importance", String.valueOf(importance));

        return new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                content, "perceptual", importance, meta,
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
    }

    // ==================== 测试1: 多模态添加 + 分离存储 ====================

    static void testAddMultiModality() {
        System.out.println("==========================================");
        System.out.println("测试 多模态添加 + 按模态分离存储");
        System.out.println("==========================================\n");

        PerceptualMemory mem = new PerceptualMemory();

        // 文本模态
        mem.add(makeItem("用户上传了一份Python学习笔记", "text", null, 0.8));
        mem.add(makeItem("代码截图显示了一个递归函数的实现", "text", null, 0.7));

        // 图像模态
        mem.add(makeItem("用户上传了一张Python代码截图，包含函数定义",
                "image", "./uploads/code_screenshot.png", 0.9));
        mem.add(makeItem("用户分享了一张架构设计图",
                "image", "./uploads/architecture.png", 0.85));

        // 音频模态
        mem.add(makeItem("用户录了一段项目讨论的语音备忘",
                "audio", "./recordings/discussion.mp3", 0.75));

        System.out.println("总记忆数: " + mem.size());
        System.out.println("模态分布: " + mem.modalityCounts());
        System.out.println("  text: " + mem.sizeByModality("text") + " 条");
        System.out.println("  image: " + mem.sizeByModality("image") + " 条");
        System.out.println("  audio: " + mem.sizeByModality("audio") + " 条");
        System.out.println();
    }

    // ==================== 测试2: 模态过滤检索 ====================

    static void testModalityFiltering() {
        System.out.println("==========================================");
        System.out.println("测试 模态过滤检索");
        System.out.println("==========================================\n");

        PerceptualMemory mem = new PerceptualMemory();

        mem.add(makeItem("Python装饰器的高级用法", "text", null, 0.9));
        mem.add(makeItem("Python类的继承和多态", "text", null, 0.85));
        mem.add(makeItem("Python代码的UML类图", "image", "./uml_diagram.png", 0.8));
        mem.add(makeItem("Python函数调用栈的截图", "image", "./stack_trace.png", 0.75));

        // 不限模态检索
        System.out.println("--- 不限模态搜索: 'Python 代码' ---");
        List<MemoryManager.MemoryItem> results = mem.retrieve("Python 代码", 4);
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            String mod = r.metadata.getOrDefault("modality", "text");
            String preview = r.content.length() > 40 ? r.content.substring(0, 40) + "..." : r.content;
            System.out.println((i + 1) + ". [" + mod + "] " + preview);
        }

        // 只检索图像
        System.out.println("\n--- 限定 target_modality=image ---");
        results = mem.retrieve("Python", 4,
                Map.of("target_modality", "image"));
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            System.out.println((i + 1) + ". [" + r.metadata.get("modality") + "] " + r.content);
        }

        // 只检索文本
        System.out.println("\n--- 限定 target_modality=text ---");
        results = mem.retrieve("Python", 4,
                Map.of("target_modality", "text"));
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            System.out.println((i + 1) + ". [" + r.metadata.get("modality") + "] " + r.content);
        }

        System.out.println();
    }

    // ==================== 测试3: 模态推断 ====================

    static void testModalityInference() {
        System.out.println("==========================================");
        System.out.println("测试 模态推断 (文件扩展名 → 模态)");
        System.out.println("==========================================\n");

        System.out.println("code_screenshot.png → " + PerceptualMemory.inferModality("code_screenshot.png"));
        System.out.println("discussion.mp3     → " + PerceptualMemory.inferModality("discussion.mp3"));
        System.out.println("notes.txt          → " + PerceptualMemory.inferModality("notes.txt"));
        System.out.println("photo.jpg          → " + PerceptualMemory.inferModality("photo.jpg"));
        System.out.println("podcast.wav        → " + PerceptualMemory.inferModality("podcast.wav"));
        System.out.println("readme.md          → " + PerceptualMemory.inferModality("readme.md"));
        System.out.println();
    }

    // ==================== 测试4: 时间近因性 ====================

    static void testRecencyScoring() {
        System.out.println("==========================================");
        System.out.println("测试 时间近因性对排序的影响");
        System.out.println("==========================================\n");

        // 用文本模态测试，因为文本查询和文本存储共享同一向量空间
        PerceptualMemory mem = new PerceptualMemory(128, 128, 128);

        // 旧记忆（2小时前）
        MemoryManager.MemoryItem oldItem = new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                "用户正在调试一个Python递归函数的栈溢出错误", "perceptual", 0.8,
                Map.of("modality", "text", "importance", "0.8"),
                java.time.LocalDateTime.now().minus(2, java.time.temporal.ChronoUnit.HOURS)
                        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
        mem.add(oldItem);

        // 新记忆（现在）
        MemoryManager.MemoryItem newItem = new MemoryManager.MemoryItem(
                java.util.UUID.randomUUID().toString(),
                "用户刚刚遇到了一个Python递归函数的栈溢出错误", "perceptual", 0.8,
                Map.of("modality", "text", "importance", "0.8"),
                java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );
        mem.add(newItem);

        List<MemoryManager.MemoryItem> results = mem.retrieve("Python 递归 栈溢出", 5);
        System.out.println("同重要性(0.8)，新记忆应排在旧记忆前面:\n");
        for (int i = 0; i < results.size(); i++) {
            MemoryManager.MemoryItem r = results.get(i);
            System.out.println((i + 1) + ". " + r.content);
            System.out.println("   importance=" + r.importance + " | 时间=" + r.createdAt);
        }

        System.out.println();
    }
}
