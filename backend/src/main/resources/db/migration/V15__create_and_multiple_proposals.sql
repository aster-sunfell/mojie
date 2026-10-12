-- CREATE proposals have no base object; IDs are assigned only on author save.
ALTER TABLE tb_edit_proposal DROP CONSTRAINT tb_edit_proposal_run_id_key;
ALTER TABLE tb_edit_proposal ADD COLUMN proposal_index integer NOT NULL DEFAULT 1 CHECK (proposal_index BETWEEN 1 AND 8);
ALTER TABLE tb_edit_proposal ADD UNIQUE(run_id,proposal_index);
ALTER TABLE tb_edit_proposal ADD COLUMN operation text NOT NULL DEFAULT 'UPDATE'
    CHECK (operation IN ('CREATE', 'UPDATE'));
ALTER TABLE tb_edit_proposal ADD COLUMN created_chapter_id uuid;
ALTER TABLE tb_edit_proposal ADD COLUMN created_setting_id uuid;
ALTER TABLE tb_edit_proposal ADD FOREIGN KEY (project_id, created_chapter_id) REFERENCES tb_chapter(project_id, id);
ALTER TABLE tb_edit_proposal ADD FOREIGN KEY (project_id, created_setting_id) REFERENCES tb_setting(project_id, id);
ALTER TABLE tb_edit_proposal DROP CONSTRAINT tb_edit_proposal_base_version_check;
ALTER TABLE tb_edit_proposal ADD CHECK ((operation='CREATE' AND base_version=0 AND base_content='')
    OR (operation='UPDATE' AND base_version>0));
ALTER TABLE tb_edit_proposal DROP CONSTRAINT tb_edit_proposal_target_type_check;
ALTER TABLE tb_edit_proposal ADD CHECK (
    (operation='UPDATE' AND created_chapter_id IS NULL AND created_setting_id IS NULL AND (
        (target_type='CHAPTER' AND target_chapter_id IS NOT NULL AND target_setting_id IS NULL
          AND base_type IS NULL AND base_title IS NULL AND proposed_type IS NULL AND proposed_title IS NULL)
        OR (target_type='SETTING' AND target_chapter_id IS NULL AND target_setting_id IS NOT NULL
          AND base_type IN ('CHARACTER','WORLD','RULE') AND base_type IS NOT NULL
          AND base_title IS NOT NULL AND length(btrim(base_title)) BETWEEN 1 AND 80
          AND proposed_type IS NOT NULL AND proposed_type IN ('CHARACTER','WORLD','RULE')
          AND proposed_title IS NOT NULL AND length(btrim(proposed_title)) BETWEEN 1 AND 80
          AND length(base_content)<=2000 AND length(proposed_content)<=2000)))
    OR (operation='CREATE' AND target_chapter_id IS NULL AND target_setting_id IS NULL
        AND base_type IS NULL AND base_title IS NULL AND proposed_title IS NOT NULL AND (
        (target_type='CHAPTER' AND proposed_type IS NULL AND created_setting_id IS NULL
          AND length(btrim(proposed_title)) BETWEEN 1 AND 120)
        OR (target_type='SETTING' AND created_chapter_id IS NULL
          AND proposed_type IS NOT NULL AND proposed_type IN ('CHARACTER','WORLD','RULE')
          AND length(btrim(proposed_title)) BETWEEN 1 AND 80 AND length(proposed_content)<=2000)))
);
ALTER TABLE tb_edit_proposal ADD CHECK (operation='UPDATE'
    OR (status='APPLIED' AND ((target_type='CHAPTER' AND created_chapter_id IS NOT NULL)
                         OR (target_type='SETTING' AND created_setting_id IS NOT NULL)))
    OR (status IN ('PENDING','REJECTED') AND created_chapter_id IS NULL AND created_setting_id IS NULL));
ALTER TABLE tb_edit_proposal ADD CHECK (applied_version IS NULL OR applied_version>0);
