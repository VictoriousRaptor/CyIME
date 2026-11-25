"""Build the offline edition from the app's pinned model catalog (no market API)."""
import argparse
import hashlib
import json
import re
import shutil
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INCLUDED = {'ochwpro', 'paraformer-zh-en-int8', 'sensevoice-multilingual-int8',
            'speaker-campplus-zh', 'translate-zh-en', 'translate-en-zh'}  # the elder package must tell speakers apart without a download (elder-pack-spec.md)

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def catalog():
    models = []
    for name in ('model/BuiltinModelCatalog.kt', 'speech/SpeechModelCatalog.kt'):
        source = (ROOT / 'app/src/main/java/com/kingzcheung/xime' / name).read_text(encoding='utf-8')
        constants = dict(re.findall(r'const val (\w+) = "([^"]+)"', source))
        for block in source.split('ModelInfo(')[1:]:
            literal = re.match(r'"([^"]+)"', block)
            model_id = literal[1] if literal else constants.get(re.match(r'(\w+)', block)[1], '')
            version = re.search(r'ModelVersion\(version = "([^"]+)"', block)[1]
            block = 'ModelVersion('.join(block.split('ModelVersion(')[:2])  # files of the first (newest) version only
            title = re.match(r'(?:"[^"]+"|\w+),\s*"([^"]+)"', block)
            # The URL is a literal or a catalog constant plus a literal (RELEASE + "translate-…"); files may list
            # download mirrors after the size.
            files = [dict(name=n, url=(constants[c] + u if c in constants else '') if c else u, sha256=s, size=int(z)) for n,c,u,s,z in
                     re.findall(r'ModelFile\("([^"]+)", (?:(\w+) \+ )?"([^"]*)", "([^"]*)", (\d+)L[,)]', block)]
            archive = re.search(r'archiveUrl = "([^"]+)", sha256 = "([^"]+)"', block)
            if model_id in INCLUDED and (not files or (not archive and any(not f['sha256'] or not f['url'] for f in files))):
                raise ValueError(f'Unsupported/incomplete pinned catalog: {model_id}')
            models.append(dict(id=model_id, name=title[1] if title else model_id, version=version, files=files,
                               archive=archive.groups() if archive else None))
    # Only the explicitly included models are bundled; new catalog entries never join silently.
    ids = [m['id'] for m in models]
    if len(set(ids)) != len(ids) or not INCLUDED <= set(ids):
        raise ValueError('Catalog changed: review the offline model edition before building')
    return [m for m in models if m['id'] in INCLUDED]

def download(url, target, sha):
    if target.is_file() and digest(target) == sha:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + '.part')
    print(f'Downloading {target.name}', flush=True)
    with urllib.request.urlopen(url, timeout=180) as src, temporary.open('wb') as dst:
        shutil.copyfileobj(src, dst)
    if digest(temporary) != sha:
        raise ValueError(f'Checksum mismatch: {target.name}')
    temporary.replace(target)

def prepare(output, cache, selected=None):
    payload = output / 'bundled-models'
    payload.mkdir(parents=True, exist_ok=True)
    models = [m for m in catalog() if not selected or m['id'] in selected]
    if selected and {m['id'] for m in models} != set(selected):
        raise ValueError('Unknown bundled model id: ' + ', '.join(sorted(set(selected) - {m['id'] for m in models})))
    # Remove model folders left by a previous edition with a different selection.
    for stale in payload.iterdir():
        if stale.is_dir() and stale.name not in {m['id'] for m in models}:
            if stale.resolve().parent != payload.resolve():
                raise ValueError('Unsafe generated model path')
            shutil.rmtree(stale)
    # The generated folder can contain a previous six-model edition. Never ship stale models.
    for obsolete in ('zipformer-zh-int8',):
        target = (payload / obsolete).resolve()
        if target.parent != payload.resolve():
            raise ValueError('Unsafe generated model path')
        if target.is_dir():
            shutil.rmtree(target)
    manifest = []
    for model in models:
        directory = payload / model['id']
        directory.mkdir(exist_ok=True)
        archive = None
        if model['archive']:
            url, sha = model['archive']
            archive = cache / (model['id'] + '.tar.bz2')
            download(url, archive, sha)
        for info in model['files']:
            target = directory / info['name']
            if archive:
                # Extract only explicitly catalogued regular files, never archive paths/links.
                with tarfile.open(archive) as tar:
                    matches = [m for m in tar.getmembers() if m.isfile() and Path(m.name).name == info['name']]
                    if len(matches) != 1:
                        raise ValueError(f'Archive member missing/ambiguous: {info["name"]}')
                    with tar.extractfile(matches[0]) as src, target.open('wb') as dst:
                        shutil.copyfileobj(src, dst)
            else:
                cached = cache / model['id'] / info['name']
                download(info['url'], cached, info['sha256'])
                if not target.is_file() or digest(target) != info['sha256']:
                    shutil.copyfile(cached, target)
            if not target.stat().st_size or info['size'] and target.stat().st_size != info['size']:
                raise ValueError(f'Invalid model size: {target}')
            info['sha256'] = digest(target)
            info['size'] = target.stat().st_size
        manifest.append(dict(id=model['id'], version=model['version'], files=model['files']))
    (payload / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'Bundled {len(manifest)} models: {sum(f["size"] for m in manifest for f in m["files"]):,} bytes', flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'app/build/generated/bundled-model-assets')
    parser.add_argument('--cache', type=Path, default=ROOT / '.gradle/bundled-model-cache')
    parser.add_argument('--models', default='', help='Comma-separated model ids; empty means all offline models')
    args = parser.parse_args()
    prepare(args.output, args.cache, [m for m in args.models.split(',') if m])
