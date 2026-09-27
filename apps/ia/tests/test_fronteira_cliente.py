"""O cliente do core (ADR-0008): categorias de falha do §22.3.5, sigilo, integridade do download."""

import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest

from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.fronteira.cliente import ClienteCore, Sigiloso
from oplenario_ia.fronteira.contrato import EventoParaCore


def cliente(tratador: object) -> ClienteCore:
    return ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(tratador)))  # type: ignore[arg-type]


def test_feed_leva_o_segredo_e_o_cursor() -> None:
    vistos: list[httpx.Request] = []

    def t(req: httpx.Request) -> httpx.Response:
        vistos.append(req)
        return httpx.Response(
            200,
            json={
                "eventos": [
                    {
                        "seq": 7,
                        "ente-id": "e",
                        "tipo": "GravacaoVinculada",
                        "versao": 1,
                        "chave": "k",
                        "payload": {},
                        "criado-em": "2026-09-26T20:00:00Z",
                    }
                ],
                "proximo": 7,
            },
        )

    f = cliente(t).feed(3)
    assert f.proximo == 7 and f.eventos[0].ente_id == "e"
    assert vistos[0].headers["authorization"] == "Bearer seg"
    assert vistos[0].url.params["depois"] == "3"


@pytest.mark.parametrize(
    ("status", "categoria", "retentavel"),
    [
        (500, Categoria.INFRAESTRUTURA, True),
        (429, Categoria.SOBRECARGA, True),
        (401, Categoria.INFRAESTRUTURA, False),
        (503, Categoria.INFRAESTRUTURA, True),
        (404, Categoria.ENTRADA, False),
        (422, Categoria.ENTRADA, False),
    ],
)
def test_falhas_do_core_categorizadas(status: int, categoria: Categoria, retentavel: bool) -> None:
    c = cliente(lambda req: httpx.Response(status, json={"erro": "motivo"}))
    with pytest.raises(ErroIA) as e:
        c.contexto("/x")
    assert (e.value.categoria, e.value.retentavel) == (categoria, retentavel)
    assert "motivo" in e.value.detalhe


def test_403_e_sigilo_nao_falha() -> None:
    with pytest.raises(Sigiloso):
        cliente(lambda req: httpx.Response(403, json={"erro": "secreta"})).contexto("/x")


def test_core_inalcancavel_e_infra_retentavel() -> None:
    def cai(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("recusado")

    with pytest.raises(ErroIA) as e:
        cliente(cai).feed(0)
    assert e.value.categoria is Categoria.INFRAESTRUTURA and e.value.retentavel


def test_download_confere_o_sha256(tmp_path: Path) -> None:
    corpo = b"audio" * 1000
    ok = hashlib.sha256(corpo).hexdigest()
    destino = tmp_path / "g"
    assert (
        cliente(lambda r: httpx.Response(200, content=corpo, headers={"X-Conteudo-Sha256": ok})).baixar("/c", destino)
        == ok
    )
    assert destino.read_bytes() == corpo
    with pytest.raises(ErroIA) as e:
        cliente(lambda r: httpx.Response(200, content=corpo, headers={"X-Conteudo-Sha256": "0" * 64})).baixar(
            "/c", destino
        )
    assert e.value.retentavel, "download corrompido: tenta de novo"
    with pytest.raises(Sigiloso):
        cliente(lambda r: httpx.Response(403)).baixar("/c", destino)


def test_evento_vai_em_kebab_case() -> None:
    corpos: list[dict[str, object]] = []

    def t(req: httpx.Request) -> httpx.Response:
        corpos.append(json.loads(req.content))
        return httpx.Response(201, json={"chave": "k", "aplicado": True})

    r = cliente(t).enviar(
        EventoParaCore(
            tipo="TranscricaoFalhou",
            chave="k",
            ente_id="e",
            correlation_id="c",
            ocorrido_em=datetime(2026, 9, 26, tzinfo=UTC),
            payload={"sessao-id": "s"},
        )
    )
    assert r.aplicado
    assert set(corpos[0]) == {"tipo", "versao", "chave", "ente-id", "correlation-id", "ocorrido-em", "payload"}
