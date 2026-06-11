package com.honghu.ai.assigment.skill.builtin.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 {@link SandboxedCommandRunner} 的命令/子命令白名单与参数校验。
 *
 * <p>这些用例只覆盖“执行前的安全校验”分支，全部在真正 fork 进程之前就会抛错，
 * 因此不依赖本机是否安装 git/npm，也不会启动任何外部进程。</p>
 */
class SandboxedCommandRunnerTest {

    private final SandboxedCommandRunner runner = new SandboxedCommandRunner();

    @Test
    void rejectsNonAllowlistedCommand() {
        assertThatThrownBy(() -> runner.run("rm", List.of("-rf", "/")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonAllowlistedSubcommand() {
        assertThatThrownBy(() -> runner.run("git", List.of("push")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingSubcommand() {
        assertThatThrownBy(() -> runner.run("git", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsControlCharactersInArguments() {
        assertThatThrownBy(() -> runner.run("git", List.of("status", "evil\nrm -rf")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDisallowedNpmRunScript() {
        assertThatThrownBy(() -> runner.run("npm", List.of("run", "deploy")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
