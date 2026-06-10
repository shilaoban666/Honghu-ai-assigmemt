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
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "chat_role", nullable = false, length = 20)
    private String chatRole; // 'system', 'user', 'assistant'

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    @Builder.Default
    @Column(name = "status", length = 20)
    private String status = "pending";

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

