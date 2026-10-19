#!/usr/bin/env python3
"""CI-only aggregation; no deployment command or secret enters the manifest."""
import argparse
import json
import os
from pathlib import Path
from mojie_deploy import validate_release


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parts', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    images = {}
    for name in ('backend', 'web'):
        part = json.loads((Path(args.parts) / (name + '.json')).read_text())
        if set(part) != {name}:
            raise ValueError('Unexpected image artifact')
        images.update(part)
    release = {
        'schemaVersion': 1, 'deploymentContract': 2,
        'repository': os.environ['GITHUB_REPOSITORY'],
        'commit': os.environ['GITHUB_SHA'], 'sourceRef': os.environ['GITHUB_REF'],
        'runId': int(os.environ['GITHUB_RUN_ID']),
        'runNumber': int(os.environ['GITHUB_RUN_NUMBER']),
        'runAttempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
        'migrationPolicy': 'isolated-copy', 'images': images,
    }
    validate_release(release, release['repository'])
    Path(args.output).write_text(json.dumps(release, indent=2) + '\n')


if __name__ == '__main__':
    main()
