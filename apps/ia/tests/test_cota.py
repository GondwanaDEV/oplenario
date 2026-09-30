"""B.9 — a cota de IA da Casa: aviso a 80%; ao estourar o orçamento o SEGUNDO PLANO pausa primeiro; o que a pessoa pediu
segue até o teto duro, e então R-IA-1 "cota da Casa". Nada vai ao fornecedor com a cota fechada, e a execução fica
registrada. O consumo do mês por capacidade é o que o painel da Casa lê."""

from __future__ import annotations

import os
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.avaliacao.custo import Custo
from oplenario_ia.busca.embeddings import EmbedderFake
from oplenario_ia.confianca.consumo import consumo_do_mes, intervalo
from oplenario_ia.confianca.cota import (
    Cota,
    Fonte,
    Orcamento,
    estado,
    inicio_do_mes,
    inicio_do_mes_seguinte,
    libera,
)
from oplenario_ia.confianca.registro import RegistroExecucao, RegistroMemoria, ReporteErro, RevisaoHumana
from oplenario_ia.config import Config
from oplenario_ia.fronteira.cliente import ClienteCore
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Peca, Proveniencia, Sigilo
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.inferencia.gravadora import PortaGravadora
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.resumo import fake as resumo_fake
from oplenario_ia.resumo.redacao import OPERACAO as RESUMO
from oplenario_ia.trabalhador import Trabalhador
from oplenario_ia.transcricao.fake import DiarizadorFake, TranscritorFake
from test_resumo import CoreComTexto
from test_trabalhador import ENTE, FRASES, VOZES, Relogio

PLANO = Orcamento(mensal=Decimal("100"), teto_duro=Decimal("120"), moeda="USD")
SETEMBRO = datetime(2026, 9, 26, 22, 0, tzinfo=UTC)


# ---------- os limiares ----------


@pytest.mark.parametrize(
    ("gasto", "esperado"),
    [("0", "normal"), ("79.99", "normal"), ("80", "aviso"), ("100", "segundo_plano_pausado"), ("120", "esgotada")],
)
def test_estado_pelos_limiares(gasto: str, esperado: str) -> None:
    assert estado(Decimal(gasto), PLANO) == esperado


def test_sem_orcamento_so_mede() -> None:
    assert estado(Decimal("1000000"), None) == "sem_orcamento"
    assert libera("sem_orcamento", "conferencia.redigir")


def test_segundo_plano_pausa_primeiro_e_a_pessoa_segue_ate_o_teto() -> None:
    assert not libera("segundo_plano_pausado", "resumo.redigir")
    assert not libera("segundo_plano_pausado", "conferencia.redigir")
    assert libera("segundo_plano_pausado", "agente.responder")
    assert libera("aviso", "resumo.redigir")
    assert not libera("esgotada", "agente.responder")


def test_teto_duro_nao_fica_abaixo_do_mensal() -> None:
    with pytest.raises(ValueError):
        Orcamento(mensal=Decimal("100"), teto_duro=Decimal("50"), moeda="USD")


def test_mes_civil_da_casa() -> None:
    # 01/10 às 02:00 UTC ainda é 30/09 em Fortaleza: o mês da Casa é setembro
    assert inicio_do_mes(datetime(2026, 10, 1, 2, 0, tzinfo=UTC)) == datetime(2026, 9, 1, 3, 0, tzinfo=UTC)
    assert inicio_do_mes_seguinte(SETEMBRO) == datetime(2026, 10, 1, 3, 0, tzinfo=UTC)
    assert inicio_do_mes_seguinte(datetime(2026, 12, 20, tzinfo=UTC)) == datetime(2027, 1, 1, 3, 0, tzinfo=UTC)
    assert intervalo("2026-02", SETEMBRO)[0] == "2026-02"
    with pytest.raises(ValueError):
        intervalo("2026-13", SETEMBRO)


# ---------- o núcleo ----------


class FonteFixa:
    def __init__(self, orcamento: Orcamento | None, gasto: str, *, quebrada: bool = False) -> None:
        self.o, self.g, self.quebrada = orcamento, Decimal(gasto), quebrada

    def orcamento(self, ente_id: str) -> Orcamento | None:
        if self.quebrada:
            raise RuntimeError("banco fora")
        return self.o

    def gasto_desde(self, ente_id: str, desde: datetime) -> Decimal:
        return self.g


