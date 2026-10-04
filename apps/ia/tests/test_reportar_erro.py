"""Feature 8.4 — "reportar erro" da IA, de ponta a ponta no satélite: a rota de serviço recebe o id da execução e a
categoria (vocabulário fixo do registro — sem texto livre, o registro é SEM conteúdo), confere que a execução é da Casa
do caminho e que produziu um artefato, e anexa o `ReporteErro` pelo núcleo. A mesma pessoa reportando de novo não
conta duas vezes."""

from __future__ import annotations

import os
from collections.abc import Iterator
from datetime import UTC, datetime
from typing import Any

import pytest
from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.busca.embeddings import EmbedderFake
from oplenario_ia.confianca.metricas import por_ente_e_operacao
from oplenario_ia.confianca.registro import RegistroExecucao, RegistroMemoria, ReporteErro
from oplenario_ia.config import Config
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.nucleo import Nucleo

ENTE = "10000000-0000-0000-0000-000000000001"
OUTRA = "20000000-0000-0000-0000-000000000002"
H = {"Authorization": "Bearer s"}


def _exec(execucao_id: str, ente: str = ENTE, resultado: str = "artefato") -> RegistroExecucao:
    return RegistroExecucao(
        execucao_id=execucao_id,
        instante=datetime(2026, 10, 1, 12, tzinfo=UTC),
        ente_id=ente,
        correlation_id="c",
        operacao="agente.responder",
        decisao_governanca="liberado",
        motivos_bloqueio=[],
        redacoes={},
        terceiros=0,
        hash_entrada="h",
        vendor="fake",
        resultado=resultado,  # type: ignore[arg-type]
        motivo_indisponivel=None if resultado == "artefato" else "cota",
    )


def _cliente(reg: RegistroMemoria, segredo: str = "s") -> TestClient:
    nucleo = Nucleo(criar_porta(Config(vendor="fake")), reg)
    return TestClient(criar_app(Config(segredo=segredo), ArmazemMemoria(), EmbedderFake(), nucleo=nucleo, registro=reg))


def _reportes(reg: RegistroMemoria) -> list[ReporteErro]:
    return [e for e in reg.eventos() if isinstance(e, ReporteErro)]


def test_reporta_o_erro_da_execucao_da_casa() -> None:
    reg = RegistroMemoria()
    reg.anexar(_exec("e1"))
    r = _cliente(reg).post(
        f"/v1/entes/{ENTE}/execucoes/e1/reportes", json={"quem": "pessoa-1", "categoria": "citacao_errada"}, headers=H
    )
    assert r.status_code == 200
    assert r.json() == {"execucao-id": "e1", "reportado": True}
    [rep] = _reportes(reg)
    assert (rep.execucao_id, rep.ente_id, rep.operacao, rep.quem, rep.categoria) == (
        "e1",
        ENTE,
        "agente.responder",
        "pessoa-1",
        "citacao_errada",
    )
    assert por_ente_e_operacao(reg.eventos())[(ENTE, "agente.responder")].erros_reportados == 1


def test_a_mesma_pessoa_de_novo_nao_conta_duas_vezes() -> None:
    reg = RegistroMemoria()
    reg.anexar(_exec("e1"))
    c = _cliente(reg)
    for categoria in ("fato_errado", "omissao"):
        r = c.post(f"/v1/entes/{ENTE}/execucoes/e1/reportes", json={"quem": "p1", "categoria": categoria}, headers=H)
        assert r.json() == {"execucao-id": "e1", "reportado": True}
    c.post(f"/v1/entes/{ENTE}/execucoes/e1/reportes", json={"quem": "p2", "categoria": "outro"}, headers=H)
    assert [(e.quem, e.categoria) for e in _reportes(reg)] == [("p1", "fato_errado"), ("p2", "outro")]


def test_execucao_de_outra_casa_ou_sem_artefato_responde_404() -> None:
    reg = RegistroMemoria()
    reg.anexar(_exec("e-outra", ente=OUTRA))
    reg.anexar(_exec("e-cota", resultado="indisponivel"))
    c = _cliente(reg)
    for eid in ("e-outra", "e-cota", "nao-existe"):
        r = c.post(f"/v1/entes/{ENTE}/execucoes/{eid}/reportes", json={"quem": "p", "categoria": "outro"}, headers=H)
        assert r.status_code == 404, eid
    assert _reportes(reg) == []


def test_so_o_vocabulario_do_registro_nada_de_texto_livre() -> None:
    reg = RegistroMemoria()
    reg.anexar(_exec("e1"))
    c = _cliente(reg)
    url = f"/v1/entes/{ENTE}/execucoes/e1/reportes"
    assert c.post(url, json={"quem": "p", "categoria": "a resposta inventou o artigo"}, headers=H).status_code == 422
    assert c.post(url, json={"quem": "p", "categoria": "outro", "texto": "x"}, headers=H).status_code == 422
    assert c.post(url, json={"quem": "", "categoria": "outro"}, headers=H).status_code == 422
    assert c.post(url, json={"quem": "p" * 101, "categoria": "outro"}, headers=H).status_code == 422
    assert _reportes(reg) == []


def test_rota_de_servico() -> None:
    reg = RegistroMemoria()
    reg.anexar(_exec("e1"))
    corpo = {"quem": "p", "categoria": "outro"}
    assert _cliente(reg).post(f"/v1/entes/{ENTE}/execucoes/e1/reportes", json=corpo).status_code == 401
    desligado = _cliente(reg, segredo="")
    assert desligado.post(f"/v1/entes/{ENTE}/execucoes/e1/reportes", json=corpo, headers=H).status_code == 503


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


def test_registro_postgres_le_os_eventos_de_uma_execucao_so_na_casa(registro_pg: Any) -> None:
    registro_pg.anexar(_exec("e1"))
    registro_pg.anexar(_exec("e2"))
    registro_pg.anexar(_exec("e1-outra", ente=OUTRA))
    n = Nucleo(criar_porta(Config(vendor="fake")), registro_pg)
    n.registrar_reporte("e1", ENTE, "agente.responder", "p", "linguagem")
    eventos = registro_pg.eventos_da_execucao(ENTE, "e1")
    assert [e.tipo for e in eventos] == ["execucao", "reporte_erro"]
    assert registro_pg.eventos_da_execucao(ENTE, "e1-outra") == []
