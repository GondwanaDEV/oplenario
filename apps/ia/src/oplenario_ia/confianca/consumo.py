"""O consumo de IA de uma Casa num mês (B.9, docs/25 Eixos 8.3 e 8.4): o gasto × o orçamento, o estado da cota e, por
capacidade, quantas execuções, quanto custaram, quantas não rodaram, e o que as pessoas fizeram com o resultado
(aprovado, editado, descartado, erro reportado). Só contagens e valores (8.5) — é o que o painel da Casa mostra."""

from __future__ import annotations

import re
from collections.abc import Callable
from datetime import UTC, datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict

from oplenario_ia.confianca.cota import (
    FUSO_DA_CASA,
    EstadoCota,
    Orcamento,
    estado,
    inicio_do_mes,
    inicio_do_mes_seguinte,
)
from oplenario_ia.confianca.metricas import consumo_por_ente, por_ente_e_operacao
from oplenario_ia.confianca.registro import ConsultaConsumo, RegistroExecucao


def _kebab(s: str) -> str:
    return s.replace("_", "-")


class _Fio(BaseModel):
    model_config = ConfigDict(alias_generator=_kebab, populate_by_name=True)


class OrcamentoFio(_Fio):
    mensal: Decimal
    teto_duro: Decimal
    moeda: str


class ConsumoOperacao(_Fio):
    operacao: str
    execucoes: int = 0
    indisponiveis: int = 0
    custo: Decimal = Decimal(0)
    aprovados: int = 0
    editados: int = 0
    descartados: int = 0
    erros_reportados: int = 0


class ConsumoDoMes(_Fio):
    mes: str
    orcamento: OrcamentoFio | None
    estado: EstadoCota
    gasto: Decimal
    moeda: str | None
    parcial: bool
    execucoes: int
    por_operacao: list[ConsumoOperacao]


_MES = re.compile(r"^(\d{4})-(0[1-9]|1[0-2])$")


def intervalo(mes: str | None, agora: datetime) -> tuple[str, datetime, datetime]:
    """'2026-09' (ou o mês corrente) -> o rótulo e o intervalo [início, início do seguinte) do mês civil da Casa."""
    if mes is None:
        inicio = inicio_do_mes(agora)
    else:
        m = _MES.match(mes)
        if not m:
            raise ValueError("mês no formato AAAA-MM")
        inicio = datetime(int(m[1]), int(m[2]), 1, tzinfo=FUSO_DA_CASA).astimezone(UTC)
    rotulo = inicio.astimezone(FUSO_DA_CASA).strftime("%Y-%m")
    return rotulo, inicio, inicio_do_mes_seguinte(inicio)


def consumo_do_mes(
    registro: ConsultaConsumo,
    ente_id: str,
    orcamento: Orcamento | None,
    mes: str | None = None,
    agora: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> ConsumoDoMes:
    rotulo, desde, ate = intervalo(mes, agora())
    eventos = registro.eventos_entre(ente_id, desde, ate)
    total = consumo_por_ente(eventos).get(ente_id)
    ops: dict[str, ConsumoOperacao] = {}
    for e in eventos:
        if isinstance(e, RegistroExecucao):
            o = ops.setdefault(e.operacao, ConsumoOperacao(operacao=e.operacao))
            o.execucoes += 1
            o.indisponiveis += e.resultado == "indisponivel"
            if e.custo and e.custo.valor is not None:
                o.custo += e.custo.valor
    for (_, operacao), m in por_ente_e_operacao(eventos).items():
        o = ops.setdefault(operacao, ConsumoOperacao(operacao=operacao))
        o.aprovados, o.editados, o.descartados, o.erros_reportados = (
            m.aprovados,
            m.editados,
            m.descartados,
            m.erros_reportados,
        )
    gasto = total.custo if total else Decimal(0)
    return ConsumoDoMes(
        mes=rotulo,
        orcamento=None
        if orcamento is None
        else OrcamentoFio(mensal=orcamento.mensal, teto_duro=orcamento.teto_duro, moeda=orcamento.moeda),
        estado=estado(gasto, orcamento),
        gasto=gasto,
        moeda=(total.moeda if total and total.moeda else None) or (orcamento.moeda if orcamento else None),
        parcial=bool(total and total.parcial),
        execucoes=total.execucoes if total else 0,
        por_operacao=sorted(ops.values(), key=lambda o: o.operacao),
    )