def _pedido(operacao: str) -> PedidoGovernado:
    return PedidoGovernado(
        ente_id=ENTE,
        correlation_id="c",
        operacao=operacao,
        instrucoes="Resuma.",
        pecas=[Peca(texto="Texto público.", proveniencia=Proveniencia(origem="x", sigilo=Sigilo.PUBLICO))],
    )


def _nucleo(fonte: FonteFixa) -> tuple[Nucleo, PortaGravadora, RegistroMemoria]:
    porta = PortaGravadora(PortaFake({RESUMO: "ok", "agente.responder": "ok"}))
    reg = RegistroMemoria()
    return Nucleo(porta, reg, cota=Cota(fonte, agora=lambda: SETEMBRO)), porta, reg


def test_cota_fechada_nada_vai_ao_fornecedor_e_a_execucao_fica_registrada() -> None:
    nucleo, porta, reg = _nucleo(FonteFixa(PLANO, "100"))
    r = nucleo.executar(_pedido(RESUMO))
    assert getattr(r, "motivo", None) == "cota"
    assert "cota da Casa" in getattr(r, "mensagem", "")
    assert porta.recebidos == []
    [e] = reg.eventos()
    assert isinstance(e, RegistroExecucao)
    assert (e.decisao_governanca, e.resultado, e.motivo_indisponivel, e.custo) == ("cota", "indisponivel", "cota", None)
    assert nucleo.executar(_pedido("agente.responder")).__class__.__name__ == "Artefato", "a pessoa segue até o teto"


def test_teto_duro_fecha_tambem_para_a_pessoa() -> None:
    nucleo, porta, _ = _nucleo(FonteFixa(PLANO, "120"))
    assert getattr(nucleo.executar(_pedido("agente.responder")), "motivo", None) == "cota"
    assert porta.recebidos == []


def test_medicao_fora_do_ar_nao_para_a_casa() -> None:
    nucleo, porta, _ = _nucleo(FonteFixa(PLANO, "0", quebrada=True))
    assert nucleo.executar(_pedido(RESUMO)).__class__.__name__ == "Artefato"
    assert len(porta.recebidos) == 1


# ---------- o trabalhador ----------


def test_orcamento_do_core_e_o_segundo_plano_pausado() -> None:
    core = CoreComTexto()
    core.eventos.append(
        {
            "seq": 1,
            "ente-id": ENTE,
            "tipo": "OrcamentoIADefinido",
            "versao": 1,
            "chave": f"OrcamentoIADefinido:v1:{ENTE}:1",
            "criado-em": "2026-09-26T20:00:00Z",
            "payload": {"mensal": "0", "teto-duro": "10", "moeda": "USD", "definido-em": "2026-09-26T20:00:00Z"},
        }
    )
    core.proposicao(2)
    arm, rel = ArmazemMemoria(), Relogio()
    reg = RegistroMemoria()
    t = Trabalhador(
        ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(core))),
        arm,
        TranscritorFake(FRASES),
        DiarizadorFake(VOZES),
        nucleo=Nucleo(PortaFake({RESUMO: resumo_fake.redigir}), reg, cota=Cota(Fonte(arm, reg), agora=rel)),
        embedder=EmbedderFake(),
        agora=rel,
    )
    t.ciclo()
    assert arm.orcamento(ENTE) == Orcamento(mensal=Decimal("0"), teto_duro=Decimal("10"), moeda="USD")
    resumo = next(x for x in arm.trabalhos() if x["tipo"] == "redigir_resumo")
    assert (resumo["estado"], resumo["tentativas"]) == ("pendente", 0), "pausa não é falha nem conta tentativa"
    assert "cota" in resumo["erro"]
    assert core.recebidos == [], "ninguém é avisado de falha: é pausa"
    rel.t += timedelta(minutes=30)
    t.ciclo()
    assert next(x for x in arm.trabalhos() if x["tipo"] == "redigir_resumo")["tentativas"] == 0
    # o plano da Casa aumentou: na próxima volta depois da pausa, o resumo sai
    arm.definir_orcamento(ENTE, Orcamento(mensal=Decimal("50"), teto_duro=Decimal("60"), moeda="USD"), rel.t, "k2")
    rel.t += timedelta(hours=1)
    t.ciclo()
    assert [a["tipo"] for a in core.recebidos] == ["ResumoCidadaoPronto"]


