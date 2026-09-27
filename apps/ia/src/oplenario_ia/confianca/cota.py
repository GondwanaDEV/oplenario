"""A cota de IA de cada Casa (Faixa B / B.9, docs/25 Eixo 8.3, ADR-0014): orçamento mensal com aviso a 80%; ao
estourar, o SEGUNDO PLANO (o que a IA faz sozinha — resumo cidadão, conferência institucional) pausa primeiro; o que
uma pessoa pediu na tela segue até o TETO DURO, e então "IA indisponível — cota da Casa" (R-IA-1: a tela faz tudo).
Nunca troca para um modelo pior em silêncio por causa de custo.

O orçamento vem do core (`OrcamentoIADefinido`, valores do plano da Casa) na moeda da tabela de preços; o gasto é a
soma do custo registrado das execuções do mês civil da Casa (America/Fortaleza). Casa sem orçamento definido só mede.
Modelo sem preço na tabela não soma (o consumo fica `parcial` no painel) — não inventa custo.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
from decimal import Decimal
from typing import Literal, Protocol
from zoneinfo import ZoneInfo

from pydantic import BaseModel, Field, model_validator

FUSO_DA_CASA = ZoneInfo("America/Fortaleza")
LIMIAR_AVISO = Decimal("0.8")

SEGUNDO_PLANO = frozenset({"resumo.redigir", "conferencia.redigir"})
"""Operações que a IA faz sozinha, sem uma pessoa esperando na tela: pausam primeiro quando o orçamento estoura."""

EstadoCota = Literal["sem_orcamento", "normal", "aviso", "segundo_plano_pausado", "esgotada"]


class Orcamento(BaseModel):
    mensal: Decimal = Field(ge=0)
    teto_duro: Decimal = Field(ge=0)
    moeda: str = Field(min_length=3, max_length=3)

    @model_validator(mode="after")
    def _teto_cobre_o_mensal(self) -> Orcamento:
        if self.teto_duro < self.mensal:
            raise ValueError("o teto duro não pode ser menor que o orçamento mensal")
        return self


def estado(gasto: Decimal, orcamento: Orcamento | None) -> EstadoCota:
    if orcamento is None:
        return "sem_orcamento"
    if gasto >= orcamento.teto_duro:
        return "esgotada"
    if gasto >= orcamento.mensal:
        return "segundo_plano_pausado"
    if gasto >= orcamento.mensal * LIMIAR_AVISO:
        return "aviso"
    return "normal"


def libera(e: EstadoCota, operacao: str) -> bool:
    if e == "esgotada":
        return False
    if e == "segundo_plano_pausado":
        return operacao not in SEGUNDO_PLANO
    return True


def inicio_do_mes(agora: datetime) -> datetime:
    """O primeiro instante do mês civil da Casa em que `agora` cai (em UTC)."""
    local = agora.astimezone(FUSO_DA_CASA)
    return local.replace(day=1, hour=0, minute=0, second=0, microsecond=0).astimezone(UTC)


def inicio_do_mes_seguinte(agora: datetime) -> datetime:
    local = agora.astimezone(FUSO_DA_CASA)
    ano, mes = (local.year + 1, 1) if local.month == 12 else (local.year, local.month + 1)
    return local.replace(year=ano, month=mes, day=1, hour=0, minute=0, second=0, microsecond=0).astimezone(UTC)


class FonteCota(Protocol):
    """De onde a cota lê: o orçamento da Casa (armazém do satélite) e o gasto (registro da Camada de Confiança)."""

    def orcamento(self, ente_id: str) -> Orcamento | None: ...

    def gasto_desde(self, ente_id: str, desde: datetime) -> Decimal: ...


class _OrcamentoDe(Protocol):
    def orcamento(self, ente_id: str) -> Orcamento | None: ...


class _GastoDe(Protocol):
    def gasto_desde(self, ente_id: str, desde: datetime) -> Decimal: ...


class Fonte:
    """A fonte da cota montada das duas pontas: o orçamento do armazém e o gasto do registro."""

    def __init__(self, orcamentos: _OrcamentoDe, gastos: _GastoDe) -> None:
        self._o = orcamentos
        self._g = gastos

    def orcamento(self, ente_id: str) -> Orcamento | None:
        return self._o.orcamento(ente_id)

    def gasto_desde(self, ente_id: str, desde: datetime) -> Decimal:
        return self._g.gasto_desde(ente_id, desde)


class Cota:
    def __init__(self, fonte: FonteCota, agora: Callable[[], datetime] = lambda: datetime.now(UTC)) -> None:
        self._fonte = fonte
        self._agora = agora

    def estado(self, ente_id: str) -> EstadoCota:
        o = self._fonte.orcamento(ente_id)
        if o is None:
            return "sem_orcamento"
        return estado(self._fonte.gasto_desde(ente_id, inicio_do_mes(self._agora())), o)

    def liberada(self, ente_id: str, operacao: str) -> bool:
        return libera(self.estado(ente_id), operacao)


class CotaFechada(Exception):
    """O trabalho de segundo plano esbarrou na cota da Casa: pausa (não é falha) até a cota reabrir."""
