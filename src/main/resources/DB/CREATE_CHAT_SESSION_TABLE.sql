CREATE TABLE chat_session (
    session_id VARCHAR(64) PRIMARY KEY,       -- 会话ID（可以用 UUID）
    user_id VARCHAR(64) NOT NULL,     -- 谁的聊天（未来做多用户鉴权用）
    user_name VARCHAR(255),
    system_role VARCHAR(64),  -- 会话定义的系统角色
    session_name VARCHAR(64),
    session_status VARCHAR(64) DEFAULT 'active', -- 会话状态：'pending'（等待中）、'active'（活跃）、'closed'（已关闭）
    title VARCHAR(255),               -- 聊天的主题（比如“Java高并发探讨”）
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);