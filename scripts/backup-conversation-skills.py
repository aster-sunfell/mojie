#!/usr/bin/env python3
"""Coordinated backup; deployment can retain the freeze with --keep-stopped."""
import argparse
import hashlib
import json
import pathlib
import subprocess


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def backup(compose, output, database='mojie', user='mojie', keep_stopped=False):
    output = pathlib.Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    running = subprocess.check_output([*compose, 'ps', '--status', 'running', '--services'], text=True).split()
    writers = [service for service in ('backend', 'skill-executor') if service in running]
    try:
        subprocess.run([*compose, 'stop', '-t', '60', 'backend', 'skill-executor'], check=True)
        postgres = subprocess.check_output([*compose, 'ps', '-q', 'postgres'], text=True).strip()
        backend = subprocess.check_output([*compose, 'ps', '-a', '-q', 'backend'], text=True).strip()
        if not postgres or not backend:
            raise RuntimeError('Backup requires an existing backend and running PostgreSQL')
        mounts = json.loads(subprocess.check_output(['docker', 'inspect', backend], text=True))[0]['Mounts']
        volume = next(m['Name'] for m in mounts if m['Destination'] == '/data' and m['Type'] == 'volume')
        query = "SELECT json_build_object('versions',COALESCE((SELECT json_agg(v) FROM (SELECT id,skill_id,status,storage_key FROM tb_skill_version) v),'[]'),'files',COALESCE((SELECT json_agg(f) FROM (SELECT version_id,relative_path,hash,size_bytes,mime FROM tb_skill_file) f),'[]'),'artifacts',COALESCE((SELECT json_agg(a) FROM (SELECT id,storage_key,hash,size_bytes,status,expires_at FROM tb_artifact WHERE status='READY') a),'[]'))"
        manifest = subprocess.check_output(['docker', 'exec', postgres, 'psql', '-U', user, '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-c', query], text=True)
        (output / 'manifest.json').write_text(json.dumps(json.loads(manifest), ensure_ascii=False, indent=2))
        with (output / 'postgres.dump').open('wb') as target:
            subprocess.run(['docker', 'exec', postgres, 'pg_dump', '-U', user, '-d', database, '-Fc'], stdout=target, check=True)
        with (output / 'files.tar').open('wb') as target:
            subprocess.run(['docker', 'run', '--rm', '--network', 'none', '-v', volume + ':/data:ro', 'alpine:3.22', 'tar', '-C', '/data', '-cf', '-', 'skills/packages', 'artifacts'], stdout=target, check=True)
        completed = {'schemaVersion': 1, 'files': {name: {'size': (output / name).stat().st_size, 'sha256': sha256(output / name)} for name in ('manifest.json', 'postgres.dump', 'files.tar')}}
        temporary = output / 'complete.json.tmp'
        temporary.write_text(json.dumps(completed, indent=2) + '\n')
        temporary.replace(output / 'complete.json')
    finally:
        if not keep_stopped and writers:
            subprocess.run([*compose, 'start', *writers], check=True)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose-file', default='deploy/compose.prod.yaml')
    parser.add_argument('--env-file', default='deploy/.env.prod')
    parser.add_argument('--project', default='mojie-prod')
    parser.add_argument('--database', default='mojie')
    parser.add_argument('--db-user', default='mojie')
    parser.add_argument('--output', required=True)
    parser.add_argument('--keep-stopped', action='store_true', help='Deployment owns resuming services, including on backup failure')
    args = parser.parse_args()
    compose = ['docker', 'compose', '--env-file', args.env_file, '-f', args.compose_file, '-p', args.project]
    print(backup(compose, args.output, args.database, args.db_user, args.keep_stopped))


if __name__ == '__main__':
    main()
