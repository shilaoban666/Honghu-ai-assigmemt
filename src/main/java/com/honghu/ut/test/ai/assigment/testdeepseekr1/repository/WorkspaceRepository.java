package com.honghu.ut.test.ai.assigment.testdeepseekr1.repository;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.Workspace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * workspace 仓库。
 *
 * <p>除了基础 CRUD，这里主要提供后台分页筛选能力，方便按套餐和关键字检索团队空间。</p>
 */
@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    /** 按套餐编码分页查看 workspace，主要给后台套餐视角使用。 */
    Page<Workspace> findByPlanCode(String planCode, Pageable pageable);

    /** 后台 workspace 检索：支持按 planCode 过滤，并按名称或 workspaceId 关键字搜索。 */
    @Query(value = """
            select *
            from workspace w
            where (cast(:planCode as text) is null or cast(w.plan_code as text) = cast(:planCode as text))
              and (cast(:q as text) is null
                or lower(cast(w.name as text)) like concat('%', lower(cast(:q as text)), '%')
                or lower(cast(w.workspace_id as text)) like concat('%', lower(cast(:q as text)), '%'))
            order by w.created_at desc
            """,
            countQuery = """
            select count(*)
            from workspace w
            where (cast(:planCode as text) is null or cast(w.plan_code as text) = cast(:planCode as text))
              and (cast(:q as text) is null
                or lower(cast(w.name as text)) like concat('%', lower(cast(:q as text)), '%')
                or lower(cast(w.workspace_id as text)) like concat('%', lower(cast(:q as text)), '%'))
            """,
            nativeQuery = true)
    Page<Workspace> searchAdminWorkspaces(@Param("q") String q,
                                          @Param("planCode") String planCode,
                                          Pageable pageable);
}
