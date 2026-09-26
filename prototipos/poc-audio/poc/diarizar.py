"""Diarização: quem fala quando, sem saber quem é ("SPK_0", "SPK_1"...). Segmentação pyannote 3.0 + embedding de
voz + agrupamento, pelo sherpa-onnx (ONNX em CPU). Mede o fator de tempo real (processamento / duração do áudio),
que dimensiona a máquina.

Uso: python -m poc.diarizar AUDIO [--inicio S --duracao S] [--falantes N | --limiar L] [--embedding titanet-small]
Saída: dados/<nome>.diarizacao.csv (inicio,fim,falante) + a medição de tempo no terminal."""
from __future__ import annotations

import argparse
import os
import time
from pathlib import Path

import sherpa_onnx

from poc import modelos
from poc.audio import TAXA, carregar
from poc.linha_do_tempo import Trecho, escrever_csv


def diarizar(amostras, *, falantes: int = -1, limiar: float = 0.5, embedding: str = "titanet-small",
             threads: int | None = None) -> list[Trecho]:
    threads = threads or os.cpu_count() or 1
    config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(modelos.segmentacao())),
            num_threads=threads,
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(modelos.embeddings(embedding)),
                                                              num_threads=threads),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=falantes, threshold=limiar),
        min_duration_on=0.3,
        min_duration_off=0.5,
    )
    if not config.validate():
        raise RuntimeError("configuração de diarização inválida")
    sd = sherpa_onnx.OfflineSpeakerDiarization(config)
    assert sd.sample_rate == TAXA
    resultado = sd.process(amostras).sort_by_start_time()
    return [(r.start, r.end, f"SPK_{r.speaker}") for r in resultado]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("audio")
    ap.add_argument("--inicio", type=float)
    ap.add_argument("--duracao", type=float)
    ap.add_argument("--falantes", type=int, default=-1, help="número de falantes, se conhecido (-1 = estimar)")
    ap.add_argument("--limiar", type=float, default=0.5, help="limiar de agrupamento quando o número é estimado")
    ap.add_argument("--embedding", default="titanet-small", choices=sorted(modelos.EMBEDDINGS))
    ap.add_argument("--saida")
    a = ap.parse_args()

    amostras = carregar(a.audio, a.inicio, a.duracao)
    dur = len(amostras) / TAXA
    t0 = time.perf_counter()
    trechos = diarizar(amostras, falantes=a.falantes, limiar=a.limiar, embedding=a.embedding)
    gasto = time.perf_counter() - t0
    saida = Path(a.saida or f"dados/{Path(a.audio).stem}.diarizacao.csv")
    escrever_csv(saida, trechos)
    print(f"áudio {dur:.1f}s | processamento {gasto:.1f}s | fator tempo real {gasto / dur:.2f} | "
          f"{len({r for *_, r in trechos})} falantes | {len(trechos)} trechos -> {saida}")


if __name__ == "__main__":
    main()
