package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.cli;

import lombok.Builder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 不经过 shell、只执行极小白名单命令的 CLI 运行器。
 *
 * <p>本类刻意保持能力很窄：它不会拼出 {@code sh -c "..."}、{@code cmd /c "..."} 这种整段命令字符串，
 * 而是把命令和每个参数作为独立 argv 元素交给 {@link ProcessBuilder}。这样可以避开 shell 元字符注入，
 * 例如 {@code ; rm -rf}、{@code && curl ...} 这类拼接不会被 shell 解释。</p>
 *
 * <p>剩余安全措施包括：固定工作目录、命令白名单、子命令白名单、控制字符过滤、超时强杀、输出截断。
 * 这些措施仍然不是完整生产沙箱，因此上层 {@code CliSkillProvider} 会把 CLI 工具标记为 DANGEROUS，
 * 没有会话审批时 {@code ToolGuard} 会拒绝执行。</p>
 */
@Component
public class SandboxedCommandRunner {

    /** 允许执行的命令和子命令；第一层 key 是可执行文件名，第二层是 args[0] 子命令。 */
    private static final Map<String, Set<String>> ALLOWED_SUBCOMMANDS = Map.of(
            "git", Set.of("status", "diff", "log", "branch", "show"),
            "npm", Set.of("test", "run", "list", "view")
    );

    /** 允许命令执行的固定工作目录。 */
    @Value("${app.skill.cli.workspace:D:/Js_UI_projects}")
    private String workspace;

    /** 命令最大墙钟执行时间，单位秒。 */
    @Value("${app.skill.cli.timeout-seconds:20}")
    private long timeoutSeconds;

    /** 返回给模型的 stdout/stderr 合并输出最大字符数。 */
    @Value("${app.skill.cli.max-output-chars:12000}")
    private int maxOutputChars;

    /**
     * 执行一条已经白名单允许的命令。
     *
     * @param command 可执行文件名，例如 {@code git}
     * @param args 命令后的显式 argv 参数
     * @return 结构化命令结果
     */
    public CommandResult run(String command, List<String> args) {
        // 先做完整白名单校验，失败就不创建任何本地进程。
        validateCommand(command, args);
        try {
            // ProcessBuilder 接收 argv 列表：第一个元素是命令，后面元素是参数。
            List<String> argv = new ArrayList<>();
            argv.add(command);
            argv.addAll(args == null ? List.of() : args);
            // 不调用 shell，直接启动进程。
            ProcessBuilder builder = new ProcessBuilder(argv);
            // 所有命令都固定在配置的 workspace 下执行，避免模型切到任意目录。
            builder.directory(resolveWorkspace().toFile());
            // stderr 合并到 stdout，方便统一截断和返回。
            builder.redirectErrorStream(true);
            // 启动进程。
            Process process = builder.start();
            // 等待进程结束，但最多等配置的 timeoutSeconds。
            boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                // 超时后强制杀掉进程，避免后台长期占用资源。
                process.destroyForcibly();
                return CommandResult.builder()
                        .exitCode(-1)
                        .timedOut(true)
                        .output("Command timed out after " + timeoutSeconds + " seconds.")
                        .build();
            }
            // 命令结束后读取合并后的输出，按 UTF-8 解码。
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return CommandResult.builder()
                    // 正常结束时返回真实退出码。
                    .exitCode(process.exitValue())
                    .timedOut(false)
                    // 输出可能很大，返回前必须截断。
                    .output(truncate(output))
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Sandboxed command failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 校验命令名、子命令和参数是否在允许范围内。
     */
    private void validateCommand(String command, List<String> args) {
        if (!StringUtils.hasText(command) || !ALLOWED_SUBCOMMANDS.containsKey(command)) {
            // 命令本身不在白名单时直接拒绝，例如 powershell、cmd、bash、curl。
            throw new IllegalArgumentException("Command is not allowlisted: " + command);
        }
        if (args == null || args.isEmpty() || !StringUtils.hasText(args.get(0))) {
            // 每个命令都要求第一个参数是子命令，便于做细粒度白名单。
            throw new IllegalArgumentException("Subcommand is required for " + command);
        }
        // 子命令就是 args[0]，例如 git status、npm test。
        String subcommand = args.get(0);
        if (!ALLOWED_SUBCOMMANDS.get(command).contains(subcommand)) {
            // 即使命令允许，危险子命令也拒绝，例如 git clean、npm publish。
            throw new IllegalArgumentException("Subcommand is not allowlisted: " + command + " " + subcommand);
        }
        for (String arg : args) {
            if (!StringUtils.hasText(arg)) {
                // 空参数没有信息量，跳过控制字符检查。
                continue;
            }
            if (arg.contains("\n") || arg.contains("\r") || arg.indexOf('\0') >= 0) {
                // 换行和 NUL 常用于命令/日志注入，统一拒绝。
                throw new IllegalArgumentException("Command argument contains illegal control characters.");
            }
        }
        if ("npm".equals(command) && "run".equals(subcommand)) {
            if (args.size() < 2 || !Set.of("test", "build", "lint").contains(args.get(1))) {
                // npm run 必须进一步限制脚本名，否则 package.json 里的任意脚本都会被模型触发。
                throw new IllegalArgumentException("npm run only allows test/build/lint.");
            }
        }
    }

    /**
     * 解析并校验 CLI 工作目录。
     */
    private Path resolveWorkspace() {
        // 先转绝对路径再 normalize，消除 ../ 等路径片段。
        Path path = Path.of(workspace).toAbsolutePath().normalize();
        File dir = path.toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalStateException("CLI workspace does not exist: " + path);
        }
        return path;
    }

    /**
     * 截断命令输出，避免把巨大日志塞回模型上下文。
     */
    private String truncate(String output) {
        if (output == null) {
            // 没有输出时返回空字符串，便于 JSON 序列化。
            return "";
        }
        // 保底 1000 字符，避免配置过小导致调试输出不可读。
        int limit = Math.max(1000, maxOutputChars);
        return output.length() > limit ? output.substring(0, limit) + "\n[output truncated]" : output;
    }

    /**
     * 沙箱 runner 返回给工具回调的命令结果。
     *
     * @param exitCode 进程退出码；超时被杀时为 -1
     * @param timedOut 是否因为墙钟超时被杀
     * @param output 合并后的 stdout/stderr，已截断
     */
    @Builder
    public record CommandResult(int exitCode, boolean timedOut, String output) {
    }
}
