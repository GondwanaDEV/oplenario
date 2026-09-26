"""Modelos ONNX da PoC, baixados dos releases do sherpa-onnx no GitHub para /modelos (volume do container).

Por que não os caminhos usuais: HuggingFace (pyannote, faster-whisper) e o índice do PyTorch estão fora da
política de rede deste ambiente; o GitHub está dentro. Os modelos são os MESMOS (a segmentação é a pyannote 3.0
exportada para ONNX; o Whisper é o da OpenAI exportado) — muda só o empacotamento."""
from __future__ import annotations

import os
import tarfile
import urllib.request
from pathlib import Path

BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
DIR = Path(os.environ.get("POC_MODELOS", "/modelos"))

SEGMENTACAO = f"{BASE}/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
EMBEDDINGS = {
    "titanet-small": f"{BASE}/speaker-recongition-models/nemo_en_titanet_small.onnx",
    "titanet-large": f"{BASE}/speaker-recongition-models/nemo_en_titanet_large.onnx",
    "eres2net": f"{BASE}/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
}
WHISPER = {t: f"{BASE}/asr-models/sherpa-onnx-whisper-{t}.tar.bz2" for t in ("medium", "turbo", "large-v3")}
VAD = f"{BASE}/asr-models/silero_vad.onnx"


def _baixar(url: str) -> Path:
    DIR.mkdir(parents=True, exist_ok=True)
    destino = DIR / url.rsplit("/", 1)[1]
    if not destino.exists():
        print(f"baixando {url}", flush=True)
        tmp = destino.with_suffix(destino.suffix + ".parcial")
        urllib.request.urlretrieve(url, tmp)
        tmp.rename(destino)
    if destino.name.endswith(".tar.bz2"):
        pasta = DIR / destino.name.removesuffix(".tar.bz2")
        if not pasta.exists():
            with tarfile.open(destino) as t:
                t.extractall(DIR, filter="data")
        return pasta
    return destino


def segmentacao() -> Path:
    return _baixar(SEGMENTACAO) / "model.onnx"


def embeddings(nome: str = "titanet-small") -> Path:
    return _baixar(EMBEDDINGS[nome])


def whisper(tamanho: str = "turbo") -> dict[str, Path]:
    p = _baixar(WHISPER[tamanho])
    pref = tamanho
    return {
        "encoder": p / f"{pref}-encoder.int8.onnx",
        "decoder": p / f"{pref}-decoder.int8.onnx",
        "tokens": p / f"{pref}-tokens.txt",
    }


def vad() -> Path:
    return _baixar(VAD)


def audio_de_exemplo() -> Path:
    """Áudio público do sherpa-onnx com 4 falantes (mandarim) — só para provar que o pipeline roda e medir tempo."""
    return _baixar(f"{BASE}/speaker-segmentation-models/0-four-speakers-zh.wav")
