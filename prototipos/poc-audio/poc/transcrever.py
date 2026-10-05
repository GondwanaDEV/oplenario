"""Transcrição: Whisper (ONNX int8, CPU, pelo sherpa-onnx) sobre os trechos de fala que o VAD (Silero) recorta.
O Whisper só olha 30 s por vez; o VAD corta em trechos de até 25 s nas pausas, o que também dá o início/fim de
cada frase — é assim que a frase depois se casa com o falante da diarização. Mede o fator de tempo real.

Uso: python -m poc.transcrever AUDIO [--inicio S --duracao S] [--modelo turbo|medium|large-v3] [--idioma pt]
Saída: dados/<nome>.transcricao.csv (inicio,fim,texto) + a medição no terminal."""
from __future__ import annotations

import argparse
import csv
import os
import time
from pathlib import Path

import sherpa_onnx

from poc import modelos
from poc.audio import TAXA, carregar


def trechos_de_fala(amostras, *, max_s: float = 25.0):
    config = sherpa_onnx.VadModelConfig(
        silero_vad=sherpa_onnx.SileroVadModelConfig(
            model=str(modelos.vad()), min_silence_duration=0.4, min_speech_duration=0.25,
            max_speech_duration=max_s),
        sample_rate=TAXA,
    )
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=max_s + 5)
    janela = config.silero_vad.window_size
    for i in range(0, len(amostras), janela):
        vad.accept_waveform(amostras[i:i + janela])
        while not vad.empty():
            yield vad.front.start / TAXA, vad.front.samples
            vad.pop()
    vad.flush()
    while not vad.empty():
        yield vad.front.start / TAXA, vad.front.samples
        vad.pop()


def transcrever(amostras, *, modelo: str = "turbo", idioma: str = "pt", threads: int | None = None):
    m = modelos.whisper(modelo)
    rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(m["encoder"]), decoder=str(m["decoder"]), tokens=str(m["tokens"]),
        language=idioma, task="transcribe", num_threads=threads or os.cpu_count() or 1)
    for inicio, pedaco in trechos_de_fala(amostras):
        s = rec.create_stream()
        s.accept_waveform(TAXA, pedaco)
        rec.decode_stream(s)
        yield inicio, inicio + len(pedaco) / TAXA, s.result.text.strip()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("audio")
    ap.add_argument("--inicio", type=float)
    ap.add_argument("--duracao", type=float)
    ap.add_argument("--modelo", default="turbo", choices=sorted(modelos.WHISPER))
    ap.add_argument("--idioma", default="pt")
    ap.add_argument("--saida")
    a = ap.parse_args()

    amostras = carregar(a.audio, a.inicio, a.duracao)
    dur = len(amostras) / TAXA
    t0 = time.perf_counter()
    frases = list(transcrever(amostras, modelo=a.modelo, idioma=a.idioma))
    gasto = time.perf_counter() - t0
    saida = Path(a.saida or f"dados/{Path(a.audio).stem}.transcricao.{a.modelo}.csv")
    with open(saida, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["inicio", "fim", "texto"])
        for ini, fim, txt in frases:
            w.writerow([f"{ini:.2f}", f"{fim:.2f}", txt])
    print(f"áudio {dur:.1f}s | Whisper {a.modelo} | processamento {gasto:.1f}s | fator tempo real {gasto / dur:.2f} | "
          f"{len(frases)} frases -> {saida}")


if __name__ == "__main__":
    main()
