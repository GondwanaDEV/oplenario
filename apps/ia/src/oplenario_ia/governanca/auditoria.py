"""O registro de governança de cada cruzamento da porta (B4): decisão + proveniência + contagens + hash.

NUNCA o conteúdo (accountability LGPD: demonstra que sigiloso não cruzou e que a minimização foi aplicada, sem virar
cópia do dado). O hash é SHA-256 sobre a forma canônica das peças ORIGINAIS (limitação 4 do protótipo: o
`clojure.core/hash` era de 32 bits, não criptográfico).
"""

from __future__ import annotations

import hashlib
import json
from datetime import datetime
from typing import Literal

from pydantic import BaseModel

from oplenario_ia.governanca.proveniencia import Peca

Decisao = Literal["liberado", "bloqueado", "vazio"]


class AuditoriaGovernanca(BaseModel):
    instante: datetime
    decisao: Decisao
    vendor: str
    n_liberadas: int
    n_bloqueadas: int
    motivos_bloqueio: list[str]
    redacoes: dict[str, int]
    terceiros: int  # quantas peças de terceiro cruzaram (delimitadas) — a execução fica "contaminada"
    hash_entrada: str


def hash_pecas(pecas: list[Peca]) -> str:
    canonica = json.dumps([p.texto for p in pecas], ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonica.encode("utf-8")).hexdigest()
