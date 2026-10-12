-- Keep legacy task contracts while new turns use optional context and tool-selected edit targets.
DO $$
DECLARE item record;
BEGIN
  FOR item IN SELECT conname FROM pg_constraint
    WHERE conrelid = 'tb_agent_run'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%task_type%'
  LOOP
    EXECUTE format('ALTER TABLE tb_agent_run DROP CONSTRAINT %I', item.conname);
  END LOOP;
END $$;
ALTER TABLE tb_agent_run ADD CONSTRAINT tb_agent_run_task_type_check
  CHECK (task_type IN ('LEGACY','CHAT','CHAPTER_REVIEW','CHAPTER_EDIT','ASSISTANT'));
ALTER TABLE tb_agent_run ADD CONSTRAINT tb_agent_run_legacy_target_check
  CHECK (task_type IN ('CHAT','ASSISTANT')
    OR (target_chapter_id IS NOT NULL AND base_version IS NOT NULL AND base_content IS NOT NULL));
ALTER TABLE tb_agent_run ADD COLUMN context_chapter_id uuid;
ALTER TABLE tb_agent_run ADD COLUMN context_version integer;
ALTER TABLE tb_agent_run ADD COLUMN context_content text;
ALTER TABLE tb_agent_run ADD CONSTRAINT tb_agent_run_context_snapshot_check
  CHECK ((context_chapter_id IS NULL AND context_version IS NULL AND context_content IS NULL)
    OR (task_type = 'ASSISTANT' AND context_chapter_id IS NOT NULL
      AND context_version IS NOT NULL AND context_version > 0 AND context_content IS NOT NULL));
ALTER TABLE tb_agent_run ADD CONSTRAINT tb_agent_run_context_scope_fk
  FOREIGN KEY (project_id,context_chapter_id) REFERENCES tb_chapter(project_id,id);
ALTER TABLE tb_agent_run ADD CONSTRAINT tb_agent_run_context_revision_fk
  FOREIGN KEY (context_chapter_id,context_version) REFERENCES tb_chapter_revision(chapter_id,version);
