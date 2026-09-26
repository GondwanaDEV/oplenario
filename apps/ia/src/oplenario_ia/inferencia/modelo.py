"""Os tipos que atravessam a porta — o pedido JÁ filtrado e a resposta normalizada entre fornecedores."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

Esforco = Literal["low", "medium", "high"]
Parada = Literal["fim", "limite_tokens", "recusa"]


class PedidoInferencia(BaseModel):
    """O que o LLM recebe. Só existe DEPOIS do filtro de governança (B1–B3): `conteudo` já está liberado e redigido.

    `instrucoes` é o prompt do PRODUTO (versionado com a capacidade, nunca dado de terceiro). `conteudo` são as peças
    de dados, cada uma já delimitada quando veio de terceiro (§22.11.4).
    """

    ente_id: str
    correlation_id: str
    operacao: str
    instrucoes: str
    conteudo: list[str] = Field(min_length=1)
    max_tokens: int = Field(default=16000, gt=0)
    esforco: Esforco | None = None


class Uso(BaseModel):
    entrada: int = 0
    saida: int = 0
    cache_leitura: int = 0
    cache_escrita: int = 0


class RespostaInferencia(BaseModel):
    """A resposta normalizada. `vendor` e `modelo` são os EFETIVAMENTE usados (§22.3.5 — sempre carimbados)."""

    texto: str
    parada: Parada
    vendor: str
    modelo: str
    uso: Uso
    latencia_ms: int
    id_requisicao: str | None = None
