package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录响应 DTO。
 *
 * <p>相比普通的用户信息响应，这里额外返回：</p>
 * <ul>
 *     <li>identityLabel：身份中文名称</li>
 *     <li>permissionSummary：权限摘要说明</li>
 *     <li>availableModels：当前身份实际可选模型列表</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Schema(description = "登录响应（含身份与模型权限）")
public class LoginResponse {

    @Schema(description = "用户基础信息")
    private UserResponse user;

    @Schema(description = "身份枚举", example = "USER")
    private User.UserRole identity;

    @Schema(description = "身份中文名称", example = "普通用户")
    private String identityLabel;

    @Schema(description = "权限摘要", example = "普通用户只能使用第二梯队模型")
    private String permissionSummary;

    @Schema(description = "当前可用模型列表")
    private List<AiModelResponse> availableModels;
}

