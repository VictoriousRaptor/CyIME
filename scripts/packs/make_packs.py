"""轻量版按需下载的包（1.16）：从固定来源做成 tar.bz2，打印模型目录（BuiltinModelCatalog.kt）要写的哈希和大小。

用法：
  python scripts/packs/make_packs.py handwriting <输出目录>
      手写生僻字模板（handwriting-rare）、日语手写模板（japanese-handwriting），取自 app/src/main/assets/handwriting。
      这两份模板跟着手写模型和训练脚本变：换了文件就重跑，把打印的哈希写进模型目录（JapanesePackCatalogTest、
      HandwritingPackCatalogTest 会对不上）。
  python scripts/packs/make_packs.py speech <sherpa-onnx-static-link-onnxruntime-1.13.8.aar> <输出目录>
      语音引擎（speech-engine）：AAR 里每个 ABI 的 libsherpa-onnx-jni.so。

包里的文件放在一层目录下（下载解压时去掉第一层），时间、属主固定，同样的输入做出同样的字节。
"""
import bz2, hashlib, io, json, sys, tarfile, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HANDWRITING = ROOT / "app/src/main/assets/handwriting"
RARE = ["hanzi_rare_och.bin", "hanzi_rare_och.json"]
JAPANESE = ["kana_default_strokes.json", "kana_default_och.bin", "kana_default_och.json",
            "japanese_kanji_strokes.json", "japanese_kanji_och.bin", "japanese_kanji_och.json"]
SPEECH_AAR_SHA = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def pack(top: str, files: dict) -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(f"{top}/{name}")
            info.size = len(data); info.mtime = 0; info.mode = 0o644
            info.uid = info.gid = 0; info.uname = info.gname = ""
            tar.addfile(info, io.BytesIO(data))
    return bz2.compress(buf.getvalue(), 9)


def write(out: Path, prefix: str, top: str, files: dict) -> dict:
    data = pack(top, files)
    name = f"{prefix}-{sha(data)[:8]}.tar.bz2"
    (out / name).write_bytes(data)
    return {"archive": name, "archiveSha": sha(data), "archiveBytes": len(data),
            "files": {n: {"sha": sha(b), "bytes": len(b)} for n, b in files.items()}}


def handwriting(out: Path) -> dict:
    return {
        "handwriting-rare": write(out, "handwriting-rare", "handwriting-rare",
                                  {n: (HANDWRITING / n).read_bytes() for n in RARE}),
        "japanese-handwriting": write(out, "japanese-handwriting", "japanese-handwriting",
                                      {n: (HANDWRITING / n).read_bytes() for n in JAPANESE}),
    }


def speech(aar: Path, out: Path) -> dict:
    raw = aar.read_bytes()
    if sha(raw) != SPEECH_AAR_SHA:
        sys.exit(f"{aar} 不是固定的 sherpa-onnx 1.13.8 AAR")
    z = zipfile.ZipFile(io.BytesIO(raw))
    result = {}
    for abi in ("arm64-v8a", "armeabi-v7a", "x86_64", "x86"):
        lib = z.read(f"jni/{abi}/libsherpa-onnx-jni.so")
        data = pack("speech-engine", {"libsherpa-onnx-jni.so": lib})
        name = f"speech-engine-sherpa-onnx-1.13.8-{abi}.tar.bz2"
        (out / name).write_bytes(data)
        result[abi] = {"archive": name, "archiveSha": sha(data), "archiveBytes": len(data),
                       "fileSha": sha(lib), "fileBytes": len(lib)}
    return result


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "handwriting":
        out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
        print(json.dumps(handwriting(out), ensure_ascii=False, indent=1))
    elif len(sys.argv) >= 4 and sys.argv[1] == "speech":
        out = Path(sys.argv[3]); out.mkdir(parents=True, exist_ok=True)
        print(json.dumps(speech(Path(sys.argv[2]), out), ensure_ascii=False, indent=1))
    else:
        sys.exit(__doc__)
