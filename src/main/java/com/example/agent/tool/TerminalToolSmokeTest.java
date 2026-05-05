package com.example.agent.tool;

import java.nio.file.*;
import java.util.*;

/**
 * TerminalTool 快速验证 - 覆盖四层安全机制
 */
public class TerminalToolSmokeTest {
    public static void main(String[] args) {
        int passed = 0, failed = 0;

        // 准备测试目录
        Path testDir = Path.of("target/terminal_test").toAbsolutePath();
        try { Files.createDirectories(testDir); } catch (Exception e) { e.printStackTrace(); return; }

        TerminalTool tt = new TerminalTool(testDir, 10, 1024 * 1024, true);

        // === 测试1: 白名单检查 ===
        System.out.println("=== 测试1: 命令白名单 ===");
        String r = tt.run(Map.of("command", "rm -rf /"));
        if (r.contains("❌ 不允许的命令")) { passed++; System.out.println("  ✅ 阻止危险命令"); }
        else { failed++; System.out.println("  ❌ 危险命令未被阻止: " + r); }

        r = tt.run(Map.of("command", "cat test.txt"));
        if (!r.contains("❌ 不允许的命令")) { passed++; System.out.println("  ✅ 允许安全命令"); }
        else { failed++; System.out.println("  ❌ 安全命令被误拦: " + r); }

        // === 测试2: 白名单列表 ===
        System.out.println("\n=== 测试2: 管道命令取基础命令 ===");
        r = tt.run(Map.of("command", "grep ERROR log.txt | wc -l"));
        if (!r.contains("❌ 不允许的命令")) { passed++; System.out.println("  ✅ 管道命令正确提取基础命令 'grep'"); }
        else { failed++; System.out.println("  ❌: " + r); }

        // === 测试3: cd 功能 ===
        System.out.println("\n=== 测试3: cd 导航 ===");
        r = tt.run(Map.of("command", "cd"));
        if (r.contains("当前目录")) { passed++; System.out.println("  ✅ cd 无参显示当前目录"); }
        else { failed++; System.out.println("  ❌: " + r); }

        r = tt.run(Map.of("command", "cd ."));
        if (r.contains("✅ 切换到")) { passed++; System.out.println("  ✅ cd . 成功"); }
        else { failed++; System.out.println("  ❌: " + r); }

        r = tt.run(Map.of("command", "cd ~"));
        if (r.contains("✅ 切换到")) { passed++; System.out.println("  ✅ cd ~ 回到工作目录"); }
        else { failed++; System.out.println("  ❌: " + r); }

        // === 测试4: 沙箱边界 ===
        System.out.println("\n=== 测试4: 沙箱边界 ===");
        r = tt.run(Map.of("command", "cd ../../../etc"));
        if (r.contains("❌ 不允许访问工作目录外")) { passed++; System.out.println("  ✅ 阻止 .. 逃逸"); }
        else { failed++; System.out.println("  ❌: " + r); }

        r = tt.run(Map.of("command", "cd /etc"));
        if (r.contains("❌")) { passed++; System.out.println("  ✅ 阻止绝对路径逃逸"); }
        else { failed++; System.out.println("  ❌: " + r); }

        // === 测试5: cd 到不存在的目录 ===
        System.out.println("\n=== 测试5: 不存在的目录 ===");
        r = tt.run(Map.of("command", "cd nonexistent_dir_xyz"));
        if (r.contains("❌ 目录不存在")) { passed++; System.out.println("  ✅ 正确的错误提示"); }
        else { failed++; System.out.println("  ❌: " + r); }

        // === 测试6: echo 命令 ===
        System.out.println("\n=== 测试6: echo 命令 ===");
        r = tt.run(Map.of("command", "echo Hello World"));
        if (r.contains("Hello")) { passed++; System.out.println("  ✅ echo 执行成功: " + r.trim()); }
        else { failed++; System.out.println("  ❌: " + r); }

        // === 测试7: pwd 命令 ===
        System.out.println("\n=== 测试7: pwd 命令 ===");
        r = tt.run(Map.of("command", "pwd"));
        if (!r.contains("❌ 不允许的命令")) { passed++; System.out.println("  ✅ pwd 通过白名单"); }
        else { failed++; System.out.println("  ❌: " + r); }

        // === 测试8: cd 禁用模式 ===
        System.out.println("\n=== 测试8: cd 禁用模式 ===");
        TerminalTool ttNoCd = new TerminalTool(testDir, 10, 1024 * 1024, false);
        r = ttNoCd.run(Map.of("command", "cd /tmp"));
        if (r.contains("cd 命令已禁用")) { passed++; System.out.println("  ✅ cd 被禁用"); }
        else { failed++; System.out.println("  ❌: " + r); }

        // === 结果 ===
        System.out.println("\n" + "=".repeat(40));
        int total = passed + failed;
        System.out.println("通过: " + passed + "/" + total);
        if (failed > 0) {
            System.out.println("失败: " + failed);
            System.exit(1);
        } else {
            System.out.println("全部通过 ✅");
        }
    }
}
