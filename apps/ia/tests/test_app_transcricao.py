"""A leitura da transcrição pelo core (ADR-0008): segredo de serviço, tenant conferido, 503 sem configuração."""

from datetime import UTC, datetime

from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.armazem.porta import NovaTranscricao, NovoTrabalho
from oplenario_ia.config import Config
from oplenario_ia.transcricao.modelo import Trecho

ENTE = "10000000-0000-0000-0000-000000000001"


def com_uma_transcricao() -> tuple[ArmazemMemoria, str]:
    arm = ArmazemMemoria()
    arm.registrar_feed([NovoTrabalho("transcrever", "k", ENTE, {})], 1)
    t = arm.proximo(datetime.now(UTC))
    assert t is not None
    g = arm.concluir_transcricao(
        t.id,
        NovaTranscricao(
            ENTE,
            "s",
            "seg",
            "pt",
            10.0,
            "fake-asr-1",
            "fake-diarizacao-1",
            1.0,
            [Trecho(0.0, 10.0, "Declaro aberta a sessão.", "SPK_0", "v-pres", "Presidente Lúcia")],
        ),
        lambda g: NovoTrabalho("notificar", f"a-{g.id}", ENTE, {}),
    )
    return arm, g.id


def test_le_a_transcricao_com_o_segredo_e_o_tenant_certo() -> None:
    arm, tid = com_uma_transcricao()
    c = TestClient(criar_app(Config(segredo="s3gr3d0"), arm))
    r = c.get(f"/v1/entes/{ENTE}/transcricoes/{tid}", headers={"Authorization": "Bearer s3gr3d0"})
    assert r.status_code == 200
    b = r.json()
    assert b["trechos"][0]["orador-nome"] == "Presidente Lúcia" and b["versao"] == 1
    assert c.get(f"/v1/entes/outro/transcricoes/{tid}", headers={"Authorization": "Bearer s3gr3d0"}).status_code == 404
    assert c.get(f"/v1/entes/{ENTE}/transcricoes/{tid}", headers={"Authorization": "Bearer errado"}).status_code == 401
    assert c.get(f"/v1/entes/{ENTE}/transcricoes/{tid}").status_code == 401


def test_sem_segredo_ou_sem_armazenamento_503() -> None:
    arm, tid = com_uma_transcricao()
    assert TestClient(criar_app(Config(), arm)).get(f"/v1/entes/{ENTE}/transcricoes/{tid}").status_code == 503
    sem = TestClient(criar_app(Config(segredo="s"), None))
    assert sem.get(f"/v1/entes/{ENTE}/transcricoes/x", headers={"Authorization": "Bearer s"}).status_code == 503
