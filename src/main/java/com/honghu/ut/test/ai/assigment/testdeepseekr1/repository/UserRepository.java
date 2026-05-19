package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户数据访问层
 *
 * @author shilaoban
 * @since 2026-03-11
 */
@Repository
public interface UserRepository extends JpaRepository<User, String> {
    
    /**
     * 根据用户 ID 查找用户
     * 继承自 JpaRepository 的 findById 方法
     *
     * @param userId 用户 ID
     * @return 用户 Optional
     */
    // Optional<User> findById(String userId); // 已继承，无需重复定义
    
    /**
     * 根据用户名查找用户
     *
     * @param username 用户名
     * @return 用户 Optional
     */
    Optional<User> findByUsername(String username);

    /**
     * 根据手机号查找用户
     *
     * @param phone 手机号
     * @return 用户 Optional
     */
    Optional<User> findByPhone(String phone);

    /**
     * 根据邮箱查找用户
     *
     * @param email 邮箱
     * @return 用户 Optional
     */
    Optional<User> findByEmail(String email);

    /**
     * 根据昵称查找用户列表
     *
     * @param nickname 昵称
     * @return 用户列表
     */
    List<User> findByNicknameContaining(String nickname);

    /**
     * 根据用户状态查找用户列表
     *
     * @param status 用户状态
     * @return 用户列表
     */
    List<User> findByUserStatus(User.UserStatus status);

    /**
     * 检查用户名是否存在
     *
     * @param username 用户名
     * @return 是否存在
     */
    boolean existsByUsername(String username);

    /**
     * 检查手机号是否存在
     *
     * @param phone 手机号
     * @return 是否存在
     */
    boolean existsByPhone(String phone);

    /**
     * 检查邮箱是否存在
     *
     * @param email 邮箱
     * @return 是否存在
     */
    boolean existsByEmail(String email);

    long countByUserRole(User.UserRole userRole);

    @Query(value = """
            select *
            from users u
            where (cast(:q as text) is null
                or lower(cast(u.username as text)) like concat('%', lower(cast(:q as text)), '%')
                or lower(coalesce(cast(u.nickname as text), '')) like concat('%', lower(cast(:q as text)), '%')
                or lower(coalesce(cast(u.email as text), '')) like concat('%', lower(cast(:q as text)), '%'))
              and (cast(:role as text) is null or cast(u.user_role as text) = cast(:role as text))
            order by u.created_at desc
            """,
            countQuery = """
            select count(*)
            from users u
            where (cast(:q as text) is null
                or lower(cast(u.username as text)) like concat('%', lower(cast(:q as text)), '%')
                or lower(coalesce(cast(u.nickname as text), '')) like concat('%', lower(cast(:q as text)), '%')
                or lower(coalesce(cast(u.email as text), '')) like concat('%', lower(cast(:q as text)), '%'))
              and (cast(:role as text) is null or cast(u.user_role as text) = cast(:role as text))
            """,
            nativeQuery = true)
    Page<User> searchAdminUsers(@Param("q") String q,
                                @Param("role") String role,
                                Pageable pageable);
}
