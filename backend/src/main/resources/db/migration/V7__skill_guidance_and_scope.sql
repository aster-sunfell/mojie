ALTER TABLE tb_run_skill ADD COLUMN guidance jsonb;
ALTER TABLE tb_run_skill ADD COLUMN guidance_hash char(64);
ALTER TABLE tb_conversation ADD CHECK(last_seq>=0 AND version>0);
ALTER TABLE tb_agent_run ADD UNIQUE(conversation_id,id);
ALTER TABLE tb_conversation ADD FOREIGN KEY(id,active_run_id) REFERENCES tb_agent_run(conversation_id,id);
ALTER TABLE tb_conversation_message ADD FOREIGN KEY(conversation_id,run_id) REFERENCES tb_agent_run(conversation_id,id);
ALTER TABLE tb_agent_message ADD FOREIGN KEY(conversation_id,run_id) REFERENCES tb_agent_run(conversation_id,id);
