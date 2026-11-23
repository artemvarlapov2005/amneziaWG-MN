#!/usr/bin/env python3
"""Generate one channel entry only after the signed APK has a published Release URL.
No credentials, APK upload, signing, or Git push is performed by this script.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

RELEASES = 'https://github.com/artemvarlapov2005/amneziaWG-MN/releases'
STABLE = re.compile(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)\Z')
BETA = re.compile(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-beta\.([1-9]\d*)\Z')

def version_key(version):
    stable, beta = STABLE.fullmatch(version), BETA.fullmatch(version)
    if not stable and not beta:
        raise ValueError('Expected X.Y.Z or X.Y.Z-beta.N')
    m = stable or beta
    return tuple(map(int, m.groups()[:3])) + (1 if stable else 0, 0 if stable else int(m.group(4)))

def validate_entry(channel, entry):
    if not isinstance(entry, dict):
        raise ValueError('Release entry must be an object')
    v = entry['versionName']
    if not (STABLE if channel == 'stable' else BETA).fullmatch(v):
        raise ValueError('Channel/version mismatch')
    if entry['tag'] not in (v, 'v' + v) or type(entry['versionCode']) is not int or not 0 < entry['versionCode'] <= 2100000000:
        raise ValueError('Invalid tag/versionCode')
    expected = f"{RELEASES}/download/{entry['tag']}/AmneziaWG-MN-{v}-arm64.apk"
    if entry['apkUrl'] != expected or not re.fullmatch('[0-9a-f]{64}', entry['sha256']) or not isinstance(entry['notes'], str):
        raise ValueError('Invalid URL/hash/notes')

def validate_catalog(catalog):
    if type(catalog.get('schemaVersion')) is not int or catalog['schemaVersion'] != 1:
        raise ValueError('Unsupported catalog schema')
    if not catalog.get('stable') or 'beta' not in catalog:
        raise ValueError('Stable must exist; beta may be null')
    for channel in ('stable', 'beta'):
        if catalog[channel] is not None:
            validate_entry(channel, catalog[channel])
    if catalog['beta'] and catalog['stable']['versionCode'] == catalog['beta']['versionCode']:
        raise ValueError('Each published build must use a unique versionCode')

def replace_entry(catalog, channel, entry):
    validate_catalog(catalog)
    validate_entry(channel, entry)
    if entry['versionCode'] <= max(r['versionCode'] for r in (catalog['stable'], catalog['beta']) if r):
        raise ValueError('versionCode must exceed every published channel build')
    if catalog[channel] and version_key(entry['versionName']) <= version_key(catalog[channel]['versionName']):
        raise ValueError('Channel version must increase')
    return dict(catalog, **{channel: entry})

def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--channel', choices=('stable', 'beta'), required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--tag')
    parser.add_argument('--notes-file', type=Path, required=True)
    parser.add_argument('--catalog', type=Path, default=root / 'updates/channels.json')
    parser.add_argument('--trusted-certificate', type=Path, default=root / 'updates/signing-certificate.sha256')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    badging = subprocess.check_output([str(args.build_tools / 'aapt'), 'dump', 'badging', str(args.apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package.group(1) != 'org.amnezia.awg.patched' or "native-code: 'arm64-v8a'" not in badging:
        raise ValueError('Wrong package or ABI')
    certs = subprocess.check_output([str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(args.apk)], text=True)
    hashes = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', certs)
    if hashes != [args.trusted_certificate.read_text().strip().lower()]:
        raise ValueError('APK must be signed with the existing release certificate')
    version = package.group(3)
    tag = args.tag or version
    entry = dict(tag=tag, versionName=version, versionCode=int(package.group(2)),
                 apkUrl=f'{RELEASES}/download/{tag}/AmneziaWG-MN-{version}-arm64.apk',
                 sha256=hashlib.sha256(args.apk.read_bytes()).hexdigest(), notes=args.notes_file.read_text())
    updated = replace_entry(json.loads(args.catalog.read_text()), args.channel, entry)
    encoded = json.dumps(updated, ensure_ascii=False, indent=2) + '\n'
    if args.dry_run:
        print(encoded, end='')
    else:
        fd, name = tempfile.mkstemp(prefix='channels-', suffix='.json', dir=args.catalog.parent)
        try:
            with os.fdopen(fd, 'w') as stream:
                stream.write(encoded)
            os.replace(name, args.catalog)
        finally:
            if os.path.exists(name):
                os.unlink(name)
        print(f'Updated {args.channel}: {version} ({entry["versionCode"]}). Commit only after verifying the published APK.')

if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
