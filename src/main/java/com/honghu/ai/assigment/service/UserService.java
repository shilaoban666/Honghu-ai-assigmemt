package com.honghu.ai.assigment.service;

import com.honghu.ai.assigment.dto.RegisterRequest;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.exception.LoginServiceException;
import com.honghu.ai.assigment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
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
    /** 统一的密码编码器，由 SecurityConfig 提供（BCrypt）。 */
    private final PasswordEncoder passwordEncoder;
    /** 为微信用户生成不可用随机密码，满足 password 非空约束。 */
    private final SecureRandom secureRandom = new SecureRandom();

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

        // 加密密码：注册一律以 BCrypt 落库，杜绝明文密码。
        if (StringUtils.hasText(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
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
    @Transactional
    public User authenticate(String username, String rawPassword) {
        log.info("用户登录验证：{}", username);

        // 查询用户
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    String errorMsg = String.format("用户不存在：%s", username);
                    log.warn(errorMsg);
                    return new RuntimeException(errorMsg);
                });

        String stored = user.getPassword();
        boolean matched;
        if (isBcryptHash(stored)) {
            // 正常路径：库里已是 BCrypt 哈希，用 matches 做带盐校验。
            matched = passwordEncoder.matches(rawPassword, stored);
        } else {
            // 兼容历史明文密码（含 insert-admin-user.sql 等早期种子数据）：
            // 大小写敏感地常量比较，命中后立即“登录即升级”为 BCrypt，逐步消灭明文。
            matched = stored != null && java.security.MessageDigest.isEqual(
                    rawPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    stored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (matched) {
                user.setPassword(passwordEncoder.encode(rawPassword));
                userRepository.save(user);
                log.info("用户 {} 的历史明文密码已在登录时升级为 BCrypt", username);
            }
        }

        if (!matched) {
            log.warn("用户 {} 密码验证失败", username);
            throw new LoginServiceException("密码错误");
        }

        log.info("用户 {} 登录验证成功", username);
        return user;
    }

    /**
     * 用注册请求创建用户（注册接口入口）。
     *
     * <p>把 DTO 收敛成实体后复用 {@link #createUser(User)}，统一走唯一性校验与密码哈希。</p>
     *
     * @param request 注册请求
     * @return 创建后的用户（密码已 BCrypt）
     */
    @Transactional
    public User registerUser(RegisterRequest request) {
        User user = User.builder()
                .username(request.getUsername())
                .password(request.getPassword())
                .phone(StringUtils.hasText(request.getPhone()) ? request.getPhone() : null)
                .email(StringUtils.hasText(request.getEmail()) ? request.getEmail() : null)
                .nickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : request.getUsername())
                .userStatus(User.UserStatus.ACTIVE)
                .userRole(User.UserRole.USER)
                .build();
        return createUser(user);
    }

    /**
     * 按微信 openid 查找用户，找不到则自动注册一个。
     *
     * <p>微信用户没有平台密码，这里写入一段随机不可用密码以满足非空约束，
     * 即“能微信登录、但无法用账号密码登录”，避免空密码或弱默认密码风险。</p>
     *
     * @param openid   微信 openid（必填）
     * @param unionid  微信 unionid（可空）
     * @param nickname 微信昵称（可空）
     * @return 命中或新建的用户
     */
    @Transactional
    public User findOrCreateWeChatUser(String openid, String unionid, String nickname) {
        if (!StringUtils.hasText(openid)) {
            throw new IllegalArgumentException("微信 openid 不能为空");
        }
        return userRepository.findByWechatOpenid(openid).map(existing -> {
            // 已存在：补全可能新增的 unionid。
            if (StringUtils.hasText(unionid) && !StringUtils.hasText(existing.getWechatUnionid())) {
                existing.setWechatUnionid(unionid);
                userRepository.save(existing);
            }
            return existing;
        }).orElseGet(() -> {
            String safeNickname = StringUtils.hasText(nickname) ? nickname : "微信用户";
            User user = User.builder()
                    .userId(UUID.randomUUID().toString())
                    .username("wx_" + openid)
                    .nickname(safeNickname)
                    .password(passwordEncoder.encode(randomSecret()))
                    .wechatOpenid(openid)
                    .wechatUnionid(StringUtils.hasText(unionid) ? unionid : null)
                    .gender(User.Gender.OTHER)
                    .userStatus(User.UserStatus.ACTIVE)
                    .userRole(User.UserRole.USER)
                    .build();
            User saved = userRepository.save(user);
            workspaceContextService.ensurePersonalWorkspace(saved);
            log.info("微信新用户已创建：openid={}, userId={}", openid, saved.getUserId());
            return saved;
        });
    }

    /** 用户名是否已存在。 */
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    /** 手机号是否已存在。 */
    public boolean existsByPhone(String phone) {
        return userRepository.existsByPhone(phone);
    }

    /** 邮箱是否已存在。 */
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    /** 判断字符串是否为 BCrypt 哈希。 */
    private boolean isBcryptHash(String value) {
        return value != null && (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$"));
    }

    /** 生成一段随机密钥，用作微信用户的占位密码。 */
    private String randomSecret() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
