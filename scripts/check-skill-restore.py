#!/usr/bin/env python3
"""Verify a live-test backup in a separate PostgreSQL database and separate file root."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
p=argparse.ArgumentParser()
p.add_argument('--postgres-container', required=True)
p.add_argument('--database', required=True)
p.add_argument('--data-dir', required=True)
p.add_argument('--output', required=True)
a=p.parse_args()
restore='mojie_cs_restore'
work=pathlib.Path(tempfile.mkdtemp(prefix='mojie-cs-restore-'))
query="SELECT json_build_object('versions',COALESCE((SELECT json_agg(v) FROM (SELECT id,skill_id,status,storage_key FROM tb_skill_version) v),'[]'),'files',COALESCE((SELECT json_agg(f) FROM (SELECT version_id,relative_path,hash,size_bytes,mime FROM tb_skill_file) f),'[]'),'artifacts',COALESCE((SELECT json_agg(a) FROM (SELECT id,storage_key,hash,size_bytes,status,expires_at FROM tb_artifact WHERE status='READY') a),'[]'))"
manifest=subprocess.check_output(['docker','exec',a.postgres_container,'psql','-U','mojie','-d',a.database,'-At','-c',query],text=True)
(work/'manifest.json').write_text(manifest)
with (work/'database.dump').open('wb') as output:subprocess.run(['docker','exec',a.postgres_container,'pg_dump','-U','mojie','-d',a.database,'-Fc'],stdout=output,check=True)
shutil.copytree(a.data_dir,work/'data')
subprocess.run(['docker','exec',a.postgres_container,'psql','-U','mojie','-d','postgres','-c','CREATE DATABASE '+restore+' OWNER mojie'],check=True,stdout=subprocess.DEVNULL)
with (work/'database.dump').open('rb') as source:subprocess.run(['docker','exec','-i',a.postgres_container,'pg_restore','-U','mojie','-d',restore,'--exit-on-error'],stdin=source,check=True)
verification=subprocess.check_output(['python3','scripts/verify-skill-files.py','--manifest',str(work/'manifest.json'),'--data-dir',str(work/'data')],text=True)
counts=subprocess.check_output(['docker','exec',a.postgres_container,'psql','-U','mojie','-d',restore,'-At','-c',"SELECT json_build_object('conversations',(SELECT count(*) FROM tb_conversation),'messages',(SELECT count(*) FROM tb_conversation_message),'snapshots',(SELECT count(*) FROM tb_agent_input_snapshot),'artifacts',(SELECT count(*) FROM tb_artifact))"],text=True)
report={'status':'PASS','fileVerification':json.loads(verification),'restoredCounts':json.loads(counts),'restoreDatabase':restore,'restoreDataDir':str(work/'data'),'scope':'Separate database and file root restored from stopped isolated test services; API continuation and artifact download must be checked against a separately started restored backend.'}
pathlib.Path(a.output).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
