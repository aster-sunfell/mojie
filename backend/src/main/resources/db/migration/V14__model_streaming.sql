ALTER TABLE tb_agent_event DROP CONSTRAINT tb_agent_event_type_check;
ALTER TABLE tb_agent_event ADD CHECK(type IN (
    'run.queued','run.started','model.started','model.finished','model.text.delta',
    'tool.started','tool.finished','run.succeeded','run.failed','run.cancelled',
    'context.compacted','skill.loaded','script.started','script.finished','artifact.created'
));

CREATE TABLE tb_agent_output (
    run_id uuid NOT NULL,
    call_index integer NOT NULL,
    text text NOT NULL,
    last_event_seq bigint NOT NULL,
    PRIMARY KEY(run_id, call_index),
    FOREIGN KEY(run_id, call_index) REFERENCES tb_agent_model_call(run_id, call_index),
    FOREIGN KEY(run_id, last_event_seq) REFERENCES tb_agent_event(run_id, seq)
);
