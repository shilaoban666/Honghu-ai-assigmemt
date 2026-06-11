package com.honghu.ai.assigment.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger / OpenAPI 配置类。
 *
 * <p>这个配置的职责有两部分：</p>
 * <ul>
 *     <li>定义接口文档的基础元数据，例如标题、版本、联系人、环境地址。</li>
 *     <li>补充后台管理接口使用的 Bearer token 安全方案，方便在 Swagger UI 中直接调试管理员接口。</li>
 * </ul>
 *
 * @author shilaoban
 * @since 2026-02-22
 */
@Configuration
public class SwaggerConfig {

    /**
     * 构建全局 OpenAPI 描述。
     *
     * <p>这里注册的 {@code adminBearerAuth} 不会自动给所有接口加鉴权，
     * 它的主要作用是告诉 Swagger UI：后台接口支持在 Authorization 头里放 Bearer token。</p>
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI聊天服务API")
                        .version("1.0.0")
                        .description("基于Spring AI和Ollama的聊天服务接口")
                        .contact(new Contact()
                                .name("开发团队")
                                .email("dev@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://springdoc.org")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("本地开发环境"),
                        new Server()
                                .url("https://api.example.com")
                                .description("生产环境")
                ))
                .components(new Components()
                        .addSecuritySchemes("adminBearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque-token")
                                .description("后台管理接口使用的登录 token。先调用 POST /api/v1/admin/auth/login，再把返回 token 放到 Authorization: Bearer <token>。")));
    }
}
