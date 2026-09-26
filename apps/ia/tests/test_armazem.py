"""Contrato do armazenamento do satélite — a MESMA bateria no adaptador em memória e no Postgres (schema `ia`).

O Postgres roda quando `OPLENARIO_IA_DATABASE_URL_TESTE` aponta para um banco descartável (no CI, o serviço do job
`ia`); sem ela, só a memória roda e o teste do Postgres é pulado — nunca aponte para um banco com dados reais.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest

from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.armazem.porta import Armazem, NovaTranscricao, NovoTrabalho
from oplenario_ia.transcricao.modelo import Trecho

URL = os.environ.get("OPLENARIO_IA_DATABASE_URL_TESTE")
AGORA = datetime(2026, 9, 26, 22, 0, tzinfo=UTC)
ENTE = "10000000-0000-0000-0000-000000000001"


@pytest.fixture(params=["memoria", "postgres"])
def armazem(request: pytest.FixtureRequest) -> Iterator[Armazem]:
    if request.param == "memoria":
        yield ArmazemMemoria()
        return
    if not URL:
        pytest.skip("OPLENARIO_IA_DATABASE_URL_TESTE não definida")
    import psycopg

    from oplenario_ia.armazem.postgres import ArmazemPostgres

    with psycopg.connect(URL, autocommit=True) as c:
        c.execute("DROP SCHEMA IF EXISTS ia CASCADE")
    yield ArmazemPostgres(URL)


def novo(chave: str, tipo: str = "transcrever") -> NovoTrabalho:
    return NovoTrabalho(tipo, chave, ENTE, {"segmento-id": chave})


def nova(seg: str) -> NovaTranscricao:
    return NovaTranscricao(
        ENTE,
        "30000000-0000-0000-0000-000000000003",
        seg,
        "pt",
        12.5,
        "fake-asr-1",
        None,
        0.5,
        [Trecho(0.0, 5.0, "Olá", "SPK_0", "v-1", "Ana", 0.9), Trecho(6.0, 12.5, "…", None, None, None)],
    )


def test_feed_enfileira_uma_vez_e_o_cursor_so_anda(armazem: Armazem) -> None:
    assert armazem.cursor() == 0
    assert armazem.registrar_feed([novo("a"), novo("b")], 5) == 2
    assert armazem.registrar_feed([novo("a")], 3) == 0, "chave repetida não duplica"
    assert armazem.cursor() == 5, "o cursor nunca volta"


def test_fila_reserva_em_ordem_adia_e_desiste(armazem: Armazem) -> None:
    armazem.registrar_feed([novo("a"), novo("b")], 2)
    t1 = armazem.proximo(AGORA)
    t2 = armazem.proximo(AGORA)
    assert t1 is not None and t2 is not None and t1.id < t2.id
    assert armazem.proximo(AGORA) is None, "os dois estão em curso"
    armazem.adiar(t1.id, "infraestrutura: fora", AGORA + timedelta(minutes=5))
    assert armazem.proximo(AGORA) is None, "adiado: só depois da hora"
    t1b = armazem.proximo(AGORA + timedelta(minutes=6))
    assert t1b is not None and t1b.id == t1.id and t1b.tentativas == 1
    armazem.desistir(t2.id, "entrada: ilegível", [novo("aviso-b", "notificar")])
    estados = {t["chave"]: (t["estado"], t["tentativas"]) for t in armazem.trabalhos()}
    assert estados["b"] == ("falhou", 1) and estados["aviso-b"] == ("pendente", 0)


def test_concluir_transcricao_versiona_por_segmento_e_enfileira_o_aviso(armazem: Armazem) -> None:
    seg = "20000000-0000-0000-0000-000000000002"
    armazem.registrar_feed([novo("t1"), novo("t2")], 2)
    a, b = armazem.proximo(AGORA), armazem.proximo(AGORA)
    assert a is not None and b is not None
    g1 = armazem.concluir_transcricao(a.id, nova(seg), lambda g: novo(f"aviso-{g.id}", "notificar"))
    g2 = armazem.concluir_transcricao(b.id, nova(seg), lambda g: novo(f"aviso-{g.id}", "notificar"))
    assert (g1.versao, g2.versao) == (1, 2), "transcrever de novo = versão nova"
    lida = armazem.transcricao(g1.id)
    assert lida is not None and lida.trechos == nova(seg).trechos and lida.cobertura == 0.5
    tipos = sorted((t["tipo"], t["estado"]) for t in armazem.trabalhos())
    assert tipos == [("notificar", "pendente")] * 2 + [("transcrever", "concluido")] * 2
    assert armazem.transcricao("nao-e-uuid") is None
    assert armazem.transcricao("00000000-0000-0000-0000-000000000000") is None


def test_descartado_nao_volta(armazem: Armazem) -> None:
    armazem.registrar_feed([novo("s")], 1)
    t = armazem.proximo(AGORA)
    assert t is not None
    armazem.concluir(t.id, estado="descartado")
    assert armazem.proximo(AGORA + timedelta(days=1)) is None
    assert armazem.trabalhos()[0]["estado"] == "descartado"


def test_reabrir_nao_reaplica_migracao_e_preserva_os_dados() -> None:
    if not URL:
        pytest.skip("OPLENARIO_IA_DATABASE_URL_TESTE não definida")
    import psycopg

    from oplenario_ia.armazem.postgres import ArmazemPostgres

    with psycopg.connect(URL, autocommit=True) as c:
        c.execute("DROP SCHEMA IF EXISTS ia CASCADE")
    ArmazemPostgres(URL).registrar_feed([novo("persistente")], 9)
    reaberto = ArmazemPostgres(URL)  # o segundo processo (ou o reinício) sobe sobre o schema existente
    assert reaberto.cursor() == 9
    assert [t["chave"] for t in reaberto.trabalhos()] == ["persistente"]
