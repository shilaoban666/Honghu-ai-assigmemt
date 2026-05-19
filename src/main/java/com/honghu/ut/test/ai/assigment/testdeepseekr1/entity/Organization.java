package com.honghu.ut.test.ai.assigment.testdeepseekr1.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 组织。
 *
 * <p>组织是 workspace 的上级归属。个人用户注册时也会创建一个个人组织，
 * 这样个人空间和企业空间可以共用同一套数据结构。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "organization")
public class Organization {

    /** 组织 ID。个人组织当前直接使用 userId，企业组织可以使用 UUID。 */
    @Id
    @Column(name = "org_id", length = 64)
    private String orgId;

    /** 组织名称。 */
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** 组织状态，例如 ACTIVE。 */
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
