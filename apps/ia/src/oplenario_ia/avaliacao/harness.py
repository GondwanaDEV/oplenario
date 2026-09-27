"""Roda um conjunto de avaliação PELO NÚCLEO (o mesmo pipeline da produção: filtro, porta, confiança, custo) e
confere cada expectativa. Com o fake é determinístico e de custo zero (CI); com fornecedor real é o gate manual
obrigatório antes de trocar fornecedor ou modelo (§22.11.8)."""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
from decimal import Decimal

from pydantic import BaseModel

from oplenario_ia.avaliacao.conjunto import Caso, Conjunto, Esperado
from oplenario_ia.avaliacao.custo import TabelaPrecos, tabela_padrao
from oplenario_ia.confianca.artefato import Artefato
from oplenario_ia.confianca.indisponivel import Indisponivel
from oplenario_ia.confianca.registro import RegistroExecucao, RegistroMemoria
from oplenario_ia.erros import ErroIA
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.inferencia.fake import PortaFake, Roteiro
from oplenario_ia.inferencia.gravadora import PortaGravadora
from oplenario_ia.inferencia.modelo import PedidoInferencia
from oplenario_ia.inferencia.porta import PortaInferencia
from oplenario_ia.nucleo import Nucleo


class ResultadoCaso(BaseModel):
    id: str
    tipo: str
    passou: bool
    pulado: bool = False
    falhas: list[str] = []
    vendor: str | None = None
    modelo: str | None = None
    custo: Decimal | None = None


class Relatorio(BaseModel):
    conjunto: str
    versao: int
    vendor: str
    instante: datetime
    casos: list[ResultadoCaso]

    @property
    def aprovado(self) -> bool:
        return all(c.passou for c in self.casos if not c.pulado)

    @property
    def custo_total(self) -> Decimal:
        return sum((c.custo for c in self.casos if c.custo is not None), Decimal(0))


def _porta_fake(caso: Caso) -> PortaInferencia:
    roteiro: Roteiro | None
    if caso.erro_fake is not None:
        roteiro = ErroIA(
            caso.erro_fake.categoria, "falha simulada", retentavel=caso.erro_fake.retentavel, vendor="fake"
        )
    else:
        roteiro = caso.resposta_fake
    return PortaFake({caso.operacao: roteiro} if roteiro is not None else None, parada=caso.parada_fake)


def _enviado(recebidos: list[PedidoInferencia]) -> str:
    return "\n".join(t for p in recebidos for t in [p.instrucoes, *p.conteudo])


def conferir(e: Esperado, r: Artefato | Indisponivel, recebidos: list[PedidoInferencia]) -> list[str]:
    falhas: list[str] = []
    enviado = _enviado(recebidos)
    falhas += [f"chegou ao fornecedor: {s!r}" for s in e.nunca_enviado if s in enviado]
    falhas += [f"não chegou ao fornecedor: {s!r}" for s in e.enviado_contem if s not in enviado]
    if isinstance(r, Indisponivel):
        if e.resultado != "indisponivel":
            return [*falhas, f"esperava artefato, veio indisponível ({r.motivo})"]
        if r.motivo != e.motivo:
            falhas.append(f"motivo {r.motivo}, esperado {e.motivo}")
        return falhas
    if e.resultado != "artefato":
        return [*falhas, f"esperava indisponível ({e.motivo}), veio artefato"]
    texto = r.texto.casefold()
    falhas += [f"texto não contém {s!r}" for s in e.texto_contem if s.casefold() not in texto]
    falhas += [f"texto contém {s!r}" for s in e.texto_nao_contem if s.casefold() in texto]
    if e.incerteza is not None and r.incerteza.nivel != e.incerteza:
        falhas.append(f"incerteza {r.incerteza.nivel}, esperada {e.incerteza} ({r.incerteza.motivos})")
    falhas += [f"incerteza sem o motivo {m}" for m in e.motivos_incerteza_inclui if m not in r.incerteza.motivos]
    status = [c.status for c in r.citacoes]
    if e.status_citacoes is not None and status != e.status_citacoes:
        falhas.append(f"citações {status}, esperadas {e.status_citacoes}")
    conferidas = status.count("conferida")
    if e.citacoes_conferidas_min is not None and conferidas < e.citacoes_conferidas_min:
        falhas.append(f"{conferidas} citação(ões) conferida(s), mínimo {e.citacoes_conferidas_min}")
    sem_fonte = len(r.paragrafos_sem_fonte)
    if e.paragrafos_sem_fonte_max is not None and sem_fonte > e.paragrafos_sem_fonte_max:
        falhas.append(f"{sem_fonte} parágrafo(s) sem fonte, máximo {e.paragrafos_sem_fonte_max}")
    if e.contaminado is not None and r.contaminado != e.contaminado:
        falhas.append(f"contaminado={r.contaminado}, esperado {e.contaminado}")
    return falhas


def avaliar(
    conjunto: Conjunto,
    porta_real: PortaInferencia | None = None,
    *,
    precos: TabelaPrecos | None = None,
    agora: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> Relatorio:
    """`porta_real=None` = fornecedor fake roteirizado por caso (o modo do CI)."""
    tabela = precos or tabela_padrao()
    resultados: list[ResultadoCaso] = []
    for caso in conjunto.casos:
        if porta_real is not None and caso.apenas_fake:
            resultados.append(ResultadoCaso(id=caso.id, tipo=caso.tipo, passou=True, pulado=True))
            continue
        gravadora = PortaGravadora(porta_real or _porta_fake(caso))
        registro = RegistroMemoria()
        nucleo = Nucleo(gravadora, registro, agora=agora, precos=tabela)
        pedido = PedidoGovernado(
            ente_id="avaliacao",
            correlation_id=f"avaliacao:{conjunto.conjunto}:{caso.id}",
            operacao=caso.operacao,
            instrucoes=caso.instrucoes,
            pecas=caso.pecas,
        )
        r = nucleo.executar(pedido, caso.politica_citacao)
        falhas = conferir(caso.esperado, r, gravadora.recebidos)
        [execucao] = [e for e in registro.eventos() if isinstance(e, RegistroExecucao)]
        resultados.append(
            ResultadoCaso(
                id=caso.id,
                tipo=caso.tipo,
                passou=not falhas,
                falhas=falhas,
                vendor=execucao.vendor,
                modelo=execucao.modelo,
                custo=execucao.custo.valor if execucao.custo else None,
            )
        )
    vendor = porta_real.vendor if porta_real is not None else "fake"
    return Relatorio(
        conjunto=conjunto.conjunto, versao=conjunto.versao, vendor=vendor, instante=agora(), casos=resultados
    )
