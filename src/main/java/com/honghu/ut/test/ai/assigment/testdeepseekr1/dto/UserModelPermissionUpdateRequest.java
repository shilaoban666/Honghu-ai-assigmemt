package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 用户模型权限更新请求。
 */
@Data
@Schema(description = "用户模型权限更新请求")
public class UserModelPermissionUpdateRequest {

    @Schema(description = "允许使用的模型编码列表")
    private List<String> modelCodes;
}

