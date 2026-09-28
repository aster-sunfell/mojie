CREATE TABLE tb_edit_proposal (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    run_id uuid NOT NULL UNIQUE REFERENCES tb_agent_run(id),
    project_id uuid NOT NULL,
    target_chapter_id uuid NOT NULL,
    base_version integer NOT NULL CHECK (base_version > 0),
    base_content text NOT NULL CHECK (length(base_content) <= 6000),
    proposed_content text NOT NULL CHECK (length(btrim(proposed_content)) > 0 AND length(proposed_content) <= 6000),
    reason text NOT NULL CHECK (length(btrim(reason)) > 0 AND length(reason) <= 2000),
    status text NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED', 'CONFLICTED')),
    applied_version integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tb_edit_proposal_run_scope_fk FOREIGN KEY (project_id, run_id)
        REFERENCES tb_agent_run(project_id, id),
    CONSTRAINT tb_edit_proposal_target_scope_fk FOREIGN KEY (project_id, target_chapter_id)
        REFERENCES tb_chapter(project_id, id),
    CONSTRAINT tb_edit_proposal_base_revision_fk FOREIGN KEY (target_chapter_id, base_version)
        REFERENCES tb_chapter_revision(chapter_id, version),
    CONSTRAINT tb_edit_proposal_applied_version_chk CHECK (
        (status = 'APPLIED' AND applied_version IS NOT NULL AND applied_version > base_version)
        OR (status <> 'APPLIED' AND applied_version IS NULL)
    )
);

CREATE INDEX tb_edit_proposal_pending_chapter_idx ON tb_edit_proposal(target_chapter_id, id)
    WHERE status = 'PENDING';

ALTER TABLE tb_chapter_revision ADD CONSTRAINT tb_chapter_revision_proposal_fk
    FOREIGN KEY (proposal_id) REFERENCES tb_edit_proposal(id);

CREATE TABLE tb_agent_event (
    run_id uuid NOT NULL REFERENCES tb_agent_run(id),
    seq bigint NOT NULL CHECK (seq > 0),
    type text NOT NULL CHECK (type IN (
        'run.queued', 'run.started', 'model.started', 'model.finished',
        'tool.started', 'tool.finished', 'run.succeeded', 'run.failed', 'run.cancelled'
    )),
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);
