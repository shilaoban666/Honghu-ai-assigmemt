package com.honghu.ai.assigment.skill.entity;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.skill.core.SkillSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 技能主实体，对应数据库 {@code skill} 表。
 *
 * <p>技能是面向用户展示和开关管理的能力容器，它本身不一定是一个具体工具。一个技能可以
 * 来源于后端内置 Java Bean、外部 MCP Server 或未来的用户自定义 HTTP/OpenAPI 工具，
 * 并通过 {@link SkillTool} 关联到一个或多个真正可被模型调用的工具定义。</p>
 *
 * <p>该实体同时承载三类信息：商店展示元数据，例如名称、描述、图标、分类、评分和下载量；
 * 注入规则，例如默认启用、必装、所需角色和全局启用状态；外部 MCP 连接信息，例如传输方式、
 * endpoint、安装命令和环境变量 schema。运行时 {@code SkillResolverService} 会根据这些字段
 * 决定当前会话最终能获得哪些工具。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "skill")
public class Skill {
    // 数据库自增主键，只用于表关联；业务上不要依赖它跨环境稳定。
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 技能的稳定业务 key，例如 time、math、mcp:tavily；前端和接口都应该使用它识别技能。
    @Column(name = "skill_key", nullable = false, unique = true, length = 100)
    private String skillKey;

    // 技能来源：BUILTIN 表示本项目 Java 内置工具，MCP 表示外部 MCP Server，CUSTOM 表示用户自定义。
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private SkillSource source;

    // 商店卡片和详情页显示名称。
    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    // 给用户看的技能简介，也会帮助管理员理解这个 Skill 容器的作用。
    // 对全网爬取的 MCP / Claude Skill，这里保存英文原文，便于变更检测与重译。
    @Column(name = "description")
    private String description;

    // 中文翻译后的描述；爬取时由 LLM 生成。展示层优先用它，没有时回退 description。
    @Column(name = "description_zh", columnDefinition = "text")
    private String descriptionZh;

    // 图标 URL 或图标占位文本；内置技能当前也复用这个字段存图标文本。
    @Column(name = "icon_url", length = 500)
    private String iconUrl;

    // 技能分类，用于商店筛选和详情页标签。
    @Column(name = "category", length = 50)
    private String category;

    // 新会话没有显式设置时是否默认启用；用户仍然可以在会话级别关闭。
    @Column(name = "default_enabled")
    private boolean defaultEnabled;

    // 必装技能永远注入当前会话，UI 应显示锁定且后端也会强制补回。
    @Column(name = "mandatory")
    private boolean mandatory;

    // 使用该技能所需的最低角色；SkillResolverService 会把高等级角色映射为包含低等级能力。
    @Enumerated(EnumType.STRING)
    @Column(name = "required_role", length = 20)
    private User.UserRole requiredRole;

    // 外部技能版本；内置技能可以为空或使用应用版本。
    @Column(name = "version", length = 20)
    private String version;

    // 技能作者，用于 MCP 市场详情页展示。
    @Column(name = "author", length = 100)
    private String author;

    // 仓库地址或文档地址，方便用户查看外部技能来源。
    @Column(name = "repo_url", length = 500)
    private String repoUrl;

    // 开源许可证标签，例如 MIT、Apache-2.0。
    @Column(name = "license", length = 50)
    private String license;

    // 市场评分，主要给 MCP 市场使用；内置技能可为空。
    @Column(name = "rating")
    private BigDecimal rating;

    // 下载量或安装量，主要给 MCP 市场使用。
    @Column(name = "downloads")
    private Integer downloads;

    // MCP 连接方式，例如 stdio、sse、streamable-http。
    @Column(name = "mcp_transport", length = 20)
    private String mcpTransport;

    // 远程 MCP 地址；stdio 类型可以为空。
    @Column(name = "mcp_endpoint", length = 500)
    private String mcpEndpoint;

    // stdio MCP 的安装命令或启动命令；生产环境普通用户不应该直接执行。
    @Column(name = "mcp_install_cmd")
    private String mcpInstallCmd;

    // MCP 需要的环境变量 schema，例如 API Key 名称、是否必填和说明。
    @Column(name = "mcp_env_schema", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String mcpEnvSchema;

    // 管理员级全局开关；false 时即使用户安装或会话启用，也不会注入给模型。
    @Column(name = "enabled")
    private boolean enabled;

    // 创建时间由 Hibernate 自动写入，方便市场排序和审计。
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // 更新时间由 Hibernate 自动维护，启动注册器更新内置技能时也会刷新。
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
