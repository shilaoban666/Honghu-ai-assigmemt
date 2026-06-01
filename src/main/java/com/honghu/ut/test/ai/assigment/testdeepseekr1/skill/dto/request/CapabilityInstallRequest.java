package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.dto.request;

import java.util.Map;

/**
 * 能力安装请求。
 *
 * <p>当前实现只把 {@link #config()} 写入 {@code user_skill_install.user_config}。
 * {@link #secrets()} 是为后续 {@code skill_secret} 加密存储预留的字段，现阶段不要把 API Key、
 * OAuth token 或本机凭据放在这里并期待后端保存。</p>
 *
 * @param owner 预留安装维度，后续可支持 {@code user}/{@code workspace}；当前以后端请求头用户为准
 * @param config 非敏感配置，例如显示偏好、endpoint 选择、安装选项
 * @param secrets 敏感配置预留字段，后续应接入加密存储和脱敏响应
 */
public record CapabilityInstallRequest(
        String owner,
        Map<String, Object> config,
        Map<String, Object> secrets
) {
}
