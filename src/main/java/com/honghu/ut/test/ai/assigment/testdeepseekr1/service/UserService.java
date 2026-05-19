package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.exception.LoginServiceException;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户管理服务
 * <p>
 * 负责处理用户的增删改查业务逻辑
 * </p>
 *
 * @author shilaoban
 * @since 2026-03-11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final WorkspaceContextService workspaceContextService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 创建新用户
     *
     * @param user 用户对象
     * @return 创建后的用户
     */
    @Transactional
    public User createUser(User user) {
        log.info("开始创建用户：{}", user.getUsername());
        
        // 检查用户名是否已存在
        if (userRepository.existsByUsername(user.getUsername())) {
            String errorMsg = String.format("用户名已存在：%s", user.getUsername());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        // 检查手机号是否已存在
        if (user.getPhone() != null && userRepository.existsByPhone(user.getPhone())) {
            String errorMsg = String.format("手机号已存在：%s", user.getPhone());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        // 检查邮箱是否已存在
        if (user.getEmail() != null && userRepository.existsByEmail(user.getEmail())) {
            String errorMsg = String.format("邮箱已存在：%s", user.getEmail());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        // 生成 UUID 作为用户 ID
        user.setUserId(UUID.randomUUID().toString());
        
        // 加密密码
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
//            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        
        // 设置默认值
        if (user.getGender() == null) {
            user.setGender(User.Gender.OTHER);
        }
        if (user.getUserStatus() == null) {
            user.setUserStatus(User.UserStatus.ACTIVE);
        }
        if (user.getUserRole() == null) {
            user.setUserRole(User.UserRole.USER);
        }

        User savedUser = userRepository.save(user);
        workspaceContextService.ensurePersonalWorkspace(savedUser);
        log.info("用户创建成功：{}, userId: {}", user.getUsername(), savedUser.getUserId());
        
        return savedUser;
    }

    /**
     * 根据 ID 查询用户
     *
     * @param userId 用户 ID
     * @return 用户对象
     * @throws RuntimeException 当用户不存在时抛出
     */
    public User getUserById(String userId) {
        log.info("查询用户：{}", userId);
        return userRepository.findById(userId)
                .orElseThrow(() -> {
                    String errorMsg = String.format("用户不存在：%s", userId);
                    log.warn(errorMsg);
                    return new RuntimeException(errorMsg);
                });
    }

    /**
     * 根据 ID 查询用户（返回 Optional）
     *
     * @param userId 用户 ID
     * @return 用户 Optional
     */
    public Optional<User> getUserByIdOptional(String userId) {
        log.info("查询用户：{}", userId);
        return userRepository.findById(userId);
    }

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户 Optional
     */
    public Optional<User> getUserByUsername(String username) {
        log.info("根据用户名查询用户：{}", username);
        return userRepository.findByUsername(username);
    }

    /**
     * 获取所有用户
     *
     * @return 用户列表
     */
    public List<User> getAllUsers() {
        log.info("获取所有用户列表");
        return userRepository.findAll();
    }

    /**
     * 更新用户信息
     *
     * @param userId   用户 ID
     * @param updateUser 要更新的用户信息
     * @return 更新后的用户
     * @throws RuntimeException 当用户不存在时抛出
     */
    @Transactional
    public User updateUser(String userId, User updateUser) {
        log.info("更新用户信息：{}", userId);
        
        User existingUser = getUserById(userId);
        
        // 如果要修改的用户名已被其他用户使用
        if (!existingUser.getUsername().equals(updateUser.getUsername()) 
                && userRepository.existsByUsername(updateUser.getUsername())) {
            String errorMsg = String.format("用户名已存在：%s", updateUser.getUsername());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        // 如果要修改的手机号已被其他用户使用
        if (updateUser.getPhone() != null 
                && !existingUser.getPhone().equals(updateUser.getPhone())
                && userRepository.existsByPhone(updateUser.getPhone())) {
            String errorMsg = String.format("手机号已存在：%s", updateUser.getPhone());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        // 如果要修改的邮箱已被其他用户使用
        if (updateUser.getEmail() != null 
                && !existingUser.getEmail().equals(updateUser.getEmail())
                && userRepository.existsByEmail(updateUser.getEmail())) {
            String errorMsg = String.format("邮箱已存在：%s", updateUser.getEmail());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        // 更新字段
        existingUser.setUsername(updateUser.getUsername());
        existingUser.setNickname(updateUser.getNickname());
        existingUser.setPhone(updateUser.getPhone());
        existingUser.setEmail(updateUser.getEmail());
        existingUser.setGender(updateUser.getGender());
        existingUser.setUserStatus(updateUser.getUserStatus());
        existingUser.setHomeAddress(updateUser.getHomeAddress());
        existingUser.setUpdatedAt(LocalDateTime.now());
        
        User updatedUser = userRepository.save(existingUser);
        log.info("用户信息更新成功：{}", userId);
        
        return updatedUser;
    }

    /**
     * 部分更新用户信息（只更新非空字段）
     *
     * @param userId   用户 ID
     * @param updateUser 要更新的用户信息
     * @return 更新后的用户
     */
    @Transactional
    public User patchUpdateUser(String userId, User updateUser) {
        log.info("部分更新用户信息：{}", userId);
        
        User existingUser = getUserById(userId);
        
        // 只更新非空字段
        if (updateUser.getUsername() != null) {
            if (!existingUser.getUsername().equals(updateUser.getUsername()) 
                    && userRepository.existsByUsername(updateUser.getUsername())) {
                throw new RuntimeException(String.format("用户名已存在：%s", updateUser.getUsername()));
            }
            existingUser.setUsername(updateUser.getUsername());
        }
        
        if (updateUser.getNickname() != null) {
            existingUser.setNickname(updateUser.getNickname());
        }
        
        if (updateUser.getPhone() != null) {
            if (!existingUser.getPhone().equals(updateUser.getPhone())
                    && userRepository.existsByPhone(updateUser.getPhone())) {
                throw new RuntimeException(String.format("手机号已存在：%s", updateUser.getPhone()));
            }
            existingUser.setPhone(updateUser.getPhone());
        }
        
        if (updateUser.getEmail() != null) {
            if (!existingUser.getEmail().equals(updateUser.getEmail())
                    && userRepository.existsByEmail(updateUser.getEmail())) {
                throw new RuntimeException(String.format("邮箱已存在：%s", updateUser.getEmail()));
            }
            existingUser.setEmail(updateUser.getEmail());
        }
        
        if (updateUser.getGender() != null) {
            existingUser.setGender(updateUser.getGender());
        }
        
        if (updateUser.getUserStatus() != null) {
            existingUser.setUserStatus(updateUser.getUserStatus());
        }
        
        if (updateUser.getHomeAddress() != null) {
            existingUser.setHomeAddress(updateUser.getHomeAddress());
        }
        
        existingUser.setUpdatedAt(LocalDateTime.now());
        
        User updatedUser = userRepository.save(existingUser);
        log.info("用户信息部分更新成功：{}", userId);
        
        return updatedUser;
    }

    /**
     * 删除用户
     *
     * @param userId 用户 ID
     * @throws RuntimeException 当用户不存在时抛出
     */
    @Transactional
    public void deleteUser(String userId) {
        log.info("删除用户：{}", userId);
        
        if (!userRepository.existsById(userId)) {
            String errorMsg = String.format("用户不存在：%s", userId);
            log.warn(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        
        userRepository.deleteById(userId);
        log.info("用户删除成功：{}", userId);
    }

    /**
     * 检查用户是否存在
     *
     * @param userId 用户 ID
     * @return 是否存在
     */
    public boolean existsById(String userId) {
        return userRepository.existsById(userId);
    }

    /**
     * 根据状态查询用户列表
     *
     * @param status 用户状态
     * @return 用户列表
     */
    public List<User> getUsersByStatus(User.UserStatus status) {
        log.info("查询状态为 {} 的用户列表", status);
        return userRepository.findByUserStatus(status);
    }

    /**
     * 根据昵称模糊查询用户
     *
     * @param nickname 昵称关键词
     * @return 用户列表
     */
    public List<User> searchUsersByNickname(String nickname) {
        log.info("根据昵称搜索用户：{}", nickname);
        return userRepository.findByNicknameContaining(nickname);
    }

    /**
     * 用户登录验证
     *
     * @param username 用户名
     * @param rawPassword 原始密码
     * @return 验证成功返回用户，失败抛出异常
     * @throws RuntimeException 当用户不存在或密码错误时抛出
     */
    public User authenticate(String username, String rawPassword) {
        log.info("用户登录验证：{}", username);
        
        // 查询用户
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    String errorMsg = String.format("用户不存在：%s", username);
                    log.warn(errorMsg);
                    return new RuntimeException(errorMsg);
                });
        if (!rawPassword.equalsIgnoreCase(user.getPassword())){
            String errorMsg = "密码错误";
            log.warn("用户 {} 密码验证失败", username);
            throw new LoginServiceException(errorMsg);
        }
//        // 验证密码
//        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
//            String errorMsg = "密码错误";
//            log.warn("用户 {} 密码验证失败", username);
//            throw new RuntimeException(errorMsg);
//        }
        
        log.info("用户 {} 登录验证成功", username);
        return user;
    }

    /**
     * 构造一个“游客身份”的临时用户对象。
     *
     * <p>该对象不落库，仅用于 login 接口在游客模式下返回统一的数据结构。</p>
     */
    public User buildGuestUser() {
        return User.builder()
                .userId("guest")
                .username("guest")
                .nickname("游客")
                .userStatus(User.UserStatus.ACTIVE)
                .userRole(User.UserRole.GUEST)
                .build();
    }
}
