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
