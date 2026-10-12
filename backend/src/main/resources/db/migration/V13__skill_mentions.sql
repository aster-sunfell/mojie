-- Skill invocation belongs to a message; discovery is always available.
ALTER TABLE tb_conversation DROP COLUMN discovery_enabled;
DROP TABLE tb_conversation_skill;
ALTER TABLE tb_conversation_message ADD COLUMN skill_mentions jsonb NOT NULL DEFAULT '[]';
