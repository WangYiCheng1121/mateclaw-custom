-- V131: Agent ↔ Platform Knowledge Base binding table.
-- Client-side only stores reference IDs + display names.
-- Knowledge base traffic is routed through the platform LLM proxy.
CREATE TABLE IF NOT EXISTS mate_agent_knowledge_base (
    id           BIGINT       NOT NULL PRIMARY KEY,
    agent_id     BIGINT       NOT NULL,
    kb_ref_id    VARCHAR(128) NOT NULL COMMENT 'Platform knowledge base reference ID',
    kb_name      VARCHAR(256) COMMENT 'Platform knowledge base display name (cached for UI)',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_agent_kb_agent ON mate_agent_knowledge_base(agent_id);
CREATE UNIQUE INDEX uk_agent_kb ON mate_agent_knowledge_base(agent_id, kb_ref_id);
