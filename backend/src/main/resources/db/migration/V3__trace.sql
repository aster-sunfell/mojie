CREATE TABLE tb_agent_model_call (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    run_id uuid NOT NULL REFERENCES tb_agent_run(id),
    call_index integer NOT NULL CHECK (call_index BETWEEN 1 AND 8),
    input_message_seqs bigint[] NOT NULL,
    status text NOT NULL CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'ABORTED')),
    finish_reason text,
    raw_finish_reason text,
    provider_request_id text,
    error_code text,
    error_detail text,
    usage jsonb,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CONSTRAINT tb_agent_model_call_run_index_key UNIQUE (run_id, call_index),
    CONSTRAINT tb_agent_model_call_run_id_key UNIQUE (run_id, id),
    CONSTRAINT tb_agent_model_call_finished_chk CHECK (
        (status = 'STARTED' AND finished_at IS NULL)
        OR (status <> 'STARTED' AND finished_at IS NOT NULL)
    )
);

CREATE TABLE tb_agent_message (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    run_id uuid NOT NULL REFERENCES tb_agent_run(id),
    message_seq bigint NOT NULL CHECK (message_seq > 0),
    role text NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
    content jsonb NOT NULL,
    model_call_id uuid,
    tool_call_id text,
    tool_execution_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tb_agent_message_run_seq_key UNIQUE (run_id, message_seq),
    CONSTRAINT tb_agent_message_call_fk FOREIGN KEY (run_id, model_call_id)
        REFERENCES tb_agent_model_call(run_id, id),
    CONSTRAINT tb_agent_message_tool_pair_chk CHECK (
        (role = 'TOOL' AND model_call_id IS NOT NULL AND tool_call_id IS NOT NULL AND tool_execution_id IS NOT NULL)
        OR (role <> 'TOOL' AND tool_call_id IS NULL AND tool_execution_id IS NULL)
    )
);

CREATE INDEX tb_agent_message_run_call_idx ON tb_agent_message(run_id, model_call_id, message_seq);

CREATE TABLE tb_agent_step (
    tool_execution_id uuid PRIMARY KEY DEFAULT uuidv7(),
    run_id uuid NOT NULL REFERENCES tb_agent_run(id),
    model_call_id uuid NOT NULL,
    tool_call_id text NOT NULL CHECK (length(tool_call_id) > 0),
    tool_name text NOT NULL,
    args jsonb,
    result jsonb,
    summary text,
    status text NOT NULL CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'ABORTED')),
    error_code text,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CONSTRAINT tb_agent_step_call_fk FOREIGN KEY (run_id, model_call_id)
        REFERENCES tb_agent_model_call(run_id, id),
    CONSTRAINT tb_agent_step_model_tool_key UNIQUE (model_call_id, tool_call_id),
    CONSTRAINT tb_agent_step_run_execution_key UNIQUE (run_id, tool_execution_id),
    CONSTRAINT tb_agent_step_finished_chk CHECK (
        (status = 'STARTED' AND finished_at IS NULL)
        OR (status <> 'STARTED' AND finished_at IS NOT NULL)
    )
);

CREATE INDEX tb_agent_step_run_started_idx ON tb_agent_step(run_id, started_at, tool_execution_id);
CREATE INDEX tb_agent_step_run_call_idx ON tb_agent_step(run_id, model_call_id, tool_execution_id);

ALTER TABLE tb_agent_context_item ADD CONSTRAINT tb_agent_context_item_step_fk
    FOREIGN KEY (run_id, tool_execution_id) REFERENCES tb_agent_step(run_id, tool_execution_id);

ALTER TABLE tb_agent_message ADD CONSTRAINT tb_agent_message_step_fk
    FOREIGN KEY (run_id, tool_execution_id) REFERENCES tb_agent_step(run_id, tool_execution_id);
