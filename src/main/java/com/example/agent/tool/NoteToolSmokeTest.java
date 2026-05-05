package com.example.agent.tool;

import java.util.List;

/**
 * NoteTool 冒烟测试 — 验证 Markdown + YAML 笔记全生命周期。
 */
public class NoteToolSmokeTest {

    public static void main(String[] args) {
        NoteTool tool = new NoteTool();

        // ========== 添加 ==========
        System.out.println("=".repeat(60));
        System.out.println("测试1: 添加笔记");
        System.out.println("=".repeat(60));

        String id1 = tool.addNote(
                "# 项目进展 - 第一阶段\n\n## 完成情况\n已完成数据模型层的重构。\n\n## 下一步计划\n1. 重构业务逻辑层\n2. 解决依赖冲突问题",
                "task_state",
                List.of("refactoring", "phase1", "backend"),
                null
        );
        System.out.println("创建笔记: " + id1);

        String id2 = tool.addNote(
                "# Python装饰器的高级用法\n\n装饰器可用于日志、缓存、权限校验等场景。",
                "knowledge",
                List.of("python", "decorator"),
                "Python装饰器高级用法"
        );
        System.out.println("创建笔记: " + id2);

        String id3 = tool.addNote(
                "# 无标签笔记\n\n正文内容。",
                "general",
                List.of(),
                null
        );
        System.out.println("创建笔记: " + id3);

        System.out.println("当前笔记数: " + tool.count());

        // ========== 读取 ==========
        System.out.println("\n" + "=".repeat(60));
        System.out.println("测试2: 读取笔记");
        System.out.println("=".repeat(60));

        NoteTool.Note note = tool.getNote(id1);
        System.out.println("标题: " + note.title);
        System.out.println("类型: " + note.type);
        System.out.println("标签: " + note.tags);
        System.out.println("正文前80字: " + note.content.substring(0, Math.min(80, note.content.length())) + "...");

        // ========== 更新 ==========
        System.out.println("\n" + "=".repeat(60));
        System.out.println("测试3: 更新笔记");
        System.out.println("=".repeat(60));

        String result = tool.updateNote(id1, null, "项目进展 - 第一阶段（已更新）",
                List.of("refactoring", "phase1", "backend", "done"), null);
        System.out.println(result);

        note = tool.getNote(id1);
        System.out.println("更新后标题: " + note.title);
        System.out.println("更新后标签: " + note.tags);

        // ========== 搜索 ==========
        System.out.println("\n" + "=".repeat(60));
        System.out.println("测试4: 搜索笔记");
        System.out.println("=".repeat(60));

        System.out.println("--- 搜索 'Python' ---");
        for (NoteTool.Note n : tool.searchNotes("Python")) {
            System.out.println("  " + n);
        }

        System.out.println("--- 类型过滤: task_state ---");
        for (NoteTool.Note n : tool.listNotes("task_state", null)) {
            System.out.println("  " + n);
        }

        System.out.println("--- 标签过滤: [python] ---");
        for (NoteTool.Note n : tool.listNotes(null, List.of("python"))) {
            System.out.println("  " + n);
        }

        // ========== 删除 ==========
        System.out.println("\n" + "=".repeat(60));
        System.out.println("测试5: 删除笔记");
        System.out.println("=".repeat(60));

        System.out.println("删除前: " + tool.count() + " 条");
        String delResult = tool.deleteNote(id3);
        System.out.println(delResult);
        System.out.println("删除后: " + tool.count() + " 条");

        // 删除不存在的笔记
        String delFail = tool.deleteNote("nonexistent_id");
        System.out.println(delFail);

        // ========== 索引重建 ==========
        System.out.println("\n" + "=".repeat(60));
        System.out.println("测试6: 重建索引");
        System.out.println("=".repeat(60));

        String rebuildResult = tool.rebuildIndex();
        System.out.println(rebuildResult);

        // ========== 统计 ==========
        System.out.println("\n" + "=".repeat(60));
        System.out.println("测试7: 统计信息");
        System.out.println("=".repeat(60));

        System.out.println("总笔记数: " + tool.count());
        System.out.println("所有类型: " + tool.listTypes());
        System.out.println("所有标签: " + tool.listTags());
        System.out.println("所有ID: " + tool.listNoteIds());

        System.out.println("\n✅ 所有测试完成");
    }
}
