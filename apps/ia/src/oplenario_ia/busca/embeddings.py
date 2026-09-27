"""A porta de EMBEDDINGS — self-host, nunca fornecedor externo (§22.9 Eixo 10.1: o texto da Casa não sai para gerar
vetor). Trocar de modelo = trocar a implementação e REINDEXAR (cada trecho guarda o modelo que o gerou; a busca só
compara vetores do mesmo modelo).

`fake`: hashing determinístico de palavras normalizadas — similaridade LÉXICA, sem sentido; serve para dev, CI e o
fluxo inteiro sem baixar modelo. `fastembed` (extra `[embeddings]`): modelo aberto em ONNX na CPU.
"""

from __future__ import annotations

import hashlib
import math
import re
import unicodedata
from typing import Literal, Protocol

from oplenario_ia.config import Config

DIMENSAO = 384  # a coluna `vector(384)` do índice: o fake e o multilingual-e5-small têm a mesma dimensão
TipoTexto = Literal["consulta", "documento"]


class Embedder(Protocol):
    @property
    def modelo(self) -> str: ...

    @property
    def distancia_maxima(self) -> float:
        """Acima desta distância de cosseno o trecho não é 'parecido' — sem corte, toda busca devolveria algo."""
        ...

    def embed(self, textos: list[str], tipo: TipoTexto) -> list[list[float]]: ...


def normalizar(texto: str) -> list[str]:
    """Minúsculas, sem acento, só palavras de 3+ letras, com um radical grosseiro (6 letras): 'vereadores' e
    'vereador' batem."""
    sem = unicodedata.normalize("NFKD", texto.casefold())
    sem = "".join(c for c in sem if not unicodedata.combining(c))
    return [p[:6] for p in re.findall(r"[a-z0-9]{3,}", sem)]


class EmbedderFake:
    modelo = "fake-hash-384"
    distancia_maxima = 0.9

    def embed(self, textos: list[str], tipo: TipoTexto) -> list[list[float]]:
        return [self._um(t) for t in textos]

    @staticmethod
    def _um(texto: str) -> list[float]:
        v = [0.0] * DIMENSAO
        for p in normalizar(texto):
            h = int.from_bytes(hashlib.blake2b(p.encode(), digest_size=8).digest(), "big")
            v[h % DIMENSAO] += 1.0 if (h >> 32) & 1 else -1.0
        n = math.sqrt(sum(x * x for x in v)) or 1.0
        return [x / n for x in v]


def criar_embedder(config: Config) -> Embedder:
    if config.embeddings == "fastembed":
        from oplenario_ia.busca.fastembed_embedder import EmbedderFastembed  # import tardio: extra opcional

        return EmbedderFastembed(config.modelo_embeddings)
    return EmbedderFake()
