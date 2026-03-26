CREATE TABLE chat_message (
    chat_id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,  -- 关联的会话ID
    chat_role VARCHAR(20) NOT NULL,        -- 角色：'user' (你) 或者 'assistant' (AI)
    content_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending', -- 消息状态：'pending'（等待中）
    content TEXT NOT NULL,            -- 聊天内容
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_session_id ON chat_message(session_id); -- 必须建索引，否则查询极慢