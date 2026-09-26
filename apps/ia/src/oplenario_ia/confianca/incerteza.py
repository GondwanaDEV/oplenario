"""Indicação de incerteza (§16.8) — a categoria 6 do §22.3.5 viajando como metadado, nunca como erro.

Sinais determinísticos, sem pedir ao modelo que se autoavalie: resposta cortada por limite, afirmação sem fonte,
citação que não confere, conteúdo de terceiro na entrada (execução contaminada, §22.11.4).
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel

from oplenario_ia.confianca.citacao import Citacao
from oplenario_ia.erros import Categoria
from oplenario_ia.inferencia.modelo import Parada

Nivel = Literal["normal", "revisar_com_atencao"]
MotivoIncerteza = Literal["truncado", "sem_fonte", "citacao_nao_conferida", "conteudo_de_terceiro"]


class Incerteza(BaseModel):
    nivel: Nivel
    motivos: list[MotivoIncerteza]
    categoria: Categoria | None = None  # BAIXA_CONFIANCA quando há motivo — o vocabulário único do §22.3.5


def avaliar(parada: Parada, citacoes: list[Citacao], sem_fonte: list[int], terceiros: int) -> Incerteza:
    motivos: list[MotivoIncerteza] = []
    if parada == "limite_tokens":
        motivos.append("truncado")
    if sem_fonte:
        motivos.append("sem_fonte")
    if any(c.status != "conferida" for c in citacoes):
        motivos.append("citacao_nao_conferida")
    if terceiros:
        motivos.append("conteudo_de_terceiro")
    if not motivos:
        return Incerteza(nivel="normal", motivos=[])
    return Incerteza(nivel="revisar_com_atencao", motivos=motivos, categoria=Categoria.BAIXA_CONFIANCA)
