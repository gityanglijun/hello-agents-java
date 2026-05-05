package com.example.agent.tool;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 终端工具 — 即时文件系统访问。
 *
 * 聚焦两个核心功能：命令执行和目录导航。
 * 通过四层安全机制保证系统安全：
 *   第一层：命令白名单（仅允许只读操作）
 *   第二层：工作目录限制（沙箱，禁止逃逸）
 *   第三层：超时控制
 *   第四层：输出大小限制
 *
 * 使用示例：
 * <pre>
 *   TerminalTool terminal = new TerminalTool(Path.of("./project"));
 *   terminal.run(Map.of("command", "find . -name '*.java'"));
 *   terminal.run(Map.of("command", "grep -r 'UserService' ."));
 *   terminal.run(Map.of("command", "cat src/main/App.java"));
 *   terminal.run(Map.of("command", "cd src/main"));
 * </pre>
 */
public class TerminalTool extends Tool {

    // ==================== 第一层：命令白名单 ====================

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            // 文件列表与信息
            "ls", "dir", "tree",
            // 文件内容查看
            "cat", "head", "tail", "less", "more", "type",
            // 文件搜索
            "find", "grep", "egrep", "fgrep", "findstr",
            // 文本处理
            "wc", "sort", "uniq", "cut", "awk", "sed",
            // 目录操作
            "pwd", "cd", "chdir",
            // 文件信息
            "file", "stat", "du", "df",
            // 其他
            "echo", "which", "whereis", "where"
    );

    // ==================== 状态 ====================

    private final Path workspace;       // 工作目录（沙箱边界）
    private Path currentDir;            // 当前目录
    private final int timeout;          // 超时秒数
    private final int maxOutputSize;    // 最大输出字节
    private final boolean allowCd;      // 是否允许 cd

    // ==================== 构造 ====================

    public TerminalTool() {
        this(Path.of(".").toAbsolutePath());
    }

    public TerminalTool(Path workspace) {
        this(workspace, 30, 10 * 1024 * 1024, true);
    }

    /**
     * @param workspace     工作目录（沙箱边界，所有操作限制在此目录内）
     * @param timeout       命令超时（秒）
     * @param maxOutputSize 最大输出大小（字节）
     * @param allowCd       是否允许 cd 导航
     */
    public TerminalTool(Path workspace, int timeout, int maxOutputSize, boolean allowCd) {
        super("terminal",
                "即时文件系统访问工具。在当前工作目录下安全执行只读命令，"
              + "支持目录导航、文件查看、内容搜索和文本处理。\n"
              + "允许的命令: " + String.join(", ", ALLOWED_COMMANDS.stream().sorted().toList()));
        this.workspace = workspace.toAbsolutePath().normalize();
        this.currentDir = this.workspace;
        this.timeout = timeout;
        this.maxOutputSize = maxOutputSize;
        this.allowCd = allowCd;

        try {
            Files.createDirectories(this.workspace);
        } catch (IOException e) {
            throw new RuntimeException("无法创建工作目录: " + workspace, e);
        }
    }

    // ==================== Tool 接口 ====================

    @Override
    public String run(Map<String, Object> parameters) {
        String command = (String) parameters.getOrDefault("command", "");
        if (command == null || command.isBlank()) {
            return "❌ 命令不能为空";
        }
        return executeCommand(command.strip());
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("command", "string",
                        "要执行的 shell 命令（仅允许只读命令）"),
                new ToolParameter("timeout", "integer",
                        "命令超时时间（秒），默认30", false, 30),
                new ToolParameter("workspace", "string",
                        "工作目录路径", false, "./")
        );
    }

    // ==================== 命令执行 ====================

    /**
     * 执行命令。先解析基础命令名做白名单检查，cd 走特殊处理，
     * 其余命令通过 ProcessBuilder 在当前目录下执行。
     */
    String executeCommand(String command) {
        // 管道命令取最后一个管道前的第一个命令做白名单检查
        // 例: "grep ERROR file | wc -l" → 检查 "grep"
        String baseCommand = extractBaseCommand(command);

        if (!ALLOWED_COMMANDS.contains(baseCommand)) {
            return "❌ 不允许的命令: " + baseCommand
                    + "\n允许的命令: " + String.join(", ", ALLOWED_COMMANDS.stream().sorted().toList());
        }

        // cd 命令特殊处理（会改变自身状态）
        if (baseCommand.equals("cd") || baseCommand.equals("chdir")) {
            return handleCd(command);
        }

        return executeWithProcessBuilder(command);
    }

    /**
     * 提取基础命令名。处理管道、重定向等场景。
     * "grep ERROR file | wc -l" → "grep"
     * "find . -name '*.java'" → "find"
     */
    private String extractBaseCommand(String command) {
        // 管道符前的主命令
        String mainPart = command.split("\\|")[0].strip();
        // 取第一个词
        String[] parts = mainPart.split("\\s+");
        return parts.length > 0 ? parts[0] : "";
    }

    // ==================== ProcessBuilder 执行 ====================

    private String executeWithProcessBuilder(String command) {
        try {
            ProcessBuilder pb = buildProcess(command);
            pb.directory(currentDir.toFile());

            Process process = pb.start();

            // 异步读取 stdout / stderr
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            // 第三层：超时控制
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "❌ 命令执行超时（超过 " + timeout + " 秒）";
            }

            int exitCode = process.exitValue();

            // 合并输出
            StringBuilder output = new StringBuilder();
            if (!stdout.isEmpty()) {
                output.append(stdout);
            }
            if (!stderr.isEmpty()) {
                if (!output.isEmpty()) output.append("\n");
                output.append("[stderr]\n").append(stderr);
            }

            // 第四层：输出大小限制
            if (output.length() > maxOutputSize) {
                output.setLength(maxOutputSize);
                output.append("\n\n⚠️ 输出被截断（超过 ").append(maxOutputSize).append(" 字节）");
            }

            // 非零返回码
            if (exitCode != 0) {
                output.insert(0, "⚠️ 命令返回码: " + exitCode + "\n\n");
            }

            return !output.isEmpty() ? output.toString()
                    : "✅ 命令执行成功（无输出）";

        } catch (IOException e) {
            return "❌ 命令执行失败: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "❌ 命令执行被中断";
        }
    }

    /**
     * 构建 ProcessBuilder，自动检测操作系统使用对应的 shell。
     */
    private ProcessBuilder buildProcess(String command) {
        if (isWindows()) {
            return new ProcessBuilder("cmd", "/c", command);
        } else {
            return new ProcessBuilder("/bin/sh", "-c", command);
        }
    }

    /** 读取流内容到字符串（带超时保护，防止流阻塞） */
    private String readStream(InputStream stream) {
        try {
            Future<String> future = Executors.newSingleThreadExecutor().submit(() -> {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (sb.length() + line.length() + 1 > maxOutputSize) {
                            sb.append("\n... (输出已截断)");
                            break;
                        }
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(line);
                    }
                }
                return sb.toString();
            });
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "\n... (读取超时)";
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== cd 命令处理（第二层：沙箱） ====================

    /**
     * 处理 cd 命令，带工作目录边界检查。
     *
     * 支持格式：
     *   cd            → 显示当前目录
     *   cd <path>     → 切换到指定目录
     *   cd ..         → 上级目录（不超过 workspace）
     *   cd .          → 当前目录（无变化）
     *   cd ~          → 回到 workspace 根目录
     */
    String handleCd(String command) {
        if (!allowCd) {
            return "❌ cd 命令已禁用";
        }

        // 解析目标路径
        String[] parts = command.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            return "当前目录: " + currentDir;
        }

        String target = parts[1].strip();
        Path newDir;

        switch (target) {
            case ".." -> newDir = currentDir.getParent();
            case "."  -> newDir = currentDir;
            case "~"  -> newDir = workspace;
            default   -> {
                Path resolved = currentDir.resolve(target).normalize();
                // 处理 "cd /absolute/path" 的情况
                if (target.startsWith("/") || (target.length() > 1 && target.charAt(1) == ':')) {
                    resolved = Path.of(target).normalize();
                } else {
                    resolved = currentDir.resolve(target).normalize();
                }
                newDir = resolved;
            }
        }

        // 第二层：检查是否在工作目录内
        try {
            newDir.toAbsolutePath().normalize().relativize(
                    workspace.toAbsolutePath().normalize());
            // 如果上面没抛异常，说明从 newDir 能 relativize 到 workspace
            // 反过来检查：workspace 必须是 newDir 的前缀
            if (!newDir.toAbsolutePath().normalize()
                    .startsWith(workspace.toAbsolutePath().normalize())) {
                return "❌ 不允许访问工作目录外的路径: " + newDir;
            }
        } catch (IllegalArgumentException e) {
            // relativize 失败 = 不同根 = 在工作目录外
            return "❌ 不允许访问工作目录外的路径: " + newDir;
        }

        // 检查目录是否存在
        if (!Files.exists(newDir)) {
            return "❌ 目录不存在: " + newDir;
        }
        if (!Files.isDirectory(newDir)) {
            return "❌ 不是目录: " + newDir;
        }

        // 更新当前目录
        this.currentDir = newDir.toAbsolutePath().normalize();
        return "✅ 切换到目录: " + currentDir;
    }

    // ==================== 路径安全校验（公开方法） ====================

    /**
     * 检查给定路径是否在工作目录内。
     * 可供外部代码在构造命令前做预检查。
     */
    public boolean isPathSafe(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(workspace.toAbsolutePath().normalize());
    }

    /**
     * 将相对路径解析为工作目录下的绝对路径。
     * 始终返回 workspace 内的安全路径（越界则截断到 workspace 边界）。
     */
    public Path resolveSafe(String relativePath) {
        Path resolved = workspace.resolve(relativePath).toAbsolutePath().normalize();
        if (resolved.startsWith(workspace.toAbsolutePath().normalize())) {
            return resolved;
        }
        return workspace;
    }

    // ==================== 访问器 ====================

    public Path getWorkspace() { return workspace; }
    public Path getCurrentDir() { return currentDir; }
    public int getTimeout() { return timeout; }
    public int getMaxOutputSize() { return maxOutputSize; }
    public boolean isAllowCd() { return allowCd; }

    // ==================== 辅助 ====================

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
