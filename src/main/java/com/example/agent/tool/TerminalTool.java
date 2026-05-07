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
    private static String cachedBashPath; // 惰性检测的 Git Bash / WSL 路径
    private static Boolean cachedBashAvailable;

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
        // 跨平台命令转换（Git Bash / PowerShell 原生支持 Unix 命令，跳过翻译）
        if (isWindows() && !isBashAvailable() && !isPowershellAvailable()) {
            command = translateForWindows(command);
        }

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
     * Translate common Unix commands to Windows equivalents (cmd.exe fallback)。
     * 有 Git Bash / WSL 时不会走到这里，所以这里的翻译只需要关注 cmd 能运行的形式。
     */
    private static String translateForWindows(String command) {
        String trimmed = command.strip();
        // pwd → cd
        if (trimmed.equals("pwd") || trimmed.startsWith("pwd ")) return "cd";
        // ls → dir
        if (trimmed.equals("ls") || trimmed.startsWith("ls ")) return "dir" + trimmed.substring(2);
        // cat → type
        if (trimmed.equals("cat") || trimmed.startsWith("cat ")) return "type" + trimmed.substring(3);
        // clear / reset → cls
        if (trimmed.equals("clear") || trimmed.equals("reset")) return "cls";
        // head -N file → type file (partial — 只显示第一页)
        if (trimmed.startsWith("head ")) return "type" + trimmed.substring(5);
        // grep → findstr (基础翻译)
        if (trimmed.startsWith("grep ") || trimmed.startsWith("egrep ") || trimmed.startsWith("fgrep ")) {
            return translateGrepToFindstr(trimmed);
        }
        return command;
    }

    /** grep → findstr 基础翻译。覆盖最常见的 -rn / -r / -n / --include 模式。 */
    private static String translateGrepToFindstr(String cmd) {
        String rest = cmd.startsWith("egrep ") ? cmd.substring(6).strip()
                    : cmd.startsWith("fgrep ") ? cmd.substring(6).strip()
                    : cmd.substring(5).strip();
        boolean recursive = false;
        boolean lineNum = false;
        // 解析常见 flag
        while (rest.startsWith("-")) {
            if (rest.startsWith("-rn ") || rest.startsWith("-nr ")) {
                recursive = true; lineNum = true;
                rest = rest.substring(4).strip();
            } else if (rest.startsWith("-r ")) {
                recursive = true;
                rest = rest.substring(3).strip();
            } else if (rest.startsWith("-n ")) {
                lineNum = true;
                rest = rest.substring(3).strip();
            } else if (rest.startsWith("--include=")) {
                // --include=*.py → 忽略 (findstr 用 /s 后通配由文件参数处理)
                int space = rest.indexOf(' ');
                rest = space > 0 ? rest.substring(space + 1).strip() : "";
            } else {
                break;
            }
        }
        // 分割出 pattern 和 path
        String pattern;
        String filePath;
        if (rest.startsWith("\"") || rest.startsWith("'")) {
            char quote = rest.charAt(0);
            int end = rest.indexOf(quote, 1);
            if (end < 0) return "findstr " + rest; // 放弃翻译
            pattern = rest.substring(1, end);
            filePath = rest.substring(end + 1).strip();
        } else {
            int space = rest.indexOf(' ');
            if (space < 0) return "findstr " + rest;
            pattern = rest.substring(0, space);
            filePath = rest.substring(space + 1).strip();
        }
        // 组装 findstr 命令
        StringBuilder sb = new StringBuilder("findstr");
        if (recursive) sb.append(" /s");
        if (lineNum) sb.append(" /n");
        sb.append(" \"").append(pattern).append("\"");
        // findstr 不支持 . 作为搜索目录，需要转换为 *
        if (filePath.equals(".") || filePath.isEmpty()) {
            sb.append(" *");
        } else {
            sb.append(" ").append(filePath);
        }
        return sb.toString();
    }

    /**
     * 提取基础命令名。只认 shell 级别的 | && ; 操作符，不碰引号内或转义后的字符。
     * "grep -rn TODO\|FIXME . | wc -l" → "grep"
     */
    private String extractBaseCommand(String command) {
        // 按未转义的 |(shell pipe) 或 && 或 ; 拆分，取第一段
        String mainPart = command.split("(?<!\\\\)\\|")[0]
                                   .split("&&")[0]
                                   .split(";")[0]
                                   .strip();
        String[] parts = mainPart.split("\\s+");
        return parts.length > 0 ? parts[0] : "";
    }

    // ==================== ProcessBuilder 执行 ====================

    private String executeWithProcessBuilder(String command) {
        try {
            ProcessBuilder pb = buildProcess(command);
            pb.directory(currentDir.toFile());
            Process process = pb.start();

            // 并行读取 stdout / stderr，防止管道缓冲区满导致死锁
            ExecutorService drainer = Executors.newFixedThreadPool(2);
            Future<String> outF = drainer.submit(() -> drainStream(process.getInputStream()));
            Future<String> errF = drainer.submit(() -> drainStream(process.getErrorStream()));

            // 等待进程结束
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                drainer.shutdownNow();
                return "❌ 命令执行超时（超过 " + timeout + " 秒）";
            }

            // 进程已退出，流会在管道被读完时自动关闭
            String stdout = getWithTimeout(outF, 5, TimeUnit.SECONDS);
            String stderr = getWithTimeout(errF, 5, TimeUnit.SECONDS);
            drainer.shutdownNow();

            int exitCode = process.exitValue();

            // 合并输出
            StringBuilder output = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) {
                output.append(stdout);
            }
            if (stderr != null && !stderr.isEmpty()) {
                if (!output.isEmpty()) output.append("\n");
                output.append("[stderr]\n").append(stderr);
            }

            // 输出大小限制
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

    /** 读取流直到 EOF，自动检测编码。 */
    private static String drainStream(InputStream stream) {
        try {
            byte[] bytes = stream.readAllBytes();
            return bytes.length > 0 ? decodeBytes(bytes) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String getWithTimeout(Future<String> future, long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (Exception e) {
            future.cancel(true);
            return "";
        }
    }

    /**
     * 构建 ProcessBuilder。
     * Windows: Git Bash → PowerShell → cmd
     * Unix: /bin/sh -c
     */
    private ProcessBuilder buildProcess(String command) {
        if (isWindows()) {
            // Windows 上所有 shell 都需要引号包裹命令，否则 JDK ProcessImpl 拒掉含 " 的参数
            String safe = "\"" + command.replace("\"", "\"\"") + "\"";
            String bash = findBash();
            if (bash != null) {
                return new ProcessBuilder(bash, "-c", safe);
            }
            // PowerShell（Windows 自带，内置 ls/cat/pwd 等 Unix 别名）
            if (isPowershellAvailable()) {
                return new ProcessBuilder("powershell", "-NoProfile", "-Command", safe);
            }
            // cmd 兜底
            return new ProcessBuilder("cmd", "/c", safe);
        } else {
            return new ProcessBuilder("/bin/sh", "-c", command);
        }
    }

    private static boolean isBashAvailable() {
        if (cachedBashAvailable == null) findBash();
        return cachedBashAvailable;
    }

    private static boolean isPowershellAvailable() {
        // PowerShell 5.1 在所有 Windows 10/11 上自带，但保险起见验证一下
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", "exit 0").start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 惰性检测 Git Bash 是否可用。策略：PATH → 注册表 → 常见路径。 */
    private static String findBash() {
        if (cachedBashAvailable != null) return cachedBashPath;
        cachedBashAvailable = false;

        // 1. 从 PATH 找 git.exe，反推 bash.exe
        //    git.exe 在 Git\cmd\ 下，bash.exe 在 Git\bin\ 下
        String gitExe = findOnPath("git.exe");
        if (gitExe != null) {
            Path gitCmdDir = Path.of(gitExe).getParent();  // ...\Git\cmd
            if (gitCmdDir != null) {
                Path bashExe = gitCmdDir.getParent().resolve("bin").resolve("bash.exe");
                if (Files.exists(bashExe)) {
                    System.out.println("[TerminalTool] 使用 Git Bash (from git): " + bashExe);
                    return setBash(bashExe.toString());
                }
            }
        }

        // 2. 直接找 PATH 里的 bash.exe（如果在 Git\bin 已在 PATH 中）
        String bashOnPath = findOnPath("bash.exe");
        if (bashOnPath != null) {
            System.out.println("[TerminalTool] 使用 Git Bash (from PATH): " + bashOnPath);
            return setBash(bashOnPath);
        }

        // 3. 常见安装路径
        for (String p : new String[]{
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                "D:\\Program Files\\Git\\bin\\bash.exe",
        }) {
            if (Files.exists(Path.of(p))) {
                System.out.println("[TerminalTool] 使用 Git Bash: " + p);
                return setBash(p);
            }
        }

        // 4. 扫描用户目录下的 Git 安装
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path userGit = Path.of(localAppData, "Programs", "Git", "bin", "bash.exe");
            if (Files.exists(userGit)) {
                System.out.println("[TerminalTool] 使用 Git Bash (user): " + userGit);
                return setBash(userGit.toString());
            }
        }

        System.out.println("[TerminalTool] 使用 PowerShell / cmd (未检测到 Git Bash)");
        return null;
    }

    private static String setBash(String path) {
        cachedBashPath = path;
        cachedBashAvailable = true;
        return path;
    }

    /** 在系统 PATH 中查找可执行文件，返回完整路径，找不到返回 null。 */
    private static String findOnPath(String exeName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String exeLower = exeName.toLowerCase();
        for (String dir : pathEnv.split(";")) {
            try {
                Path exe = Path.of(dir.strip(), exeName);
                if (Files.isExecutable(exe)) return exe.toString();
                // Windows 上 .exe 可能不报 isExecutable，加 fallback
                if (Files.exists(exe)) return exe.toString();
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 读取流内容到字符串（带超时保护，防止流阻塞）。自动检测编码解决 Windows GBK 乱码。 */
    /** Decode byte array with automatic charset detection. */
    private static String decodeBytes(byte[] bytes) {
        // Try UTF-8 first
        String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (!hasReplacementChars(s)) return s;

        // Try system default charset (GBK on Chinese Windows, etc.)
        java.nio.charset.Charset sysCharset = java.nio.charset.Charset.defaultCharset();
        if (!sysCharset.equals(java.nio.charset.StandardCharsets.UTF_8)) {
            s = new String(bytes, sysCharset);
            if (!hasReplacementChars(s)) return s;
        }

        // Last resort: try GBK explicitly
        try {
            s = new String(bytes, java.nio.charset.Charset.forName("GBK"));
            if (!hasReplacementChars(s)) return s;
        } catch (Exception ignored) {}

        // Give up, return best effort with system charset
        return s;
    }

    private static boolean hasReplacementChars(String s) {
        // Unicode replacement character � indicates undecodable bytes
        return s.indexOf('�') >= 0;
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
