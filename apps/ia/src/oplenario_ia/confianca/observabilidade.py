"""A observabilidade da IA para o OPERADOR da plataforma (Onda E, `observabilidade-ia`; §22.8): todas as Casas juntas,
numa janela de horas — volume, tempo de resposta (p50/p95), quantas execuções não rodaram e por quê, e o custo, por
capacidade e por fornecedor/modelo, com a série por hora. Só contagens, tempos e valores do registro da Camada de
Confiança (B4: o registro não tem texto; aqui nem o ente sai — só quantas Casas usaram).

Escopo honesto: o registro cobre as execuções do NÚCLEO (o modelo de linguagem: ata, resumo, agente, conferência,
copiloto). Transcrição e embeddings da busca não passam por ele e não aparecem aqui."""

from __future__ import annotations

import math
from collections import Counter
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from pydantic import BaseModel, ConfigDict

from oplenario_ia.confianca.registro import Evento, RegistroExecucao

JANELA_MAXIMA_H = 168  # uma semana


def _kebab(s: str) -> str:
    return s.replace("_", "-")


class _Fio(BaseModel):
    model_config = ConfigDict(alias_generator=_kebab, populate_by_name=True)


class Agregado(_Fio):
    execucoes: int = 0
    indisponiveis: int = 0
    latencia_p50_ms: int | None = None
    latencia_p95_ms: int | None = None
    custo: Decimal = Decimal(0)
    parcial: bool = False  # alguma execução com modelo sem preço: o custo real é maior


class PorOperacao(Agregado):
    operacao: str


class PorFornecedor(Agregado):
    vendor: str
    modelo: str | None


class Hora(_Fio):
    inicio: datetime
    execucoes: int
    indisponiveis: int


class Motivo(_Fio):
    motivo: str
    execucoes: int


class Observabilidade(_Fio):
    desde: datetime
    ate: datetime
    horas: int
    casas: int
    moeda: str | None  # a da tabela de preços (uma só); None = nenhuma execução com custo
    total: Agregado
    por_operacao: list[PorOperacao]
    por_fornecedor: list[PorFornecedor]
    motivos_indisponivel: list[Motivo]
    por_hora: list[Hora]


def percentil(valores: list[int], p: float) -> int | None:
    """Percentil pelo posto mais próximo (sem interpolar: o número é de uma execução real)."""
    if not valores:
        return None
    ordenados = sorted(valores)
    return ordenados[max(0, math.ceil(p / 100 * len(ordenados)) - 1)]


def _agregar(execs: list[RegistroExecucao]) -> dict[str, object]:
    lat = [e.latencia_ms for e in execs if e.latencia_ms is not None]
    return {
        "execucoes": len(execs),
        "indisponiveis": sum(e.resultado == "indisponivel" for e in execs),
        "latencia_p50_ms": percentil(lat, 50),
        "latencia_p95_ms": percentil(lat, 95),
        "custo": sum((e.custo.valor for e in execs if e.custo and e.custo.valor is not None), Decimal(0)),
        "parcial": any(e.custo is not None and e.custo.valor is None for e in execs),
    }


def observar(eventos: list[Evento], desde: datetime, ate: datetime) -> Observabilidade:
    execs = [e for e in eventos if isinstance(e, RegistroExecucao) and desde <= e.instante < ate]
    por_op: dict[str, list[RegistroExecucao]] = {}
    por_forn: dict[tuple[str, str | None], list[RegistroExecucao]] = {}
    for e in execs:
        por_op.setdefault(e.operacao, []).append(e)
        por_forn.setdefault((e.vendor, e.modelo), []).append(e)
    horas = max(1, math.ceil((ate - desde).total_seconds() / 3600))
    baldes = [desde + timedelta(hours=i) for i in range(horas)]
    contagem: Counter[int] = Counter()
    falhas: Counter[int] = Counter()
    for e in execs:
        i = min(horas - 1, int((e.instante - desde).total_seconds() // 3600))
        contagem[i] += 1
        falhas[i] += e.resultado == "indisponivel"
    motivos = Counter(e.motivo_indisponivel or "desconhecido" for e in execs if e.resultado == "indisponivel")
    return Observabilidade(
        desde=desde,
        ate=ate,
        horas=horas,
        casas=len({e.ente_id for e in execs}),
        moeda=next((e.custo.moeda for e in execs if e.custo), None),
        total=Agregado(**_agregar(execs)),
        por_operacao=sorted(
            (PorOperacao(operacao=k, **_agregar(v)) for k, v in por_op.items()),
            key=lambda o: (-o.execucoes, o.operacao),
        ),
        por_fornecedor=sorted(
            (PorFornecedor(vendor=k[0], modelo=k[1], **_agregar(v)) for k, v in por_forn.items()),
            key=lambda o: (-o.execucoes, o.vendor, o.modelo or ""),
        ),
        motivos_indisponivel=[Motivo(motivo=m, execucoes=n) for m, n in motivos.most_common()],
        por_hora=[Hora(inicio=b, execucoes=contagem[i], indisponiveis=falhas[i]) for i, b in enumerate(baldes)],
    )


def janela(horas: int, agora: Callable[[], datetime] = lambda: datetime.now(UTC)) -> tuple[datetime, datetime]:
    """[agora - horas, agora), com o início na hora cheia — os baldes por hora ficam alinhados ao relógio."""
    if not 1 <= horas <= JANELA_MAXIMA_H:
        raise ValueError(f"horas entre 1 e {JANELA_MAXIMA_H}")
    fim = agora().astimezone(UTC)
    inicio = (fim - timedelta(hours=horas)).replace(minute=0, second=0, microsecond=0)
    return inicio, fim
