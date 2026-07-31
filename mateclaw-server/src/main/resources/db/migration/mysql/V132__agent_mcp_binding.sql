-- V132: Agent ↔ Platform MCP binding table.
-- Client-side only stores reference IDs + display names.
-- MCP traffic is routed through the platform LLM proxy.
CREATE TABLE IF NOT EXISTS mate_agent_mcp (
    id           BIGINT       NOT NULL PRIMARY KEY,
    agent_id     BIGINT       NOT NULL,
    mcp_ref_id   INT          NOT NULL COMMENT 'Platform MCP reference ID',
    mcp_name     VARCHAR(256) COMMENT 'Platform MCP display name (cached for UI)',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0,
    INDEX idx_agent_mcp_agent (agent_id),
    UNIQUE KEY uk_agent_mcp (agent_id, mcp_ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
