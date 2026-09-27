"""Embeddings self-host com `fastembed` (ONNX na CPU; extra `[embeddings]`). O padrão é o
paraphrase-multilingual-MiniLM-L12-v2 (384 dimensões, português incluído, ~220 MB). Modelos da família e5 pedem os
prefixos "query: " / "passage: " — aplicados só a eles. O modelo é baixado uma vez para o cache do processo; em
produção, pré-baixado na imagem (como os modelos do ASR)."""

from __future__ import annotations

from oplenario_ia.busca.embeddings import DIMENSAO, TipoTexto


class EmbedderFastembed:
    # PONTO DE PARTIDA, ainda não medido: o corte que separa "parecido" de "sem relação" tem de ser calibrado com um
    # conjunto de consultas reais da Casa antes de ligar este adaptador em produção (o padrão do deploy é o fake).
    distancia_maxima = 0.6

    def __init__(self, modelo: str) -> None:
        from fastembed import TextEmbedding

        self._m = TextEmbedding(model_name=modelo)
        self.modelo = modelo
        self._e5 = "e5" in modelo.lower()

    def embed(self, textos: list[str], tipo: TipoTexto) -> list[list[float]]:
        prefixo = ("query: " if tipo == "consulta" else "passage: ") if self._e5 else ""
        vetores = [list(map(float, v)) for v in self._m.embed([prefixo + t for t in textos])]
        if vetores and len(vetores[0]) != DIMENSAO:
            raise ValueError(f"o modelo {self.modelo} gera {len(vetores[0])} dimensões; o índice é de {DIMENSAO}")
        return vetores
