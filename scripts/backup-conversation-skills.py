#!/usr/bin/env python3
"""Offline coordinated backup. Stops writers, creates PG dump and file archive, resumes writers."""
import argparse
import json
import pathlib
import subprocess
import tarfile

p = argparse.ArgumentParser()
p.add_argument('--compose-file', default='deploy/compose.prod.yaml')
p.add_argument('--env-file', default='deploy/.env.prod')
p.add_argument('--project', default='mojie-prod')
p.add_argument('--database', default='mojie')
p.add_argument('--db-user', default='mojie')
p.add_argument('--output', required=True)
a = p.parse_args()
out = pathlib.Path(a.output).resolve()
out.mkdir(parents=True, exist_ok=False)
compose = ['docker', 'compose', '--env-file', a.env_file, '-f', a.compose_file, '-p', a.project]
subprocess.run([*compose, 'stop', 'backend', 'skill-executor'], check=True)
try:
    container = subprocess.check_output([*compose, 'ps', '-q', 'postgres'], text=True).strip()
    query = "SELECT json_build_object('versions',COALESCE((SELECT json_agg(v) FROM (SELECT id,skill_id,status,storage_key FROM tb_skill_version) v),'[]'),'files',COALESCE((SELECT json_agg(f) FROM (SELECT version_id,relative_path,hash,size_bytes,mime FROM tb_skill_file) f),'[]'),'artifacts',COALESCE((SELECT json_agg(a) FROM (SELECT id,storage_key,hash,size_bytes,status,expires_at FROM tb_artifact WHERE status='READY') a),'[]'))"
    manifest = subprocess.check_output(['docker', 'exec', container, 'psql', '-U', a.db_user, '-d', a.database, '-At', '-c', query], text=True)
    (out / 'manifest.json').write_text(json.dumps(json.loads(manifest), ensure_ascii=False, indent=2))
    with (out / 'postgres.dump').open('wb') as target:
        subprocess.run(['docker', 'exec', container, 'pg_dump', '-U', a.db_user, '-d', a.database, '-Fc'], stdout=target, check=True)
    with (out / 'files.tar').open('wb') as target:
        subprocess.run(['docker', 'run', '--rm', '-v', a.project + '_mojie_files:/data:ro', 'alpine:3.22', 'tar', '-C', '/data', '-cf', '-', 'skills/packages', 'artifacts'], stdout=target, check=True)
finally:
    subprocess.run([*compose, 'start', 'skill-executor', 'backend'], check=True)
print(out)
