-- V131: Agent ↔ Platform Knowledge Base binding table.
-- Client-side only stores reference IDs + display names.
-- Knowledge base traffic is routed through the platform LLM proxy.
CREATE TABLE IF NOT EXISTS mate_agent_knowledge_base (
    id           BIGINT       NOT NULL PRIMARY KEY,
    agent_id     BIGINT       NOT NULL,
    kb_ref_id    VARCHAR(128) NOT NULL COMMENT 'Platform knowledge base reference ID',
    kb_name      VARCHAR(256) COMMENT 'Platform knowledge base display name (cached for UI)',
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agent_kb_agent ON mate_agent_knowledge_base(agent_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_kb ON mate_agent_knowledge_base(agent_id, kb_ref_id);
