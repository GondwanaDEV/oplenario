"""Junta a transcrição (frases) e os falantes: cada frase vai para quem mais falou durante ela."""
from __future__ import annotations

from poc.linha_do_tempo import Trecho


def atribuir_falantes(frases: list[Trecho], falantes: list[Trecho]) -> list[tuple[float, float, str | None, str]]:
    saida = []
    for ini, fim, texto in frases:
        tempo: dict[str, float] = {}
        for fi, ff, nome in falantes:
            s = min(fim, ff) - max(ini, fi)
            if s > 0:
                tempo[nome] = tempo.get(nome, 0) + s
        saida.append((ini, fim, max(tempo, key=tempo.get) if tempo else None, texto))
    return saida
