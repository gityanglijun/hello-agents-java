package com.example.agent.pattern;

import com.example.agent.llm.MyLLM;
import com.example.agent.tool.NoteTool;

/**
 * ProjectAssistant 集成测试 — 模拟长期项目助手的完整工作流。
 *
 * 运行：
 *   mvn compile exec:java "-Dexec.mainClass=com.example.agent.pattern.ProjectAssistantTest" "-Dexec.classpathScope=compile" "-Dfile.encoding=UTF-8" -q
 */
public class ProjectAssistantTest {

    public static void main(String[] args) {
        MyLLM llm = new MyLLM.Builder().build();

        // ==================== 场景设定 ====================
        System.out.println("=".repeat(60));
        System.out.println("场景: data_pipeline_refactoring 项目的长期助手");
        System.out.println("=".repeat(60));

        ProjectAssistant assistant = new ProjectAssistant(
                "项目助手",
                "data_pipeline_refactoring",
                llm,
                null  // 使用自动生成的系统指令
        );

        // ==================== 第一次交互：记录进度 ====================
        System.out.println("\n--- 交互1: 记录项目状态 (自动保存为笔记) ---");
        String response1 = assistant.run(
                "我们已经完成了数据模型层的重构，测试覆盖率达到85%。下一步计划重构业务逻辑层，预计两周完成。",
                true  // noteAsAction
        );
        System.out.println("回答: " + response1);

        // ==================== 第二次交互：提出问题 ====================
        System.out.println("\n--- 交互2: 报告阻塞问题 ---");
        String response2 = assistant.run(
                "在重构业务逻辑层时，遇到了依赖版本冲突的问题。Spring Boot 2.x 和 3.x 的依赖不兼容。",
                true
        );
        System.out.println("回答: " + response2);

        // ==================== 第三次交互：基于笔记回顾 ====================
        System.out.println("\n--- 交互3: 基于笔记的追溯查询 ---");
        String response3 = assistant.run(
                "回顾一下，我们之前处理过哪些阻塞问题？当前的整体进度如何？",
                false
        );
        System.out.println("回答: " + response3);

        // ==================== 查看笔记摘要 ====================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("笔记摘要");
        System.out.println("=".repeat(60));
        System.out.println(assistant.getNoteSummary());

        // ==================== 手动笔记操作 ====================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("手动笔记操作");
        System.out.println("=".repeat(60));

        // 添加一条手动笔记
        String noteId = assistant.addNote(
                "## 技术决策\n\n决定将认证模块从 JWT 迁移到 OAuth2，因为需要支持第三方登录。",
                "decision",
                java.util.List.of("auth", "migration", "oauth2"),
                "认证模块技术选型决策"
        );
        System.out.println("手动笔记已创建: " + noteId);

        // 搜索包含 "认证" 的笔记
        System.out.println("\n搜索 '认证' 相关笔记:");
        for (NoteTool.Note note : assistant.searchNotes("认证")) {
            System.out.println("  [" + note.type + "] " + note.title + " " + note.tags);
        }

        // 搜索所有自动生成的笔记
        System.out.println("\n自动生成的笔记:");
        for (NoteTool.Note note : assistant.searchNotes("auto_generated")) {
            System.out.println("  [" + note.type + "] " + note.title);
        }

        // ==================== 统计 ====================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("统计");
        System.out.println("=".repeat(60));
        System.out.println("笔记总数: " + assistant.getNoteTool().count());
        System.out.println("笔记类型: " + assistant.getNoteTool().listTypes());
        System.out.println("所有标签: " + assistant.getNoteTool().listTags());

        System.out.println("\n✅ 集成测试完成");
    }
}
