import java.util.Map;

/**
 * PDFLearningAssistant 完整功能测试
 */
public class PDFLearningAssistantTest {

    public static void main(String[] args) {
        //testInit();
        //testDocumentLoad();
        //testAsk();
        testPreciseQA();
        //testAddNoteAndRecall();
        //testStatsAndReport();
    }

    // ==================== 测试1: 初始化 ====================

    static void testInit() {
        System.out.println("==========================================");
        System.out.println("测试 初始化助手");
        System.out.println("==========================================\n");

        PDFLearningAssistant assistant = new PDFLearningAssistant("test_user");
        System.out.println("用户ID: " + assistant.getUserId());
        System.out.println("会话ID: " + assistant.getSessionId());
        System.out.println("当前文档: " + assistant.getCurrentDocument());

        Map<String, Object> stats = assistant.getStats();
        System.out.println("初始统计:");
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }

        // 未加载文档时提问
        String r = assistant.ask("什么是大语言模型？");
        System.out.println("\n未加载文档时提问: " + r);
        System.out.println();
    }

    // ==================== 测试2: 文档加载 ====================

    static void testDocumentLoad() {
        System.out.println("==========================================");
        System.out.println("测试 文档加载");
        System.out.println("==========================================\n");

        PDFLearningAssistant assistant = new PDFLearningAssistant("test_user");

        // 加载不存在的文件
        Map<String, Object> r1 = assistant.loadDocument("nonexistent.pdf");
        System.out.println("不存在的文件 → " + r1.get("message"));

        // 尝试加载实际存在的文件
        String testFile = "models/Happy-LLM-0727.pdf";
        java.io.File mdFile = new java.io.File(testFile);
        if (mdFile.exists()) {
            Map<String, Object> r2 = assistant.loadDocument(testFile);
            System.out.println("加载结果: success=" + r2.get("success")
                    + ", message=" + r2.get("message"));
            System.out.println("当前文档: " + assistant.getCurrentDocument());
        } else {
            System.out.println("(跳过: " + testFile + " 不存在)");
        }

        System.out.println();
    }

    // ==================== 测试3: 问答 ====================

    static void testAsk() {
        System.out.println("==========================================");
        System.out.println("测试 智能问答");
        System.out.println("==========================================\n");

        PDFLearningAssistant assistant = new PDFLearningAssistant("test_user");

        // 第1步：加载文档
        String testFile = "models/Happy-LLM-0727.pdf";
        java.io.File pdfFile = new java.io.File(testFile);
        if (!pdfFile.exists()) {
            System.out.println("(跳过: PDF " + testFile + " 不存在)");
            System.out.println();
            return;
        }

        Map<String, Object> loadResult = assistant.loadDocument(testFile);
        System.out.println("加载结果: " + loadResult.get("message"));
        if (!(boolean) loadResult.get("success")) {
            System.out.println();
            return;
        }

        // 第2步：基于文档提问
        System.out.println("\n--- 提问1: '什么是大语言模型？' ---");
        String answer1 = assistant.ask("什么是大语言模型？");
        System.out.println(answer1);

        System.out.println("\n--- 提问2: 'Transformer的核心组件有哪些？' ---");
        String answer2 = assistant.ask("Transformer的核心组件有哪些？");
        System.out.println(answer2);

        // 第3步：回顾学习历程
        System.out.println("\n--- 学习回顾 ---");
        String recall = assistant.recall("大语言模型 Transformer", 3);
        System.out.println(recall);

        System.out.println();
    }

    // ==================== 测试4: 精准问答（严格模式） ====================

    static void testPreciseQA() {
        System.out.println("==========================================");
        System.out.println("测试 精准问答（严格基于资料验证）");
        System.out.println("==========================================\n");

        PDFLearningAssistant assistant = new PDFLearningAssistant("test_precise");

        // 加载文档
        String testFile = "models/Happy-LLM-0727.pdf";
        java.io.File pdfFile = new java.io.File(testFile);
        if (!pdfFile.exists()) {
            System.out.println("(跳过: PDF 不存在)");
            System.out.println();
            return;
        }

        Map<String, Object> loadResult = assistant.loadDocument(testFile);
        if (!(boolean) loadResult.get("success")) {
            System.out.println("加载失败: " + loadResult.get("message"));
            System.out.println();
            return;
        }
        System.out.println("文档已加载: " + loadResult.get("document") + "\n");

        // ===== 精准事实题（答案在原文中可精确验证） =====

        // Q1: BERT 的模型规格是明确数字
        System.out.println("--- Q1: BERT base版本的层数、隐藏层维度和总参数量分别是多少？ ---");
        System.out.println("[预期] 12层Encoder / 768隐藏层维度 / 110M参数");
        System.out.println(assistant.ask("BERT base版本的层数、隐藏层维度和总参数量分别是多少？"));
        System.out.println();

        // Q2: T5 的架构类型
        System.out.println("--- Q2: T5的模型架构是什么？它把NLP任务统一转化为什么形式？ ---");
        System.out.println("[预期] Encoder-Decoder架构 / 文本到文本(Text-to-Text)形式");
        System.out.println(assistant.ask("T5的模型架构是什么？它把NLP任务统一转化为什么形式？"));
        System.out.println();

        // Q3: GPT 的架构和代表模型
        System.out.println("--- Q3: GPT使用什么架构？GPT-3在什么时候发布的？ ---");
        System.out.println("[预期] Decoder-Only架构 / 2020年发布");
        System.out.println(assistant.ask("GPT使用什么架构？GPT-3在什么时候发布的？"));
        System.out.println();

        // Q4: 超出资料范围的问题（验证严格模式不编造）
        System.out.println("--- Q4: Claude模型是什么时候发布的？ ---");
        System.out.println("[预期] 参考资料中未直接涉及该问题");
        System.out.println(assistant.ask("Claude模型是什么时候发布的？"));
        System.out.println();

        // Q5: 多个事实交叉验证
        System.out.println("--- Q5: BERT和GPT的架构有什么本质区别？ ---");
        System.out.println("[预期] BERT是Encoder-Only / GPT是Decoder-Only");
        System.out.println(assistant.ask("BERT和GPT的架构有什么本质区别？"));
        System.out.println();

        System.out.println("—— 精准问答测试完成 ——\n");
    }

    // ==================== 测试5: 笔记 + 回顾 ====================

    static void testAddNoteAndRecall() {
        System.out.println("==========================================");
        System.out.println("测试 笔记 + 回顾");
        System.out.println("==========================================\n");

        PDFLearningAssistant assistant = new PDFLearningAssistant("test_user");

        // 添加笔记（带概念）
        assistant.addNote("自注意力机制通过QKV矩阵计算序列中每个token的注意力权重",
                "注意力机制");
        assistant.addNote("多头注意力将输入投影到多个子空间并行计算",
                "注意力机制");
        assistant.addNote("位置编码用于向模型注入序列顺序信息",
                "Transformer");

        System.out.println("已添加 3 条笔记");

        // 按概念回顾
        System.out.println("\n--- 回顾 '注意力机制' ---");
        System.out.println(assistant.recall("注意力机制", 3));

        System.out.println("\n--- 回顾 'Transformer' ---");
        System.out.println(assistant.recall("Transformer 位置编码", 2));

        System.out.println();
    }

    // ==================== 测试5: 统计 + 报告 ====================

    static void testStatsAndReport() {
        System.out.println("==========================================");
        System.out.println("测试 统计 + 学习报告");
        System.out.println("==========================================\n");

        PDFLearningAssistant assistant = new PDFLearningAssistant("test_user_report");

        // 添加学习内容
        assistant.addNote("CNN使用卷积核提取空间特征", "CNN");
        assistant.addNote("RNN的循环结构适合序列建模", "RNN");
        assistant.addNote("LSTM通过门控机制解决长程依赖问题", "LSTM");
        assistant.addNote("Transformer完全基于注意力机制", "Transformer");

        // 记忆整合
        Map<String, Object> consResult = assistant.consolidateMemories();
        System.out.println("记忆整合: " + consResult.get("result"));

        // 学习统计
        System.out.println("\n--- 学习统计 ---");
        Map<String, Object> stats = assistant.getStats();
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }

        // 生成报告（含 JSON 文件）
        System.out.println("\n--- 学习报告 ---");
        Map<String, Object> report = assistant.generateReport(true);
        for (Map.Entry<String, Object> e : report.entrySet()) {
            String val = String.valueOf(e.getValue());
            if (val.length() > 150) val = val.substring(0, 150) + "...";
            System.out.println("  " + e.getKey() + ": " + val);
        }

        System.out.println();
    }
}
