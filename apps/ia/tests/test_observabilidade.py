"""Onda E — a observabilidade da IA para o OPERADOR: todas as Casas numa janela de horas, só contagens, tempos e custo
(sem texto, sem ente). p50/p95 pelo posto mais próximo, o que não rodou agrupado pelo motivo, a série por hora alinhada
ao relógio, e a rota só com o segredo de serviço."""

from __future__ import annotations

import os
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

import pytest
from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.avaliacao.custo import Custo
from oplenario_ia.busca.embeddings import EmbedderFake
from oplenario_ia.confianca.observabilidade import JANELA_MAXIMA_H, janela, observar, percentil
from oplenario_ia.confianca.registro import RegistroExecucao, RegistroMemoria, RevisaoHumana
from oplenario_ia.config import Config

DESDE = datetime(2026, 9, 29, 10, tzinfo=UTC)
ATE = DESDE + timedelta(hours=3)


def _exec(
    ente: str,
    operacao: str,
    minutos: int,
    latencia: int | None,
    custo: str | None = "1",
    vendor: str = "anthropic",
    modelo: str | None = "m",
    motivo: str | None = None,
) -> RegistroExecucao:
    return RegistroExecucao(
        execucao_id=f"e-{ente}-{operacao}-{minutos}",
        instante=DESDE + timedelta(minutes=minutos),
        ente_id=ente,
        correlation_id="c",
        operacao=operacao,
        decisao_governanca="liberado",
        motivos_bloqueio=[],
        redacoes={},
        terceiros=0,
        hash_entrada="h",
        vendor=vendor,
        modelo=modelo,
        custo=Custo(valor=None if custo is None else Decimal(custo), moeda="USD", tabela="2026-09-26"),
        latencia_ms=latencia,
        resultado="indisponivel" if motivo else "artefato",
        motivo_indisponivel=motivo,  # type: ignore[arg-type]
    )


def _registro() -> RegistroMemoria:
    reg = RegistroMemoria()
    for i, lat in enumerate([100, 200, 300, 400, 5000]):
        reg.anexar(_exec("casa-a", "ata.redigir", i, lat))
    reg.anexar(_exec("casa-b", "agente.responder", 70, 800, custo=None))  # modelo sem preço: parcial
    reg.anexar(_exec("casa-b", "agente.responder", 75, None, custo="0", vendor="fake", modelo=None, motivo="cota"))
    reg.anexar(_exec("casa-c", "resumo.redigir", 130, 50, vendor="fake", modelo=None, motivo="fornecedor_fora"))
    reg.anexar(_exec("casa-a", "ata.redigir", -5, 10))  # antes da janela: fora
    reg.anexar(_exec("casa-a", "ata.redigir", 180, 10))  # no fim (exclusivo): fora
    reg.anexar(
        RevisaoHumana(
            execucao_id="x",
            instante=DESDE,
            ente_id="casa-a",
            operacao="ata.redigir",
            revisor="r",
            desfecho="editado",
            proporcao_alterada=0.2,
        )
    )
    return reg


def test_percentil_pelo_posto_mais_proximo() -> None:
    assert percentil([], 95) is None
    assert percentil([7], 50) == 7
    assert percentil([100, 200, 300, 400, 5000], 50) == 300
    assert percentil([100, 200, 300, 400, 5000], 95) == 5000
    assert percentil(list(range(1, 101)), 95) == 95


def test_observar_todas_as_casas_na_janela() -> None:
    o = observar(_registro().eventos(), DESDE, ATE)
    assert (o.horas, o.casas, o.moeda) == (3, 3, "USD")
    t = o.total
    assert (t.execucoes, t.indisponiveis, t.custo, t.parcial) == (8, 2, Decimal("6"), True)
    assert (t.latencia_p50_ms, t.latencia_p95_ms) == (300, 5000)
    assert [p.operacao for p in o.por_operacao] == ["ata.redigir", "agente.responder", "resumo.redigir"]
    ata = o.por_operacao[0]
    assert (ata.execucoes, ata.custo, ata.parcial, ata.latencia_p50_ms) == (5, Decimal("5"), False, 300)
    assert [(f.vendor, f.modelo, f.execucoes) for f in o.por_fornecedor] == [("anthropic", "m", 6), ("fake", None, 2)]
    assert {(m.motivo, m.execucoes) for m in o.motivos_indisponivel} == {("cota", 1), ("fornecedor_fora", 1)}
    assert [(h.inicio.hour, h.execucoes, h.indisponiveis) for h in o.por_hora] == [(10, 5, 0), (11, 2, 1), (12, 1, 1)]


def test_janela_vazia_tem_os_baldes_zerados() -> None:
    o = observar([], DESDE, ATE)
    assert (o.casas, o.moeda, o.total.execucoes, o.total.latencia_p95_ms) == (0, None, 0, None)
    assert [h.execucoes for h in o.por_hora] == [0, 0, 0]


def test_o_fio_nao_leva_ente_nem_texto() -> None:
    fio = observar(_registro().eventos(), DESDE, ATE).model_dump(mode="json", by_alias=True)
    assert "casa-a" not in str(fio)
    assert set(fio) == {
        "desde",
        "ate",
        "horas",
        "casas",
        "moeda",
        "total",
        "por-operacao",
        "por-fornecedor",
        "motivos-indisponivel",
        "por-hora",
    }
    assert fio["total"]["latencia-p95-ms"] == 5000


def test_janela_alinhada_a_hora_cheia() -> None:
    agora = datetime(2026, 9, 29, 13, 25, 40, tzinfo=UTC)
    desde, ate = janela(24, agora=lambda: agora)
    assert (desde, ate) == (datetime(2026, 9, 28, 13, tzinfo=UTC), agora)
    for ruim in (0, JANELA_MAXIMA_H + 1, -3):
        with pytest.raises(ValueError):
            janela(ruim, agora=lambda: agora)


def test_api_da_observabilidade() -> None:
    reg = RegistroMemoria()
    agora = datetime.now(UTC)
    reg.anexar(_exec("casa-a", "ata.redigir", 0, 120).model_copy(update={"instante": agora - timedelta(minutes=30)}))
    reg.anexar(_exec("casa-b", "ata.redigir", 0, 80).model_copy(update={"instante": agora - timedelta(hours=30)}))
    c = TestClient(criar_app(Config(segredo="s"), ArmazemMemoria(), EmbedderFake(), registro=reg))
    h = {"Authorization": "Bearer s"}
    r = c.get("/v1/observabilidade?horas=24", headers=h)
    assert r.status_code == 200
    corpo: dict[str, Any] = r.json()
    assert (corpo["casas"], corpo["total"]["execucoes"]) == (1, 1)
    assert c.get("/v1/observabilidade", headers=h).json()["total"]["execucoes"] == 1  # padrão: 24 h
    assert c.get("/v1/observabilidade?horas=48", headers=h).json()["casas"] == 2
    assert c.get("/v1/observabilidade?horas=0", headers=h).status_code == 400
    assert c.get("/v1/observabilidade?horas=200", headers=h).status_code == 400
    assert c.get("/v1/observabilidade?horas=abc", headers=h).status_code == 422
    assert c.get("/v1/observabilidade").status_code == 401


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


def test_registro_postgres_le_todas_as_casas_na_janela(registro_pg: Any) -> None:
    for e in _registro().eventos():
        registro_pg.anexar(e)
    eventos = registro_pg.eventos_de_todas_entre(DESDE, ATE)
    assert len(eventos) == 8, "só as execuções dentro da janela (a revisão humana não entra)"
    assert observar(eventos, DESDE, ATE).casas == 3
