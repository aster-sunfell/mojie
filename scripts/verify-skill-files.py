#!/usr/bin/env python3
"""Verify a restored file root against the manifest from the coordinated backup."""
import argparse
import hashlib
import json
import pathlib
p = argparse.ArgumentParser()
p.add_argument('--manifest', required=True)
p.add_argument('--data-dir', required=True)
a = p.parse_args()
root = pathlib.Path(a.data_dir).resolve()
manifest = json.loads(pathlib.Path(a.manifest).read_text())
count = 0
for file in manifest['files']:
    path = root / 'skills/packages' / str(file['version_id']) / file['relative_path']
    if path.is_symlink() or not path.resolve().is_relative_to(root):
        raise SystemExit('Unsafe restored file')
    if file['mime'] == 'inode/directory':
        if not path.is_dir():
            raise SystemExit('Missing restored directory')
    elif not path.is_file() or path.stat().st_size != file['size_bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != file['hash'].strip():
        raise SystemExit('Missing or corrupt restored package file: ' + file['relative_path'])
    count += 1
for artifact in manifest['artifacts']:
    path = root / artifact['storage_key']
    if path.is_symlink() or not path.resolve().is_relative_to(root) or not path.is_file() or path.stat().st_size != artifact['size_bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != artifact['hash'].strip():
        raise SystemExit('Missing or corrupt restored artifact')
    count += 1
print(json.dumps({'verifiedFiles': count, 'status': 'PASS'}))
