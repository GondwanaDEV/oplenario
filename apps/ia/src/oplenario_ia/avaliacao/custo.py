"""Custo de cada execução, em dinheiro, a partir do uso de tokens e de uma tabela de preços datada (Eixo 8.3).

Medir primeiro: o orçamento mensal por Casa (aviso a 80%, R-IA-1 "cota da Casa") vem na B.9 e lê exatamente isto.
Modelo sem preço na tabela não vira custo zero em silêncio: o custo sai `None` e o consumo da Casa fica `parcial`.
Quando o fornecedor declara o custo na resposta (o OpenRouter, ADR-0023), vale o declarado — é o que foi cobrado — com o
`acrescimo` do fornecedor (a taxa da plataforma) somado; a tabela fica de reserva.
"""

from __future__ import annotations

import json
from decimal import Decimal
from importlib import resources

from pydantic import BaseModel

from oplenario_ia.inferencia.modelo import Uso


class Preco(BaseModel):
    entrada: Decimal
    saida: Decimal
    cache_leitura: Decimal
    cache_escrita: Decimal


class TabelaPrecos(BaseModel):
    fonte: str
    consultado_em: str
    moeda: str
    unidade_tokens: int
    observacao: str = ""
    precos: dict[str, Preco]  # "vendor/modelo"
    acrescimo: dict[str, Decimal] = {}  # vendor → multiplicador da taxa da plataforma (ex.: "openrouter": "1.055")


class Custo(BaseModel):
    valor: Decimal | None  # None = modelo sem preço na tabela (nunca zero em silêncio)
    moeda: str
    tabela: str  # data da tabela usada — o custo é reprodutível


def tabela_padrao() -> TabelaPrecos:
    texto = resources.files("oplenario_ia.avaliacao").joinpath("precos.json").read_text(encoding="utf-8")
    return TabelaPrecos.model_validate(json.loads(texto))


def calcular(uso: Uso, vendor: str, modelo: str, tabela: TabelaPrecos, informado: Decimal | None = None) -> Custo:
    fator = tabela.acrescimo.get(vendor, Decimal(1))
    if informado is not None:
        return Custo(valor=informado * fator, moeda=tabela.moeda, tabela=f"informado por {vendor}")
    preco = tabela.precos.get(f"{vendor}/{modelo}")
    if preco is None:
        return Custo(valor=None, moeda=tabela.moeda, tabela=tabela.consultado_em)
    bruto = (
        uso.entrada * preco.entrada
        + uso.saida * preco.saida
        + uso.cache_leitura * preco.cache_leitura
        + uso.cache_escrita * preco.cache_escrita
    )
    return Custo(valor=bruto * fator / tabela.unidade_tokens, moeda=tabela.moeda, tabela=tabela.consultado_em)
