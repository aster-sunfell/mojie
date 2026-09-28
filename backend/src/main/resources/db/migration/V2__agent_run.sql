CREATE TABLE tb_agent_prompt_snapshot (
    hash char(64) PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('SYSTEM_PROMPT', 'TOOL_SCHEMA')),
    content text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tb_agent_run (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    owner_id text NOT NULL,
    project_id uuid NOT NULL REFERENCES tb_project(id),
    target_chapter_id uuid NOT NULL,
    instruction text NOT NULL CHECK (length(btrim(instruction)) > 0 AND length(instruction) <= 2000),
    client_request_id uuid NOT NULL,
    request_digest char(64) NOT NULL,
    base_version integer NOT NULL CHECK (base_version > 0),
    base_content text NOT NULL CHECK (length(base_content) <= 6000),
    status text NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    cancel_requested boolean NOT NULL DEFAULT false,
    queued_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    deadline_at timestamptz NOT NULL,
    finished_at timestamptz,
    process_instance_id uuid,
    analysis text,
    error_code text,
    error_detail text,
    model_id text NOT NULL,
    model_call_count integer NOT NULL DEFAULT 0 CHECK (model_call_count BETWEEN 0 AND 8),
    tool_call_count integer NOT NULL DEFAULT 0 CHECK (tool_call_count BETWEEN 0 AND 12),
    tool_text_codepoints integer NOT NULL DEFAULT 0 CHECK (tool_text_codepoints BETWEEN 0 AND 30000),
    last_event_seq bigint NOT NULL DEFAULT 0 CHECK (last_event_seq >= 0),
    prompt_hash char(64) NOT NULL REFERENCES tb_agent_prompt_snapshot(hash),
    tool_schema_hash char(64) NOT NULL REFERENCES tb_agent_prompt_snapshot(hash),
    config_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tb_agent_run_idempotency_key UNIQUE (owner_id, client_request_id),
    CONSTRAINT tb_agent_run_project_id_key UNIQUE (project_id, id),
    CONSTRAINT tb_agent_run_target_scope_fk FOREIGN KEY (project_id, target_chapter_id)
        REFERENCES tb_chapter(project_id, id),
    CONSTRAINT tb_agent_run_base_revision_fk FOREIGN KEY (target_chapter_id, base_version)
        REFERENCES tb_chapter_revision(chapter_id, version),
    CONSTRAINT tb_agent_run_terminal_chk CHECK (
        (status IN ('QUEUED', 'RUNNING') AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL)
    )
);

ALTER TABLE tb_project ADD CONSTRAINT tb_project_active_run_fk
    FOREIGN KEY (id, active_run_id) REFERENCES tb_agent_run(project_id, id);

CREATE INDEX tb_agent_run_project_created_idx ON tb_agent_run(project_id, created_at DESC, id DESC);
CREATE INDEX tb_agent_run_project_chapter_created_idx ON tb_agent_run(project_id, target_chapter_id, created_at DESC, id DESC);
CREATE INDEX tb_agent_run_active_deadline_idx ON tb_agent_run(status, deadline_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE tb_agent_run_chapter_view (
    run_id uuid NOT NULL REFERENCES tb_agent_run(id),
    chapter_id uuid NOT NULL,
    chapter_order integer NOT NULL CHECK (chapter_order > 0),
    version integer NOT NULL CHECK (version > 0),
    view_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, chapter_id),
    CONSTRAINT tb_agent_run_chapter_view_revision_fk FOREIGN KEY (chapter_id, version)
        REFERENCES tb_chapter_revision(chapter_id, version)
);

CREATE INDEX tb_agent_run_chapter_view_order_idx ON tb_agent_run_chapter_view(run_id, chapter_order, chapter_id);

CREATE TABLE tb_agent_context_item (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    run_id uuid NOT NULL REFERENCES tb_agent_run(id),
    tool_execution_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('FULL_CHAPTER', 'FULL_SETTING', 'SEARCH_SNIPPET')),
    chapter_id uuid REFERENCES tb_chapter(id),
    chapter_version integer,
    setting_id uuid REFERENCES tb_setting(id),
    line_number integer CHECK (line_number > 0),
    codepoint_offset integer CHECK (codepoint_offset >= 0),
    content_hash char(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tb_agent_context_item_resource_chk CHECK (
        (chapter_id IS NOT NULL AND setting_id IS NULL AND chapter_version IS NOT NULL)
        OR (chapter_id IS NULL AND setting_id IS NOT NULL AND chapter_version IS NULL AND content_hash IS NOT NULL)
    ),
    CONSTRAINT tb_agent_context_item_revision_fk FOREIGN KEY (chapter_id, chapter_version)
        REFERENCES tb_chapter_revision(chapter_id, version)
);

CREATE INDEX tb_agent_context_item_run_tool_idx ON tb_agent_context_item(run_id, tool_execution_id);
