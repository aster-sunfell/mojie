CREATE INDEX tb_edit_proposal_inbox_project_idx
    ON tb_edit_proposal(project_id,created_at DESC,id DESC)
    WHERE status IN ('PENDING','PARTIALLY_APPLIED','CONFLICTED');

CREATE INDEX tb_agent_run_conversation_idx ON tb_agent_run(conversation_id,id)
    WHERE conversation_id IS NOT NULL;
