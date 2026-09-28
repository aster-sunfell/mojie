-- Content and immutable chapter history. PostgreSQL 18 supplies uuidv7().
CREATE TABLE tb_project (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    owner_id text NOT NULL,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    next_chapter_order integer NOT NULL DEFAULT 1 CHECK (next_chapter_order > 0),
    active_run_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE INDEX tb_project_owner_created_idx ON tb_project(owner_id, created_at DESC, id DESC);

CREATE TABLE tb_chapter (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    project_id uuid NOT NULL REFERENCES tb_project(id),
    chapter_order integer NOT NULL CHECK (chapter_order > 0),
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    content text NOT NULL DEFAULT '' CHECK (length(content) <= 6000),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT tb_chapter_project_order_key UNIQUE (project_id, chapter_order),
    CONSTRAINT tb_chapter_project_id_key UNIQUE (project_id, id)
);

CREATE INDEX tb_chapter_project_visible_idx ON tb_chapter(project_id, chapter_order, id) WHERE deleted_at IS NULL;

CREATE TABLE tb_chapter_revision (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    chapter_id uuid NOT NULL REFERENCES tb_chapter(id),
    version integer NOT NULL CHECK (version > 0),
    content text NOT NULL CHECK (length(content) <= 6000),
    source text NOT NULL CHECK (source IN ('CREATE', 'MANUAL', 'AGENT')),
    proposal_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tb_chapter_revision_chapter_version_key UNIQUE (chapter_id, version),
    CONSTRAINT tb_chapter_revision_source_proposal_chk CHECK (
        (source = 'AGENT' AND proposal_id IS NOT NULL)
        OR (source <> 'AGENT' AND proposal_id IS NULL)
    )
);

CREATE UNIQUE INDEX tb_chapter_revision_proposal_key ON tb_chapter_revision(proposal_id)
    WHERE proposal_id IS NOT NULL;

CREATE TABLE tb_setting (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    project_id uuid NOT NULL REFERENCES tb_project(id),
    type text NOT NULL CHECK (type IN ('CHARACTER', 'WORLD', 'RULE')),
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    content text NOT NULL DEFAULT '' CHECK (length(content) <= 2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE INDEX tb_setting_project_type_id_idx ON tb_setting(project_id, type, id) WHERE deleted_at IS NULL;
CREATE INDEX tb_setting_project_id_idx ON tb_setting(project_id, id) WHERE deleted_at IS NULL;
