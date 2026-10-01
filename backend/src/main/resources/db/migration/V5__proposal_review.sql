ALTER TABLE tb_edit_proposal ADD COLUMN review_decisions jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(review_decisions) = 'object');
ALTER TABLE tb_edit_proposal DROP CONSTRAINT tb_edit_proposal_status_check;
ALTER TABLE tb_edit_proposal ADD CONSTRAINT tb_edit_proposal_status_check
    CHECK (status IN ('PENDING', 'PARTIALLY_APPLIED', 'APPLIED', 'REJECTED', 'CONFLICTED'));
ALTER TABLE tb_edit_proposal DROP CONSTRAINT tb_edit_proposal_applied_version_chk;
ALTER TABLE tb_edit_proposal ADD CONSTRAINT tb_edit_proposal_applied_version_chk CHECK (
    (status = 'APPLIED' AND applied_version IS NOT NULL AND applied_version >= base_version)
    OR (status <> 'APPLIED' AND applied_version IS NULL)
);

DROP INDEX tb_chapter_revision_proposal_key;
CREATE INDEX tb_chapter_revision_proposal_key ON tb_chapter_revision(proposal_id)
    WHERE proposal_id IS NOT NULL;
ALTER TABLE tb_chapter_revision DROP CONSTRAINT tb_chapter_revision_source_check;
ALTER TABLE tb_chapter_revision ADD CONSTRAINT tb_chapter_revision_source_check
    CHECK (source IN ('CREATE', 'MANUAL', 'AGENT', 'MIXED'));
ALTER TABLE tb_chapter_revision DROP CONSTRAINT tb_chapter_revision_source_proposal_chk;
ALTER TABLE tb_chapter_revision ADD CONSTRAINT tb_chapter_revision_source_proposal_chk CHECK (
    (source IN ('AGENT', 'MIXED') AND proposal_id IS NOT NULL)
    OR (source IN ('CREATE', 'MANUAL') AND proposal_id IS NULL)
);

-- One save may incorporate edits from more than one historical suggestion.
CREATE TABLE tb_chapter_revision_review (
    chapter_id uuid NOT NULL,
    version integer NOT NULL,
    proposal_id uuid NOT NULL REFERENCES tb_edit_proposal(id),
    decisions jsonb NOT NULL,
    PRIMARY KEY (chapter_id, version, proposal_id),
    FOREIGN KEY (chapter_id, version) REFERENCES tb_chapter_revision(chapter_id, version)
);
DROP INDEX tb_edit_proposal_pending_chapter_idx;
CREATE INDEX tb_edit_proposal_pending_chapter_idx ON tb_edit_proposal(target_chapter_id, id)
    WHERE status IN ('PENDING', 'PARTIALLY_APPLIED');
