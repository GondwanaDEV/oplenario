"""ADR-0018 (Eixo 4.5): `DELETE /v1/entes/{ente}` — o core, depois de conferir no banco dele as salvaguardas do
apagamento, pede ao satélite que apague tudo da Casa encerrada. Só a Casa pedida; o mesmo segredo de serviço das
outras rotas; sem armazenamento, 503 (o core marca a IA como pendente e retoma — nunca um "apagado" fingido)."""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.armazem.porta import NovoTrabalho
from oplenario_ia.confianca.cota import Orcamento
from oplenario_ia.confianca.registro import RegistroJsonl, RegistroMemoria, RevisaoHumana
from oplenario_ia.config import Config

A = "10000000-0000-0000-0000-00000000000a"
B = "10000000-0000-0000-0000-00000000000b"
AGORA = datetime(2026, 10, 2, 12, 0, tzinfo=UTC)
AUTH = {"Authorization": "Bearer s3gr3d0"}


def _revisao(ente: str) -> RevisaoHumana:
    return RevisaoHumana(
        execucao_id="x",
        instante=AGORA,
        ente_id=ente,
        operacao="ata",
        revisor="r",
        desfecho="editado",
        proporcao_alterada=0.1,
    )


def _casas() -> tuple[ArmazemMemoria, RegistroMemoria]:
    arm = ArmazemMemoria()
    reg = RegistroMemoria()
    for ente in (A, B):
        arm.registrar_feed([NovoTrabalho("transcrever", f"seg-{ente}", ente, {})], 1)
        arm.definir_orcamento(ente, Orcamento(mensal=Decimal(10), teto_duro=Decimal(20), moeda="BRL"), AGORA, ente)
        reg.anexar(_revisao(ente))
    return arm, reg


def test_apaga_so_a_casa_pedida_e_devolve_as_contagens() -> None:
    arm, reg = _casas()
    c = TestClient(criar_app(Config(segredo="s3gr3d0"), arm, registro=reg))
    r = c.delete(f"/v1/entes/{A}", headers=AUTH)
    assert r.status_code == 200
    corpo = r.json()
    assert corpo["ente_id"] == A
    assert corpo["apagados"]["ia.trabalho"] == 1
    assert corpo["apagados"]["ia.orcamento"] == 1
    assert corpo["apagados"]["ia.registro_evento"] == 1
    assert corpo["total"] == sum(corpo["apagados"].values())
    assert arm.orcamento(A) is None and arm.orcamento(B) is not None
    assert [t["chave"] for t in arm.trabalhos()] == [f"seg-{B}"]
    assert [e.ente_id for e in reg.eventos()] == [B]
    de_novo = c.delete(f"/v1/entes/{A}", headers=AUTH).json()
    assert de_novo["total"] == 0, "idempotente: a retomada do core nao falha"


def test_exige_o_segredo_de_servico() -> None:
    arm, reg = _casas()
    c = TestClient(criar_app(Config(segredo="s3gr3d0"), arm, registro=reg))
    assert c.delete(f"/v1/entes/{A}").status_code == 401
    assert c.delete(f"/v1/entes/{A}", headers={"Authorization": "Bearer outro"}).status_code == 401
    assert TestClient(criar_app(Config(segredo=""), arm, registro=reg)).delete(f"/v1/entes/{A}").status_code == 503
    assert arm.orcamento(A) is not None, "nada apagado sem a credencial"


def test_ente_invalido_e_armazem_ausente() -> None:
    arm, reg = _casas()
    c = TestClient(criar_app(Config(segredo="s3gr3d0"), arm, registro=reg))
    assert c.delete("/v1/entes/nao-e-uuid", headers=AUTH).status_code == 422
    sem = TestClient(criar_app(Config(segredo="s3gr3d0"), None, registro=reg))
    assert sem.delete(f"/v1/entes/{A}", headers=AUTH).status_code == 503, "sem armazem: pendente, nunca 'apagado'"


def test_registro_jsonl_reescreve_sem_a_casa(tmp_path: Path) -> None:
    reg = RegistroJsonl(tmp_path / "registro.jsonl")
    for ente in (A, B, A):
        reg.anexar(_revisao(ente))
    assert reg.apagar_ente(A) == 2
    assert [e.ente_id for e in reg.eventos()] == [B]
    assert reg.apagar_ente(A) == 0
