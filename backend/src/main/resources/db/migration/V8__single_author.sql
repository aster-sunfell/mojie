-- Preserve all resources and refuse to silently rewrite historical idempotency keys.
DO $$
DECLARE
    conflicting_keys text;
BEGIN
    SELECT string_agg(client_request_id::text, ', ' ORDER BY client_request_id::text)
      INTO conflicting_keys
      FROM (SELECT client_request_id FROM tb_agent_run
             GROUP BY client_request_id HAVING count(*) > 1) conflicts;
    IF conflicting_keys IS NOT NULL THEN
        RAISE EXCEPTION 'Cannot remove owner identity: duplicate client_request_id values: %', conflicting_keys;
    END IF;
END $$;

ALTER TABLE tb_agent_run DROP CONSTRAINT tb_agent_run_idempotency_key;
ALTER TABLE tb_agent_run ADD CONSTRAINT tb_agent_run_idempotency_key UNIQUE (client_request_id);
DROP INDEX tb_project_owner_created_idx;
CREATE INDEX tb_project_updated_idx ON tb_project(updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
ALTER TABLE tb_agent_run DROP COLUMN owner_id;
ALTER TABLE tb_project DROP COLUMN owner_id;

-- Conversations and Skills share this instance's single author as well.
DO $$
DECLARE conflicting_names text;
BEGIN
    SELECT string_agg(name, ', ' ORDER BY name) INTO conflicting_names
      FROM (SELECT name FROM tb_skill WHERE enabled AND deleted_at IS NULL
             GROUP BY name HAVING count(*) > 1) conflicts;
    IF conflicting_names IS NOT NULL THEN
        RAISE EXCEPTION 'Cannot remove owner identity: duplicate enabled Skill names: %', conflicting_names;
    END IF;
END $$;

ALTER TABLE tb_conversation DROP COLUMN owner_id;
CREATE INDEX tb_conversation_project_created_idx ON tb_conversation(project_id, created_at DESC, id DESC);
ALTER TABLE tb_skill DROP COLUMN owner_id;
CREATE UNIQUE INDEX tb_skill_enabled_name ON tb_skill(name) WHERE enabled AND deleted_at IS NULL;
ALTER TABLE tb_skill_upload DROP COLUMN owner_id;
ALTER TABLE tb_artifact DROP COLUMN owner_id;

-- Preserve all storage accounting in one instance-wide quota row.
CREATE TEMP TABLE mojie_single_author_quota ON COMMIT DROP AS
    SELECT COALESCE(sum(used_bytes), 0)::bigint AS used_bytes,
           COALESCE(sum(reserved_bytes), 0)::bigint AS reserved_bytes FROM tb_skill_quota;
DELETE FROM tb_skill_quota;
ALTER TABLE tb_skill_quota DROP COLUMN owner_id;
ALTER TABLE tb_skill_quota ADD COLUMN singleton boolean NOT NULL DEFAULT true CHECK (singleton) PRIMARY KEY;
INSERT INTO tb_skill_quota(singleton, used_bytes, reserved_bytes)
    SELECT true, used_bytes, reserved_bytes FROM mojie_single_author_quota;
