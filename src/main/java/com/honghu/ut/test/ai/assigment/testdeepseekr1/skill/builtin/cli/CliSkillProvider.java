package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.DangerLevel;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.ToolCallbackRegistration;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.Skill;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把受控本地 CLI 能力转换成模型可调用工具。
 *
 * <p>这里不会暴露“任意 shell”。每个 CLI 技能最终只会变成一个 {@code run} 工具，真实执行时仍要经过
 * {@link SandboxedCommandRunner} 的命令白名单、子命令白名单、工作目录限制、超时和输出截断。</p>
 *
 * <p>本 provider 产出的所有回调都标记为 {@link DangerLevel#DANGEROUS}。即使是 {@code git status}
 * 这类只读命令，也会读取本地工作区状态并可能泄露文件路径或代码内容，所以默认必须由
 * {@code ToolGuard} 按会话审批放行后才能执行。</p>
 */
@Component
@RequiredArgsConstructor
public class CliSkillProvider {

    /** 真正负责执行受限命令的沙箱 runner。 */
    private final SandboxedCommandRunner commandRunner;

    /** 将命令执行结果序列化成 JSON 字符串返回给模型。 */
    private final ObjectMapper objectMapper;

    /**
     * 把启用中的 CLI 技能转换成工具回调。
     *
     * @param skills 已经过角色、会话和全局启用过滤的 CLI 技能行
     * @return 等待审计装饰器包装的危险工具回调
     */
    public List<ToolCallbackRegistration> callbacksFor(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            // 当前会话没有启用 CLI 技能时，不注册任何工具。
            return List.of();
        }
        // 收集所有 CLI 工具注册信息，后续由 resolver 统一包审计装饰器。
        List<ToolCallbackRegistration> registrations = new ArrayList<>();
        for (Skill skill : skills) {
            // 从 skill 元数据中解析实际命令名，例如 cli:git -> git。
            String command = commandName(skill);
            if (!StringUtils.hasText(command)) {
                // 命令名为空说明这条目录数据无效，跳过。
                continue;
            }
            // 给模型看的工具名必须全局唯一且只包含安全字符。
            String qualifiedName = "cli__" + sanitize(command) + "__run";
            // FunctionToolCallback 把 Java lambda 包装成 Spring AI 工具。
            ToolCallback callback = FunctionToolCallback
                    .builder(qualifiedName, (Map<String, Object> input, ToolContext context) -> execute(command, input))
                    .description("Run an approved, allowlisted " + command + " command inside the configured workspace sandbox.")
                    .inputSchema("""
                            {
                              "type":"object",
                              "properties":{
                                "args":{
                                  "type":"array",
                                  "items":{"type":"string"},
                                  "description":"Explicit argv entries after the command. The first entry must be an allowlisted subcommand."
                                }
                              },
                              "required":["args"]
                            }
                            """)
                    .build();
            // 注册时标记为 DANGEROUS，AuditingToolCallback 会先通过 ToolGuard 检查审批。
            registrations.add(new ToolCallbackRegistration(
                    callback,
                    skill.getId(),
                    skill.getSkillKey(),
                    qualifiedName,
                    DangerLevel.DANGEROUS,
                    "CLI"
            ));
        }
        return registrations;
    }

    /**
     * 执行一次 CLI 工具调用。
     */
    private String execute(String command, Map<String, Object> input) {
        try {
            // 模型只能传 args 数组，不能传整段 shell 字符串。
            List<String> args = normalizeArgs(input == null ? null : input.get("args"));
            // 真正执行前还会在 SandboxedCommandRunner 内做白名单和参数校验。
            SandboxedCommandRunner.CommandResult result = commandRunner.run(command, args);
            // 返回结构化 JSON，模型可以看到 exitCode、timedOut 和输出内容。
            return objectMapper.writeValueAsString(Map.of(
                    "command", command,
                    "args", args,
                    "exitCode", result.exitCode(),
                    "timedOut", result.timedOut(),
                    "output", result.output()
            ));
        } catch (Exception ex) {
            // 统一包装异常，让上层审计装饰器能记录 ERROR。
            throw new IllegalStateException("CLI tool failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 把模型输入中的 args 字段规范成字符串列表。
     */
    private List<String> normalizeArgs(Object rawArgs) {
        if (rawArgs instanceof List<?> list) {
            // 每个元素都转成字符串，避免 JSON number/boolean 混入导致 ProcessBuilder 类型不匹配。
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("args must be an array of strings");
    }

    /**
     * 从 Skill 行解析命令名。
     */
    private String commandName(Skill skill) {
        if (StringUtils.hasText(skill.getMcpInstallCmd())) {
            // 当前复用 mcpInstallCmd 字段保存 CLI 命令名或安装规范；有显式配置时优先使用。
            return skill.getMcpInstallCmd().trim();
        }
        // 没有显式命令时按 skillKey 前缀解析，例如 cli:git -> git。
        String key = skill.getSkillKey();
        return key != null && key.startsWith("cli:") ? key.substring(4) : key;
    }

    /**
     * 把命令名转成适合工具名的安全片段。
     */
    private String sanitize(String value) {
        // 工具名只保留字母、数字和下划线，其他字符统一替换成下划线。
        return value == null ? "command" : value.replaceAll("[^A-Za-z0-9_]+", "_");
    }
}