def test_orcamento_mais_antigo_nao_volta_atras() -> None:
    arm = ArmazemMemoria()
    arm.definir_orcamento(ENTE, PLANO, SETEMBRO, "k2")
    arm.definir_orcamento(
        ENTE, Orcamento(mensal=Decimal("1"), teto_duro=Decimal("1"), moeda="USD"), SETEMBRO - timedelta(days=1), "k1"
    )
    assert arm.orcamento(ENTE) == PLANO


def test_casa_suspensa_zera_a_cota_e_a_reativada_volta_a_so_medir() -> None:
    """ADR-0018: a suspensão da Casa manda 0/0 (nem o que a pessoa pede roda); a reativação de uma Casa que antes só
    media manda o orçamento SEM VALOR — ela volta a só medir, e um 0/0 atrasado não a trava de novo."""

    def evento(seq: int, payload: dict[str, Any]) -> dict[str, Any]:
        return {
            "seq": seq,
            "ente-id": ENTE,
            "tipo": "OrcamentoIADefinido",
            "versao": 1,
            "chave": f"OrcamentoIADefinido:v1:{ENTE}:{seq}",
            "criado-em": payload["definido-em"],
            "payload": payload,
        }

    core = CoreComTexto()
    core.eventos.append(
        evento(1, {"mensal": "0", "teto-duro": "0", "moeda": "USD", "definido-em": "2026-09-26T20:00:00Z"})
    )
    arm, rel = ArmazemMemoria(), Relogio()
    reg = RegistroMemoria()
    cota = Cota(Fonte(arm, reg), agora=rel)
    t = Trabalhador(
        ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(core))),
        arm,
        TranscritorFake(FRASES),
        DiarizadorFake(VOZES),
        nucleo=Nucleo(PortaFake({RESUMO: resumo_fake.redigir}), reg, cota=cota),
        embedder=EmbedderFake(),
        agora=rel,
    )
    t.ciclo()
    assert cota.estado(ENTE) == "esgotada", "suspensa: a cota fica zerada"
    assert not cota.liberada(ENTE, "busca.responder")
    core.eventos.append(
        evento(2, {"mensal": None, "teto-duro": None, "moeda": "USD", "definido-em": "2026-09-27T20:00:00Z"})
    )
    t.ciclo()
    assert arm.orcamento(ENTE) is None
    assert cota.estado(ENTE) == "sem_orcamento", "reativada: volta a só medir"
    arm.definir_orcamento(
        ENTE,
        Orcamento(mensal=Decimal("0"), teto_duro=Decimal("0"), moeda="USD"),
        datetime(2026, 9, 26, 21, 0, tzinfo=UTC),
        "atrasado",
    )
    assert arm.orcamento(ENTE) is None, "um 0/0 atrasado não volta atrás"


def test_orcamento_sem_valor_vem_com_os_dois_nulos() -> None:
    from oplenario_ia.fronteira.contrato import OrcamentoIADefinidoV1

    ok = OrcamentoIADefinidoV1.model_validate(
        {"mensal": None, "teto-duro": None, "moeda": "USD", "definido-em": "2026-09-27T20:00:00Z"}
    )
    assert ok.mensal is None and ok.teto_duro is None
    with pytest.raises(ValueError):
        OrcamentoIADefinidoV1.model_validate(
            {"mensal": "10", "teto-duro": None, "moeda": "USD", "definido-em": "2026-09-27T20:00:00Z"}
        )


# ---------- o consumo do mês ----------


def _execucao(operacao: str, custo: str | None, instante: datetime, resultado: str = "artefato") -> RegistroExecucao:
    return RegistroExecucao(
        execucao_id=f"e-{operacao}-{instante.isoformat()}",
        instante=instante,
        ente_id=ENTE,
        correlation_id="c",
        operacao=operacao,
        decisao_governanca="liberado",
        motivos_bloqueio=[],
        redacoes={},
        terceiros=0,
        hash_entrada="h",
        vendor="anthropic",
        modelo="m",
        custo=Custo(valor=None if custo is None else Decimal(custo), moeda="USD", tabela="2026-09-26"),
        resultado=resultado,  # type: ignore[arg-type]
    )


