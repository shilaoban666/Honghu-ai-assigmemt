package com.honghu.ai.assigment.service;

import com.honghu.ai.assigment.entity.Organization;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.entity.UserDefaultWorkspace;
import com.honghu.ai.assigment.entity.Workspace;
import com.honghu.ai.assigment.entity.WorkspaceMember;
import com.honghu.ai.assigment.repository.OrganizationRepository;
import com.honghu.ai.assigment.repository.UserDefaultWorkspaceRepository;
import com.honghu.ai.assigment.repository.WorkspaceMemberRepository;
import com.honghu.ai.assigment.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * workspace 上下文解析服务。
 *
 * <p>多租户之后，很多业务都不再只依赖 userId，还要知道“这个用户当前是站在自己的个人空间里，
 * 还是站在某个企业团队空间里”。这个服务专门负责：</p>
 * <ul>
 *     <li>给新用户补齐个人 organization / workspace / 成员关系 / 默认空间记录。</li>
 *     <li>把一次请求最终应该落到哪个 workspace 解析出来，并做成员校验。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class WorkspaceContextService {

    private final OrganizationRepository organizationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserDefaultWorkspaceRepository userDefaultWorkspaceRepository;

    /**
     * 确保用户拥有个人 organization、个人 workspace、成员关系和默认 workspace 记录。
     *
     * <p>这个方法主要在新用户注册时调用，也可以安全地重复调用。
     * 每一步都会先查是否存在，不存在才创建，所以对老数据补齐、重复注册回调都比较稳。</p>
     *
     * <p>个人 workspace 的 ID 当前直接使用 userId，planCode 留空。
     * 这样个人用户仍然按角色规则走模型权限和配额，不会被企业套餐逻辑覆盖。</p>
     *
     * @param user 已保存到 users 表的用户
     * @return 用户个人 workspace；用户为空或 userId 为空时返回 null
     */
    @Transactional
    public Workspace ensurePersonalWorkspace(User user) {
        if (user == null || !StringUtils.hasText(user.getUserId())) {
            return null;
        }
        String userId = user.getUserId();
        organizationRepository.findById(userId).orElseGet(() -> organizationRepository.save(Organization.builder()
                .orgId(userId)
                .name("个人组织-" + user.getUsername())
                .status("ACTIVE")
                .build()));
        Workspace workspace = workspaceRepository.findById(userId).orElseGet(() -> workspaceRepository.save(Workspace.builder()
                .workspaceId(userId)
                .orgId(userId)
                .name("个人空间")
                .status("ACTIVE")
                .build()));
        workspaceMemberRepository.findByWorkspaceIdAndUserIdAndStatus(userId, userId, "ACTIVE")
                .orElseGet(() -> workspaceMemberRepository.save(WorkspaceMember.builder()
                        .workspaceId(userId)
                        .userId(userId)
                        .memberRole("OWNER")
                        .status("ACTIVE")
                        .build()));
        userDefaultWorkspaceRepository.findById(userId)
                .orElseGet(() -> userDefaultWorkspaceRepository.save(UserDefaultWorkspace.builder()
                        .userId(userId)
                        .workspaceId(userId)
                        .build()));
        return workspace;
    }

    /**
     * 解析一次请求实际使用的 workspace，并校验用户是否是该 workspace 的有效成员。
     *
     * <p>调用方可以显式传 workspaceId；如果不传，则使用 {@code user_default_workspace}。
     * 如果默认 workspace 记录还不存在，会退回 userId，这兼容了“个人 workspace ID = userId”的迁移策略。</p>
     *
     * <p>这里不仅查询 workspace，还做成员校验。这样模型权益、配额、聊天落流水等上层逻辑，
     * 都可以直接相信返回的 workspace 是当前用户可用的上下文。</p>
     *
     * @param userId 当前用户 ID
     * @param requestedWorkspaceId 前端传入的 workspace ID，可为空
     * @return 已校验成员关系的 workspace
     * @throws IllegalArgumentException 用户不是成员，或 workspace 不存在
     */
    public Workspace resolveWorkspace(String userId, String requestedWorkspaceId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        String workspaceId = requestedWorkspaceId;
        if (!StringUtils.hasText(workspaceId)) {
            workspaceId = userDefaultWorkspaceRepository.findById(userId)
                    .map(UserDefaultWorkspace::getWorkspaceId)
                    .orElse(userId);
        }
        String finalWorkspaceId = workspaceId;
        workspaceMemberRepository.findByWorkspaceIdAndUserIdAndStatus(finalWorkspaceId, userId, "ACTIVE")
                .orElseThrow(() -> new IllegalArgumentException("用户不是该 workspace 成员: " + finalWorkspaceId));
        return workspaceRepository.findById(finalWorkspaceId)
                .orElseThrow(() -> new IllegalArgumentException("workspace 不存在: " + finalWorkspaceId));
    }
}
