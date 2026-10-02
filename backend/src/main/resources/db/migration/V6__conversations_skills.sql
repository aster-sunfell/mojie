CREATE TABLE tb_conversation (
 id uuid PRIMARY KEY DEFAULT uuidv7(), owner_id text NOT NULL, project_id uuid NOT NULL REFERENCES tb_project(id),
 title text NOT NULL DEFAULT '新对话' CHECK(length(title) BETWEEN 1 AND 100), status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')),
 last_seq bigint NOT NULL DEFAULT 0, version bigint NOT NULL DEFAULT 1, active_run_id uuid,
 model_id text NOT NULL, model_config jsonb NOT NULL, discovery_enabled boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(project_id,id)
);
CREATE INDEX ON tb_conversation(owner_id,project_id,created_at DESC,id DESC);
ALTER TABLE tb_agent_run ALTER COLUMN target_chapter_id DROP NOT NULL;
ALTER TABLE tb_agent_run ALTER COLUMN base_version DROP NOT NULL;
ALTER TABLE tb_agent_run ALTER COLUMN base_content DROP NOT NULL;
ALTER TABLE tb_agent_run DROP CONSTRAINT tb_agent_run_instruction_check;
ALTER TABLE tb_agent_run ADD CHECK(length(btrim(instruction)) > 0 AND length(instruction) <= 8000);
ALTER TABLE tb_agent_run DROP CONSTRAINT tb_agent_run_model_call_count_check;
ALTER TABLE tb_agent_run DROP CONSTRAINT tb_agent_run_tool_call_count_check;
ALTER TABLE tb_agent_run DROP CONSTRAINT tb_agent_run_tool_text_codepoints_check;
ALTER TABLE tb_agent_run ADD COLUMN conversation_id uuid;
ALTER TABLE tb_agent_run ADD COLUMN trigger_message_id uuid;
ALTER TABLE tb_agent_run ADD COLUMN retry_of uuid REFERENCES tb_agent_run(id);
ALTER TABLE tb_agent_run ADD COLUMN input_boundary bigint;
ALTER TABLE tb_agent_run ADD COLUMN task_type text NOT NULL DEFAULT 'LEGACY' CHECK(task_type IN ('LEGACY','CHAT','CHAPTER_REVIEW','CHAPTER_EDIT'));
ALTER TABLE tb_agent_run ADD FOREIGN KEY(project_id,conversation_id) REFERENCES tb_conversation(project_id,id);
ALTER TABLE tb_agent_run ADD CHECK(task_type = 'CHAT' OR (target_chapter_id IS NOT NULL AND base_version IS NOT NULL AND base_content IS NOT NULL));
ALTER TABLE tb_agent_run ADD CHECK(model_call_count BETWEEN 0 AND COALESCE((config_snapshot->>'maxModelCalls')::int,8));
ALTER TABLE tb_agent_run ADD CHECK(tool_call_count BETWEEN 0 AND COALESCE((config_snapshot->>'maxToolCalls')::int,12));
ALTER TABLE tb_agent_run ADD CHECK(tool_text_codepoints BETWEEN 0 AND COALESCE((config_snapshot->>'maxToolTextCodePoints')::int,30000));
ALTER TABLE tb_agent_model_call DROP CONSTRAINT tb_agent_model_call_call_index_check;
ALTER TABLE tb_agent_model_call ADD CHECK(call_index > 0);
ALTER TABLE tb_agent_model_call ADD COLUMN phase text NOT NULL DEFAULT 'TASK';
CREATE TABLE tb_conversation_message (
 id uuid PRIMARY KEY DEFAULT uuidv7(), conversation_id uuid NOT NULL REFERENCES tb_conversation(id), seq bigint NOT NULL,
 role text NOT NULL CHECK(role IN ('USER','ASSISTANT')), content text NOT NULL, run_id uuid REFERENCES tb_agent_run(id),
 status text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(conversation_id,seq), UNIQUE(conversation_id,id)
);
ALTER TABLE tb_agent_run ADD FOREIGN KEY(conversation_id,trigger_message_id) REFERENCES tb_conversation_message(conversation_id,id);
ALTER TABLE tb_conversation ADD FOREIGN KEY(project_id,active_run_id) REFERENCES tb_agent_run(project_id,id);
ALTER TABLE tb_agent_message ADD COLUMN conversation_id uuid REFERENCES tb_conversation(id);
CREATE TABLE tb_agent_input_snapshot (
 id uuid PRIMARY KEY DEFAULT uuidv7(), run_id uuid NOT NULL REFERENCES tb_agent_run(id), call_index int NOT NULL,
 hash char(64) NOT NULL, request jsonb NOT NULL, source_message_ids jsonb NOT NULL DEFAULT '[]', created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(run_id,call_index), FOREIGN KEY(run_id,call_index) REFERENCES tb_agent_model_call(run_id,call_index)
);
CREATE TABLE tb_conversation_compaction (
 id uuid PRIMARY KEY DEFAULT uuidv7(), conversation_id uuid NOT NULL REFERENCES tb_conversation(id), run_id uuid NOT NULL REFERENCES tb_agent_run(id),
 covered_seq bigint NOT NULL, summary text NOT NULL, parent_id uuid REFERENCES tb_conversation_compaction(id), model_id text NOT NULL,
 snapshot_id uuid REFERENCES tb_agent_input_snapshot(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE tb_skill (
 id uuid PRIMARY KEY DEFAULT uuidv7(), owner_id text NOT NULL, name text NOT NULL, enabled boolean NOT NULL DEFAULT false,
 current_version_id uuid, deleted_at timestamptz, version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX tb_skill_enabled_name ON tb_skill(owner_id,name) WHERE enabled AND deleted_at IS NULL;
CREATE TABLE tb_skill_upload (
 id uuid PRIMARY KEY, owner_id text NOT NULL, skill_id uuid, version_id uuid, status text NOT NULL CHECK(status IN ('UPLOADING','VALIDATING','READY','FAILED')),
 reserved_bytes bigint NOT NULL DEFAULT 0, error_code text, error_detail text, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE tb_skill_version (
 id uuid PRIMARY KEY, skill_id uuid NOT NULL REFERENCES tb_skill(id), package_hash char(64) NOT NULL, storage_key text NOT NULL UNIQUE,
 status text NOT NULL CHECK(status IN ('READY','UNAVAILABLE')), metadata jsonb NOT NULL, size_bytes bigint NOT NULL, file_count int NOT NULL,
 compatibility jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(skill_id,id)
);
ALTER TABLE tb_skill ADD FOREIGN KEY(id,current_version_id) REFERENCES tb_skill_version(skill_id,id);
CREATE TABLE tb_skill_file (
 id uuid PRIMARY KEY, version_id uuid NOT NULL REFERENCES tb_skill_version(id), relative_path text NOT NULL,
 hash char(64) NOT NULL, size_bytes bigint NOT NULL, mime text NOT NULL, UNIQUE(version_id,relative_path)
);
CREATE TABLE tb_conversation_skill (
 conversation_id uuid NOT NULL REFERENCES tb_conversation(id), skill_id uuid NOT NULL REFERENCES tb_skill(id), version_id uuid NOT NULL,
 PRIMARY KEY(conversation_id,skill_id), FOREIGN KEY(skill_id,version_id) REFERENCES tb_skill_version(skill_id,id)
);
CREATE TABLE tb_run_skill (
 run_id uuid NOT NULL REFERENCES tb_agent_run(id), skill_id uuid NOT NULL REFERENCES tb_skill(id), version_id uuid NOT NULL,
 source text NOT NULL, loaded boolean NOT NULL DEFAULT false, provided_to_model boolean NOT NULL DEFAULT false,
 loaded_message_seq bigint, PRIMARY KEY(run_id,skill_id), FOREIGN KEY(skill_id,version_id) REFERENCES tb_skill_version(skill_id,id)
);
CREATE TABLE tb_skill_execution (
 id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES tb_agent_run(id), tool_execution_id uuid NOT NULL REFERENCES tb_agent_step(tool_execution_id),
 version_id uuid NOT NULL REFERENCES tb_skill_version(id), script text NOT NULL, arguments jsonb NOT NULL, inputs jsonb NOT NULL,
 environment jsonb NOT NULL, status text NOT NULL, exit_code int, stdout text, stderr text, error_code text,
 started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz
);
CREATE TABLE tb_artifact (
 id uuid PRIMARY KEY, owner_id text NOT NULL, project_id uuid NOT NULL REFERENCES tb_project(id), run_id uuid NOT NULL REFERENCES tb_agent_run(id),
 execution_id uuid NOT NULL REFERENCES tb_skill_execution(id), name text NOT NULL, storage_key text NOT NULL UNIQUE,
 hash char(64) NOT NULL, size_bytes bigint NOT NULL, mime text NOT NULL, expires_at timestamptz NOT NULL DEFAULT now() + interval '30 days',
 status text NOT NULL DEFAULT 'READY', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE tb_skill_quota (owner_id text PRIMARY KEY, used_bytes bigint NOT NULL DEFAULT 0, reserved_bytes bigint NOT NULL DEFAULT 0 CHECK(reserved_bytes >= 0));

CREATE FUNCTION conversation_run_terminal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE next_seq bigint;
BEGIN
 IF NEW.conversation_id IS NOT NULL AND OLD.status IN ('QUEUED','RUNNING') AND NEW.status IN ('SUCCEEDED','FAILED','CANCELLED') THEN
  UPDATE tb_conversation_message SET status = NEW.status, run_id = NEW.id WHERE id = NEW.trigger_message_id;
  UPDATE tb_conversation SET active_run_id = NULL, updated_at = now(), version = version + 1,
   last_seq = last_seq + CASE WHEN NEW.status = 'SUCCEEDED' THEN 1 ELSE 0 END
   WHERE id = NEW.conversation_id AND active_run_id = NEW.id RETURNING last_seq INTO next_seq;
  IF NEW.status = 'SUCCEEDED' THEN
   INSERT INTO tb_conversation_message(conversation_id,seq,role,content,run_id,status)
    VALUES(NEW.conversation_id,next_seq,'ASSISTANT',NEW.analysis,NEW.id,NEW.status);
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER conversation_run_terminal AFTER UPDATE OF status ON tb_agent_run FOR EACH ROW EXECUTE FUNCTION conversation_run_terminal();

-- A historical run is an archived, isolated conversation; preserve its original protocol and budgets.
INSERT INTO tb_conversation(id,owner_id,project_id,title,status,model_id,model_config,last_seq)
 SELECT id,owner_id,project_id,'历史任务','ARCHIVED',model_id,config_snapshot,CASE WHEN analysis IS NULL THEN 1 ELSE 2 END FROM tb_agent_run;
INSERT INTO tb_conversation_message(conversation_id,seq,role,content,run_id,status)
 SELECT id,1,'USER',instruction,id,status FROM tb_agent_run;
INSERT INTO tb_conversation_message(conversation_id,seq,role,content,run_id,status)
 SELECT id,2,'ASSISTANT',analysis,id,status FROM tb_agent_run WHERE analysis IS NOT NULL;
UPDATE tb_agent_run r SET conversation_id = r.id,input_boundary = 0,trigger_message_id = m.id
 FROM tb_conversation_message m WHERE m.conversation_id = r.id AND m.seq = 1;
UPDATE tb_conversation c SET active_run_id=r.id FROM tb_agent_run r WHERE c.id=r.id AND r.status IN ('QUEUED','RUNNING');
UPDATE tb_agent_message m SET conversation_id = r.conversation_id FROM tb_agent_run r WHERE r.id = m.run_id;

ALTER TABLE tb_agent_event DROP CONSTRAINT tb_agent_event_type_check;
ALTER TABLE tb_agent_event ADD CHECK(type IN ('run.queued','run.started','model.started','model.finished','tool.started','tool.finished','run.succeeded','run.failed','run.cancelled','context.compacted','skill.loaded','script.started','script.finished','artifact.created'));
