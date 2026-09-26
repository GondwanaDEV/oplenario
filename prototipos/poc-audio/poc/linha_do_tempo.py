"""Linha do tempo = lista de (inicio_s, fim_s, rotulo). Discretizada em quadros de 10 ms para as métricas."""
from __future__ import annotations

import csv
from pathlib import Path

import numpy as np

PASSO_S = 0.01  # 10 ms, o passo usual das métricas de diarização

Trecho = tuple[float, float, str]


def quadro(t: float) -> int:
    return int(round(t / PASSO_S))


def matriz(trechos: list[Trecho], n_quadros: int) -> tuple[np.ndarray, list[str]]:
    """Matriz booleana [rótulo × quadro]: quem está falando em cada quadro (mais de um = fala sobreposta)."""
    rotulos = sorted({r for _, _, r in trechos})
    idx = {r: i for i, r in enumerate(rotulos)}
    m = np.zeros((len(rotulos), n_quadros), dtype=bool)
    for ini, fim, r in trechos:
        m[idx[r], quadro(ini):quadro(fim)] = True
    return m, rotulos


def fim_de(*linhas: list[Trecho]) -> int:
    return max((quadro(f) for linha in linhas for _, f, _ in linha), default=0)


def ler_csv(caminho: str | Path) -> list[Trecho]:
    """CSV com cabeçalho inicio,fim,falante (segundos). É o formato da planilha de anotação humana."""
    with open(caminho, newline="", encoding="utf-8") as f:
        return [(float(l["inicio"]), float(l["fim"]), l["falante"].strip()) for l in csv.DictReader(f)]


def escrever_csv(caminho: str | Path, trechos: list[Trecho]) -> None:
    with open(caminho, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["inicio", "fim", "falante"])
        for ini, fim, r in trechos:
            w.writerow([f"{ini:.2f}", f"{fim:.2f}", r])
