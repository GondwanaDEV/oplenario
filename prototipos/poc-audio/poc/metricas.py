"""DER (Diarization Error Rate) no formato NIST, em quadros de 10 ms.

DER = (fala perdida + falso alarme + confusão de falante) / tempo total de fala da referência, contando fala
sobreposta uma vez por falante. Com `mapear=True` (o padrão) os rótulos da hipótese ("SPK_0"...) são casados aos
da referência pela correspondência de maior sobreposição (algoritmo húngaro) — é o DER da diarização pura. Com
`mapear=False` os rótulos já são nomes e têm de bater: é o DER da atribuição final (diarização + Caminho C)."""
from __future__ import annotations

import numpy as np
from scipy.optimize import linear_sum_assignment

from poc.linha_do_tempo import Trecho, fim_de, matriz, quadro


def _mascara_colar(ref: list[Trecho], n: int, colar: float) -> np.ndarray:
    """Quadros avaliados: tudo, menos ±`colar` s em volta de cada início/fim de trecho da referência."""
    ok = np.ones(n, dtype=bool)
    if colar <= 0:
        return ok
    c = quadro(colar)
    for ini, fim, _ in ref:
        for t in (quadro(ini), quadro(fim)):
            ok[max(0, t - c):min(n, t + c)] = False
    return ok


def der(ref: list[Trecho], hip: list[Trecho], *, mapear: bool = True, colar: float = 0.0) -> dict:
    n = fim_de(ref, hip)
    mr, rot_ref = matriz(ref, n)
    mh, rot_hip = matriz(hip, n)
    ok = _mascara_colar(ref, n, colar)
    mr, mh = mr[:, ok], mh[:, ok]

    # hip -> ref: melhor casamento (mapear) ou nome igual (não mapear)
    if mapear and len(rot_ref) and len(rot_hip):
        sobreposicao = mr.astype(np.int64) @ mh.T.astype(np.int64)  # [ref × hip]
        lin, col = linear_sum_assignment(-sobreposicao)
        pares = list(zip(lin, col))
    else:
        pares = [(rot_ref.index(r), j) for j, r in enumerate(rot_hip) if r in rot_ref]

    n_ref = mr.sum(axis=0)
    n_hip = mh.sum(axis=0)
    corretos = np.zeros(mr.shape[1], dtype=np.int64)
    for i, j in pares:
        corretos += mr[i] & mh[j]

    total = int(n_ref.sum())
    perdida = int(np.maximum(0, n_ref - n_hip).sum())
    falso = int(np.maximum(0, n_hip - n_ref).sum())
    confusao = int((np.minimum(n_ref, n_hip) - corretos).sum())
    if total == 0:
        return {"der": 0.0, "perdida": 0.0, "falso_alarme": 0.0, "confusao": 0.0, "fala_ref_s": 0.0}
    return {
        "der": (perdida + falso + confusao) / total,
        "perdida": perdida / total,
        "falso_alarme": falso / total,
        "confusao": confusao / total,
        "fala_ref_s": total * 0.01,
    }


def tempo_de_fala(trechos: list[Trecho]) -> dict[str, float]:
    t: dict[str, float] = {}
    for ini, fim, r in trechos:
        t[r] = t.get(r, 0) + (fim - ini)
    return t
