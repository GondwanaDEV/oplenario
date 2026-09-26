"""Adaptadores SELF-HOST de ASR e diarização (sherpa-onnx: ONNX Runtime, CPU ou GPU). Porta de produção do PoC de
áudio (`prototipos/poc-audio/`): Whisper sobre os trechos que o Silero VAD recorta; diarização por segmentação
pyannote 3.0 + embedding TitaNet + agrupamento. Nada sai do cluster.

Dependências pesadas ficam no extra `[asr]` (sherpa-onnx, numpy, imageio-ffmpeg) — o CI e o padrão de deploy usam
o fake. Os modelos são os releases do sherpa-onnx no GitHub, num diretório montado (`OPLENARIO_IA_MODELOS`).
"""

from __future__ import annotations

import os
import subprocess
from pathlib import Path
from typing import Any

from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.transcricao.modelo import Frase, Voz

TAXA = 16_000


def carregar_audio(arquivo: Path) -> Any:
    """Qualquer áudio/vídeo (mkv/mp4 do OBS, wav) -> 16 kHz mono float32 (ffmpeg do imageio-ffmpeg)."""
    import imageio_ffmpeg
    import numpy as np

    cmd = [
        imageio_ffmpeg.get_ffmpeg_exe(),
        "-v",
        "error",
        "-i",
        str(arquivo),
        "-vn",
        "-ac",
        "1",
        "-ar",
        str(TAXA),
        "-f",
        "f32le",
        "-",
    ]
    try:
        bruto = subprocess.run(cmd, check=True, capture_output=True).stdout
    except subprocess.CalledProcessError as e:
        detalhe = e.stderr.decode(errors="replace")[-300:]
        raise ErroIA(Categoria.ENTRADA, f"áudio ilegível: {detalhe}", retentavel=False, vendor="self-host") from e
    amostras = np.frombuffer(bruto, dtype=np.float32).copy()
    if amostras.size < TAXA // 2:
        raise ErroIA(Categoria.ENTRADA, "áudio vazio ou curto demais", retentavel=False, vendor="self-host")
    return amostras


def _threads() -> int:
    return os.cpu_count() or 1


class TranscritorSherpa:
    def __init__(self, modelos: Path, tamanho: str = "turbo") -> None:
        self._dir = modelos
        self._tamanho = tamanho
        self._rec: Any = None

    @property
    def modelo(self) -> str:
        return f"whisper-{self._tamanho}-int8"

    def _reconhecedor(self, idioma: str) -> Any:
        import sherpa_onnx

        if self._rec is None:
            p = self._dir / f"sherpa-onnx-whisper-{self._tamanho}"
            self._rec = sherpa_onnx.OfflineRecognizer.from_whisper(
                encoder=str(p / f"{self._tamanho}-encoder.int8.onnx"),
                decoder=str(p / f"{self._tamanho}-decoder.int8.onnx"),
                tokens=str(p / f"{self._tamanho}-tokens.txt"),
                language=idioma,
                task="transcribe",
                num_threads=_threads(),
            )
        return self._rec

    def _trechos_de_fala(self, amostras: Any, max_s: float = 25.0) -> Any:
        import sherpa_onnx

        config = sherpa_onnx.VadModelConfig(
            silero_vad=sherpa_onnx.SileroVadModelConfig(
                model=str(self._dir / "silero_vad.onnx"),
                min_silence_duration=0.4,
                min_speech_duration=0.25,
                max_speech_duration=max_s,
            ),
            sample_rate=TAXA,
        )
        vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=max_s + 5)
        janela = config.silero_vad.window_size
        for i in range(0, len(amostras), janela):
            vad.accept_waveform(amostras[i : i + janela])
            while not vad.empty():
                yield vad.front.start / TAXA, vad.front.samples
                vad.pop()
        vad.flush()
        while not vad.empty():
            yield vad.front.start / TAXA, vad.front.samples
            vad.pop()

    def transcrever(self, arquivo: Path, idioma: str) -> list[Frase]:
        amostras = carregar_audio(arquivo)
        rec = self._reconhecedor(idioma)
        frases = []
        for inicio, pedaco in self._trechos_de_fala(amostras):
            s = rec.create_stream()
            s.accept_waveform(TAXA, pedaco)
            rec.decode_stream(s)
            texto = s.result.text.strip()
            if texto:
                frases.append(Frase(round(inicio, 2), round(inicio + len(pedaco) / TAXA, 2), texto))
        return frases


class DiarizadorSherpa:
    def __init__(self, modelos: Path, *, falantes: int = -1, limiar: float = 0.5) -> None:
        self._dir = modelos
        self._falantes = falantes
        self._limiar = limiar

    @property
    def modelo(self) -> str:
        return "pyannote-3.0+titanet-small"

    def diarizar(self, arquivo: Path) -> list[Voz]:
        import sherpa_onnx

        amostras = carregar_audio(arquivo)
        config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
            segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
                pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                    model=str(self._dir / "sherpa-onnx-pyannote-segmentation-3-0" / "model.onnx")
                ),
                num_threads=_threads(),
            ),
            embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                model=str(self._dir / "nemo_en_titanet_small.onnx"), num_threads=_threads()
            ),
            clustering=sherpa_onnx.FastClusteringConfig(num_clusters=self._falantes, threshold=self._limiar),
            min_duration_on=0.3,
            min_duration_off=0.5,
        )
        if not config.validate():
            raise ErroIA(
                Categoria.INFRAESTRUTURA,
                "modelos de diarização ausentes ou inválidos",
                retentavel=False,
                vendor="self-host",
            )
        resultado = sherpa_onnx.OfflineSpeakerDiarization(config).process(amostras).sort_by_start_time()
        return [Voz(round(r.start, 2), round(r.end, 2), f"SPK_{r.speaker}") for r in resultado]
