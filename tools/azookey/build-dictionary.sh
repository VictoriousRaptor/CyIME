#!/bin/sh
# Builds azookey-louds-mozc-<hash8>.zip: the azooKey dictionary's louds/ files with the Mozc nouns it lacks
# (names such as 暁山, places, organisations). tasks-japanese.gradle.kts swaps these files into the pinned
# upstream dictionary. Needs the Swift 6.2.0 toolchain of build-android.sh (a host build), python3 and curl.
# Prints the zip's SHA-256 (azooKeyLoudsZipHash) and the merged dictionary's content hash
# (azooKeyMergedDictionaryHash); put both into tasks-japanese.gradle.kts.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$HERE/work}
COMMIT=25a6346bf76931c1cf2214015b808875ea82f4bf
DICTIONARY_COMMIT=4d418525b090cf49c219819d05a7e3cc2a4346eb
MOZC_COMMIT=00724c6d6395cd3cc6ca6e32ec0975f962d6c800
mkdir -p "$WORK"
if [ ! -d "$WORK/akkc-lite" ]; then
  git clone --recurse-submodules https://github.com/azooKey/AzooKeyKanaKanjiConverter "$WORK/akkc-lite"
  git -C "$WORK/akkc-lite" checkout --recurse-submodules $COMMIT
  (cd "$WORK/akkc-lite" && patch -p1 < "$HERE/azookey-lite.patch")
fi
# SuppTool (added by azookey-lite.patch) dumps louds/ to TSV and builds louds/ back from TSV.
swift build -c release --jobs "${JOBS:-2}" --package-path "$WORK/akkc-lite" --product SuppTool --scratch-path "$WORK/build-host"
TOOL="$WORK/build-host/release/SuppTool"
# The converter's submodule is the dictionary commit tasks-japanese.gradle.kts pins.
STORAGE="$WORK/akkc-lite/Sources/KanaKanjiConverterModuleWithDefaultDictionary/azooKey_dictionary_storage"
[ "$(git -C "$STORAGE" rev-parse HEAD)" = "$DICTIONARY_COMMIT" ] || { echo "dictionary is not $DICTIONARY_COMMIT" >&2; exit 1; }
DICTIONARY="$STORAGE/Dictionary"
mkdir -p "$WORK/mozc"
for f in dictionary00 dictionary01 dictionary02 dictionary03 dictionary04 dictionary05 dictionary06 dictionary07 dictionary08 dictionary09; do
  [ -f "$WORK/mozc/$f.txt" ] || curl -fL -o "$WORK/mozc/$f.txt" \
    "https://raw.githubusercontent.com/google/mozc/$MOZC_COMMIT/src/data/dictionary_oss/$f.txt"
done
[ -f "$WORK/mozc/id.def" ] || curl -fL -o "$WORK/mozc/id.def" \
  "https://raw.githubusercontent.com/google/mozc/$MOZC_COMMIT/src/data/dictionary_oss/id.def"
"$TOOL" dump "$DICTIONARY" "$WORK/azookey.tsv"
python3 "$HERE/mozc-words.py" "$WORK/mozc" "$WORK/azookey.tsv" "$DICTIONARY/louds/charID.chid" "$WORK/mozc.tsv"
cat "$WORK/azookey.tsv" "$WORK/mozc.tsv" > "$WORK/merged.tsv"
rm -rf "$WORK/louds-mozc"
"$TOOL" build "$WORK/merged.tsv" "$WORK/louds-mozc" "$DICTIONARY/louds/charID.chid"
python3 - "$WORK" "$DICTIONARY" <<'PY'
import hashlib, os, sys, zipfile
work = sys.argv[1]
built = os.path.join(work, 'louds-mozc')
names = sorted(os.listdir(built))
pending = os.path.join(work, 'louds-mozc.zip')
with zipfile.ZipFile(pending, 'w') as z:
    for name in names:
        info = zipfile.ZipInfo('louds/' + name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, open(os.path.join(built, name), 'rb').read(), compresslevel=9)
digest = hashlib.sha256(open(pending, 'rb').read()).hexdigest()
target = os.path.join(work, 'azookey-louds-mozc-%s.zip' % digest[:8])
os.replace(pending, target)
def sha(path): return hashlib.sha256(open(path, 'rb').read()).hexdigest()
dictionary = sys.argv[2]
files = {}
for root, _, entries in os.walk(dictionary):
    for entry in entries:
        rel = os.path.relpath(os.path.join(root, entry), dictionary).replace(os.sep, '/')
        if entry.startswith('.') or (rel.startswith('louds/') and rel != 'louds/charID.chid'): continue
        files[rel] = sha(os.path.join(root, entry))
for name in names: files['louds/' + name] = sha(os.path.join(built, name))
manifest = ''.join('%s\0%s\n' % (k, files[k]) for k in sorted(files))
print('azooKeyLoudsZipHash', digest, target)
print('azooKeyMergedDictionaryHash', hashlib.sha256(manifest.encode()).hexdigest())
PY
