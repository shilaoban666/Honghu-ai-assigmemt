package com.honghu.ai.assigment.controller;

import com.honghu.ai.assigment.dto.AiModelResponse;
import com.honghu.ai.assigment.dto.UserModelPermissionUpdateRequest;
import com.honghu.ai.assigment.entity.AiModelDefinition;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.service.AiModelAccessService;
import com.honghu.ai.assigment.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * AI 模型目录与权限控制器。
 */
@Slf4j
@RequiredArgsConstructor
@Deprecated
@Tag(name = "AI 模型管理（已停用）", description = "模型能力已并入 login 接口返回；该控制器保留仅为兼容代码结构，不再对外暴露接口")
public class AiModelController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final AiModelAccessService aiModelAccessService;
    private final UserService userService;

    @Operation(summary = "查询全部模型目录")
    public ResponseEntity<List<AiModelResponse>> getModelCatalog() {
        List<AiModelResponse> response = aiModelAccessService.listAllEnabledModels().stream()
                .map(AiModelResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "查询当前用户可用模型")
    public ResponseEntity<List<AiModelResponse>> getAvailableModels(
            String userId) {
        User user = userId != null && !userId.isBlank() ? userService.getUserById(userId) : null;
        List<AiModelResponse> response = aiModelAccessService.listAccessibleModels(user).stream()
                .map(AiModelResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "查询指定用户可用模型")
    public ResponseEntity<List<AiModelResponse>> getUserModelPermissions(String userId) {
        User user = userService.getUserById(userId);
        List<AiModelResponse> response = aiModelAccessService.listAccessibleModels(user).stream()
                .map(AiModelResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "替换指定用户的模型权限")
    public ResponseEntity<List<AiModelResponse>> replaceUserModelPermissions(
            String userId,
            UserModelPermissionUpdateRequest request) {
        log.info("收到替换用户模型权限请求：userId={}, modelCodes={}", userId, request.getModelCodes());
        List<AiModelDefinition> updated = aiModelAccessService.replaceUserPermissions(userId, request.getModelCodes());
        return ResponseEntity.ok(updated.stream().map(AiModelResponse::fromEntity).toList());
    }
}


