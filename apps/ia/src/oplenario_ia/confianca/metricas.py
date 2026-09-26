"""Qualidade em produção medida pelo que a pessoa faz (§22.11.8): aceito / editado / descartado e erros reportados,
por Casa e por operação; e o consumo de cada Casa (Eixo 8.3). Só contagens e valores — o insumo do orçamento e do
painel da Casa (B.9)."""

from __future__ import annotations

from decimal import Decimal

from pydantic import BaseModel

from oplenario_ia.confianca.registro import Evento, RegistroExecucao, ReporteErro, RevisaoHumana


class MetricaOperacao(BaseModel):
    aprovados: int = 0
    editados: int = 0
    descartados: int = 0
    erros_reportados: int = 0

    @property
    def revisados(self) -> int:
        return self.aprovados + self.editados + self.descartados

    @property
    def taxa_aceitacao(self) -> float | None:
        return None if self.revisados == 0 else (self.aprovados + self.editados) / self.revisados


def por_ente_e_operacao(eventos: list[Evento]) -> dict[tuple[str, str], MetricaOperacao]:
    m: dict[tuple[str, str], MetricaOperacao] = {}
    for e in eventos:
        if not isinstance(e, RevisaoHumana | ReporteErro):
            continue
        alvo = m.setdefault((e.ente_id, e.operacao), MetricaOperacao())
        if isinstance(e, ReporteErro):
            alvo.erros_reportados += 1
        elif e.desfecho == "aprovado":
            alvo.aprovados += 1
        elif e.desfecho == "editado":
            alvo.editados += 1
        else:
            alvo.descartados += 1
    return m


class ConsumoEnte(BaseModel):
    execucoes: int = 0
    tokens_entrada: int = 0
    tokens_saida: int = 0
    tokens_cache: int = 0
    custo: Decimal = Decimal(0)
    moeda: str | None = None
    parcial: bool = False  # alguma execução usou modelo sem preço na tabela: o custo real é MAIOR que o somado


def consumo_por_ente(eventos: list[Evento]) -> dict[str, ConsumoEnte]:
    c: dict[str, ConsumoEnte] = {}
    for e in eventos:
        if not isinstance(e, RegistroExecucao):
            continue
        alvo = c.setdefault(e.ente_id, ConsumoEnte())
        alvo.execucoes += 1
        if e.uso is not None:
            alvo.tokens_entrada += e.uso.entrada
            alvo.tokens_saida += e.uso.saida
            alvo.tokens_cache += e.uso.cache_leitura + e.uso.cache_escrita
        if e.custo is None:
            continue
        if e.custo.valor is None:
            alvo.parcial = True
        else:
            alvo.custo += e.custo.valor
            alvo.moeda = e.custo.moeda
    return c