def _registro_de_setembro() -> RegistroMemoria:
    reg = RegistroMemoria()
    d = datetime(2026, 9, 10, 12, tzinfo=UTC)
    reg.anexar(_execucao("ata.redigir", "50", d))
    reg.anexar(_execucao("ata.redigir", "35", d))
    reg.anexar(_execucao("agente.responder", None, d))  # modelo sem preço: consumo parcial
    reg.anexar(_execucao("agente.responder", "1", datetime(2026, 8, 31, 12, tzinfo=UTC)))  # agosto: fora
    reg.anexar(
        RevisaoHumana(
            execucao_id="x",
            instante=d,
            ente_id=ENTE,
            operacao="ata.redigir",
            revisor="r",
            desfecho="editado",
            proporcao_alterada=0.2,
        )
    )
    reg.anexar(
        ReporteErro(
            execucao_id="x", instante=d, ente_id=ENTE, operacao="ata.redigir", quem="q", categoria="fato_errado"
        )
    )
    return reg


def test_consumo_do_mes_por_capacidade() -> None:
    c = consumo_do_mes(_registro_de_setembro(), ENTE, PLANO, None, agora=lambda: SETEMBRO)
    assert (c.mes, c.gasto, c.estado, c.parcial, c.execucoes) == ("2026-09", Decimal("85"), "aviso", True, 3)
    ata = next(o for o in c.por_operacao if o.operacao == "ata.redigir")
    assert (ata.execucoes, ata.custo, ata.editados, ata.erros_reportados) == (2, Decimal("85"), 1, 1)
    agosto = consumo_do_mes(_registro_de_setembro(), ENTE, PLANO, "2026-08", agora=lambda: SETEMBRO)
    assert (agosto.gasto, agosto.execucoes) == (Decimal("1"), 1)


def test_api_do_consumo() -> None:
    arm = ArmazemMemoria()
    arm.definir_orcamento(ENTE, PLANO, SETEMBRO, "k")
    c = TestClient(criar_app(Config(segredo="s"), arm, EmbedderFake(), registro=_registro_de_setembro()))
    h = {"Authorization": "Bearer s"}
    r = c.get(f"/v1/entes/{ENTE}/consumo?mes=2026-09", headers=h)
    assert r.status_code == 200
    corpo: dict[str, Any] = r.json()
    assert (corpo["gasto"], corpo["estado"], corpo["orcamento"]["teto-duro"]) == ("85", "aviso", "120")
    assert {o["operacao"] for o in corpo["por-operacao"]} == {"ata.redigir", "agente.responder"}
    assert c.get(f"/v1/entes/{ENTE}/consumo?mes=setembro", headers=h).status_code == 400
    assert c.get(f"/v1/entes/{ENTE}/consumo").status_code == 401


# ---------- o registro compartilhado (Postgres) ----------

URL = os.environ.get("OPLENARIO_IA_DATABASE_URL_TESTE")


@pytest.fixture
def registro_pg() -> Iterator[Any]:
    if not URL:
        pytest.skip("OPLENARIO_IA_DATABASE_URL_TESTE não definida")
    import psycopg

    from oplenario_ia.confianca.registro_postgres import RegistroPostgres

    with psycopg.connect(URL, autocommit=True) as c:
        c.execute("DROP SCHEMA IF EXISTS ia CASCADE")
    yield RegistroPostgres(URL)


def test_registro_postgres_soma_o_mes_da_casa(registro_pg: Any) -> None:
    for e in _registro_de_setembro().eventos():
        registro_pg.anexar(e)
    desde = inicio_do_mes(SETEMBRO)
    assert registro_pg.gasto_desde(ENTE, desde) == Decimal("85")
    assert registro_pg.gasto_desde("outra-casa", desde) == Decimal("0")
    c = consumo_do_mes(registro_pg, ENTE, PLANO, None, agora=lambda: SETEMBRO)
    assert (c.gasto, c.parcial, c.execucoes) == (Decimal("85"), True, 3)
    assert len(registro_pg.eventos()) == 6
