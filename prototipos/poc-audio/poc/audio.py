"""Leitura de qualquer áudio/vídeo (mp4 do OBS, webm do YouTube, wav) como 16 kHz mono float32 — o formato que
todos os modelos da PoC esperam. Usa o ffmpeg estático que vem no pacote imageio-ffmpeg (sem apt)."""
from __future__ import annotations

import subprocess

import imageio_ffmpeg
import numpy as np

TAXA = 16_000


def carregar(caminho: str, inicio_s: float | None = None, duracao_s: float | None = None) -> np.ndarray:
    cmd = [imageio_ffmpeg.get_ffmpeg_exe(), "-v", "error"]
    if inicio_s is not None:
        cmd += ["-ss", str(inicio_s)]
    if duracao_s is not None:
        cmd += ["-t", str(duracao_s)]
    cmd += ["-i", caminho, "-ac", "1", "-ar", str(TAXA), "-f", "f32le", "-"]
    bruto = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(bruto, dtype=np.float32).copy()
