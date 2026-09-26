"""Caminho C (§22.6 eixo D): quem falou, inferido de quem TINHA A PALAVRA — as falas que a Mesa registra na
plataforma (`sessoes.fala_executada`: orador, início, fim), sem reconhecer voz.

Duas perguntas que a PoC responde:
1. `cobertura_da_palavra`: sozinho, o Caminho C acerta quanto do tempo de fala? Os erros esperados são o
   presidente conduzindo (ninguém na tribuna) e apartes / interrupções (outro falando durante a palavra de alguém).
2. `nomear_clusters`: combinado com a diarização, dá nome aos grupos de voz ("SPK_0" → "Ana") pela maioria do
   tempo em que o grupo fala dentro da palavra de alguém."""
from __future__ import annotations

import numpy as np

from poc.linha_do_tempo import Trecho, fim_de, matriz


def cobertura_da_palavra(ref: list[Trecho], palavra: list[Trecho]) -> dict:
    n = fim_de(ref, palavra)
    mr, rot_ref = matriz(ref, n)
    mp, rot_pal = matriz(palavra, n)
    alguem_na_tribuna = mp.any(axis=0)
    total = acerto = fora = outro = 0
    for i, nome in enumerate(rot_ref):
        fala = mr[i]
        total += int(fala.sum())
        tem = mp[rot_pal.index(nome)] if nome in rot_pal else np.zeros(n, dtype=bool)
        acerto += int((fala & tem).sum())
        fora += int((fala & ~alguem_na_tribuna).sum())
        outro += int((fala & alguem_na_tribuna & ~tem).sum())
    if total == 0:
        return {"acerto": 0.0, "fora_da_tribuna": 0.0, "outro_falando": 0.0}
    return {"acerto": acerto / total, "fora_da_tribuna": fora / total, "outro_falando": outro / total}


def nomear_clusters(hip: list[Trecho], palavra: list[Trecho], maioria_minima: float = 0.5) -> dict[str, str | None]:
    """Cada grupo de voz recebe o nome de quem tinha a palavra na maior parte do tempo em que ele falou — se essa
    parte for ao menos `maioria_minima` do tempo do grupo. Senão fica sem nome (None): melhor dizer "não sei" do
    que pôr na ata a fala na boca da pessoa errada."""
    n = fim_de(hip, palavra)
    mh, rot_hip = matriz(hip, n)
    mp, rot_pal = matriz(palavra, n)
    nomes: dict[str, str | None] = {}
    for j, grupo in enumerate(rot_hip):
        dur = int(mh[j].sum())
        sobre = [(int((mh[j] & mp[k]).sum()), rot_pal[k]) for k in range(len(rot_pal))]
        melhor, nome = max(sobre, default=(0, None))
        nomes[grupo] = nome if dur and melhor / dur >= maioria_minima and melhor > 0 else None
    return nomes


def aplicar_nomes(hip: list[Trecho], nomes: dict[str, str | None], desconhecido: str = "?") -> list[Trecho]:
    return [(ini, fim, nomes.get(r) or f"{desconhecido}{r}") for ini, fim, r in hip]
