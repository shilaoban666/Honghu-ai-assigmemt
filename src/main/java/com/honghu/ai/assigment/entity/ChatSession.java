package com.honghu.ai.assigment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "chat_session")
public class ChatSession {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "system_role", length = 64)
    private String systemRole;

    @Column(name = "session_name", length = 64)
    private String sessionName;

    @Builder.Default
    @Column(name = "session_status", length = 64)
    private String sessionStatus = "active";

    @Column(name = "title")
    private String title;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

