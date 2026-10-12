ALTER TABLE tb_setting ADD COLUMN version integer NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE tb_setting ADD CONSTRAINT tb_setting_project_id_key UNIQUE (project_id, id);

CREATE TABLE tb_setting_revision (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    setting_id uuid NOT NULL REFERENCES tb_setting(id),
    version integer NOT NULL CHECK (version > 0),
    type text NOT NULL CHECK (type IN ('CHARACTER', 'WORLD', 'RULE')),
    title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 80),
    content text NOT NULL CHECK (length(content) <= 2000),
    source text NOT NULL CHECK (source IN ('CREATE', 'MANUAL', 'AGENT')),
    proposal_id uuid REFERENCES tb_edit_proposal(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (setting_id, version),
    CHECK ((source = 'AGENT' AND proposal_id IS NOT NULL)
        OR (source <> 'AGENT' AND proposal_id IS NULL))
);
CREATE UNIQUE INDEX tb_setting_revision_proposal_key ON tb_setting_revision(proposal_id)
    WHERE proposal_id IS NOT NULL;
INSERT INTO tb_setting_revision(setting_id, version, type, title, content, source)
    SELECT id, version, type, title, content, 'CREATE' FROM tb_setting;

ALTER TABLE tb_edit_proposal ALTER COLUMN target_chapter_id DROP NOT NULL;
ALTER TABLE tb_edit_proposal ADD COLUMN target_type text NOT NULL DEFAULT 'CHAPTER';
ALTER TABLE tb_edit_proposal ADD COLUMN target_setting_id uuid;
ALTER TABLE tb_edit_proposal ADD COLUMN base_type text;
ALTER TABLE tb_edit_proposal ADD COLUMN base_title text;
ALTER TABLE tb_edit_proposal ADD COLUMN proposed_type text;
ALTER TABLE tb_edit_proposal ADD COLUMN proposed_title text;
ALTER TABLE tb_edit_proposal ADD CONSTRAINT tb_edit_proposal_setting_scope_fk
    FOREIGN KEY (project_id, target_setting_id) REFERENCES tb_setting(project_id, id);
ALTER TABLE tb_edit_proposal ADD CONSTRAINT tb_edit_proposal_setting_revision_fk
    FOREIGN KEY (target_setting_id, base_version) REFERENCES tb_setting_revision(setting_id, version);
ALTER TABLE tb_edit_proposal ADD CONSTRAINT tb_edit_proposal_target_type_check CHECK (
    (target_type = 'CHAPTER' AND target_chapter_id IS NOT NULL AND target_setting_id IS NULL
        AND base_type IS NULL AND base_title IS NULL AND proposed_type IS NULL AND proposed_title IS NULL)
    OR (target_type = 'SETTING' AND target_chapter_id IS NULL AND target_setting_id IS NOT NULL
        AND base_type IS NOT NULL AND base_type IN ('CHARACTER','WORLD','RULE')
        AND proposed_type IS NOT NULL AND proposed_type IN ('CHARACTER','WORLD','RULE')
        AND base_title IS NOT NULL AND length(btrim(base_title)) BETWEEN 1 AND 80
        AND proposed_title IS NOT NULL AND length(btrim(proposed_title)) BETWEEN 1 AND 80
        AND length(base_content) <= 2000 AND length(proposed_content) <= 2000)
);

-- Preserve conversation history while removing the retired CHAT task contract.
ALTER TABLE tb_agent_run DROP CONSTRAINT tb_agent_run_task_type_check;
ALTER TABLE tb_agent_run DROP CONSTRAINT tb_agent_run_legacy_target_check;
UPDATE tb_agent_run SET task_type = 'ASSISTANT' WHERE task_type = 'CHAT';
ALTER TABLE tb_agent_run ADD CONSTRAINT tb_agent_run_task_type_check
    CHECK (task_type IN ('LEGACY','CHAPTER_REVIEW','CHAPTER_EDIT','ASSISTANT'));
ALTER TABLE tb_agent_run ADD CONSTRAINT tb_agent_run_legacy_target_check
    CHECK (task_type = 'ASSISTANT'
        OR (target_chapter_id IS NOT NULL AND base_version IS NOT NULL AND base_content IS NOT NULL));
ALTER TABLE tb_agent_run ADD COLUMN analysis_only boolean NOT NULL DEFAULT false;
