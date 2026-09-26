import json
from typing import Any

import anthropic
import httpx2
import pytest

from oplenario_ia.config import Config
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.inferencia.anthropic_adapter import PortaAnthropic
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.inferencia.modelo import PedidoInferencia


def pedido(**over: Any) -> PedidoInferencia:
    base: dict[str, Any] = {
        "ente_id": "e1",
        "correlation_id": "c1",
        "operacao": "resumo",
        "instrucoes": "Resuma.",
        "conteudo": ["texto público"],
    }
    return PedidoInferencia(**{**base, **over})


# ---------- fake ----------


def test_fake_guarda_o_que_recebeu_e_segue_o_roteiro() -> None:
    porta = PortaFake({"resumo": "RESUMO"})
    r = porta.gerar(pedido())
    assert r.texto == "RESUMO"
    assert (r.vendor, r.modelo, r.parada) == ("fake", "fake-1", "fim")
    assert porta.recebidos[0].conteudo == ["texto público"]


def test_fake_pode_simular_falha() -> None:
    porta = PortaFake({"resumo": ErroIA(Categoria.INFRAESTRUTURA, "fora", retentavel=True)})
    with pytest.raises(ErroIA):
        porta.gerar(pedido())


def test_pedido_sem_conteudo_nao_existe() -> None:
    with pytest.raises(ValueError):
        pedido(conteudo=[])


# ---------- adaptador Anthropic contra transporte mock (sem rede) ----------


def mensagem(stop_reason: str = "end_turn", texto: str | None = "Olá", modelo: str = "claude-opus-5") -> dict[str, Any]:
    content = [{"type": "text", "text": texto}] if texto is not None else []
    return {
        "id": "msg_1",
        "type": "message",
        "role": "assistant",
        "model": modelo,
        "content": content,
        "stop_reason": stop_reason,
        "stop_sequence": None,
        "usage": {
            "input_tokens": 12,
            "output_tokens": 3,
            "cache_read_input_tokens": 4,
            "cache_creation_input_tokens": 0,
        },
    }


def porta_com(resposta: httpx2.Response | Exception, visto: list[dict[str, Any]] | None = None) -> PortaAnthropic:
    def tratar(req: httpx2.Request) -> httpx2.Response:
        if visto is not None:
            visto.append({"headers": dict(req.headers), "corpo": json.loads(req.content)})
        if isinstance(resposta, Exception):
            raise resposta
        return resposta

    cliente = anthropic.Anthropic(
        api_key="teste",
        max_retries=0,
        http_client=anthropic.DefaultHttpxClient(transport=httpx2.MockTransport(tratar)),
    )
    return PortaAnthropic("claude-opus-5", timeout_s=5, cliente=cliente)


def test_anthropic_normaliza_texto_uso_e_modelo_efetivo() -> None:
    visto: list[dict[str, Any]] = []
    porta = porta_com(
        httpx2.Response(200, json=mensagem(modelo="claude-opus-5"), headers={"request-id": "req_1"}), visto
    )
    r = porta.gerar(pedido(esforco="low", max_tokens=500))
    assert (r.texto, r.parada, r.vendor, r.modelo) == ("Olá", "fim", "anthropic", "claude-opus-5")
    assert (r.uso.entrada, r.uso.saida, r.uso.cache_leitura) == (12, 3, 4)
    assert r.id_requisicao == "req_1"
    corpo = visto[0]["corpo"]
    assert corpo["system"] == "Resuma."
    assert corpo["max_tokens"] == 500
    assert corpo["output_config"] == {"effort": "low"}
    assert corpo["messages"] == [{"role": "user", "content": [{"type": "text", "text": "texto público"}]}]
    assert "fallbacks" not in corpo, "sem fallback server-side: nunca troca de modelo em silêncio"


def test_anthropic_recusa_e_limite_viram_parada_explicita() -> None:
    assert porta_com(httpx2.Response(200, json=mensagem("refusal", None))).gerar(pedido()).parada == "recusa"
    assert porta_com(httpx2.Response(200, json=mensagem("max_tokens"))).gerar(pedido()).parada == "limite_tokens"


def test_anthropic_resposta_sem_texto_e_falha_de_modelo() -> None:
    with pytest.raises(ErroIA) as e:
        porta_com(httpx2.Response(200, json=mensagem(texto=None))).gerar(pedido())
    assert e.value.categoria is Categoria.MODELO


def erro_api(status: int) -> httpx2.Response:
    return httpx2.Response(status, json={"type": "error", "error": {"type": "x", "message": "m"}})


@pytest.mark.parametrize(
    ("status", "categoria", "retentavel"),
    [
        (429, Categoria.SOBRECARGA, True),
        (529, Categoria.SOBRECARGA, True),
        (500, Categoria.INFRAESTRUTURA, True),
        (400, Categoria.ENTRADA, False),
        (422, Categoria.ENTRADA, False),
        (401, Categoria.INFRAESTRUTURA, False),
        (404, Categoria.INFRAESTRUTURA, False),
    ],
)
def test_anthropic_status_vira_categoria(status: int, categoria: Categoria, retentavel: bool) -> None:
    with pytest.raises(ErroIA) as e:
        porta_com(erro_api(status)).gerar(pedido())
    assert (e.value.categoria, e.value.retentavel, e.value.vendor) == (categoria, retentavel, "anthropic")


def test_anthropic_rede_e_timeout_sao_infraestrutura_retentavel() -> None:
    for exc in (httpx2.ConnectError("x"), httpx2.ReadTimeout("x")):
        with pytest.raises(ErroIA) as e:
            porta_com(exc).gerar(pedido())
        assert (e.value.categoria, e.value.retentavel) == (Categoria.INFRAESTRUTURA, True)


def test_anthropic_nao_tenta_de_novo_por_conta_propria() -> None:
    visto: list[dict[str, Any]] = []
    with pytest.raises(ErroIA):
        porta_com(erro_api(500), visto).gerar(pedido())
    assert len(visto) == 1, "retry/failover são nossos (§22.3.5), não do SDK"


# ---------- fábrica ----------


def test_fabrica_escolhe_por_config() -> None:
    assert criar_porta(Config()).vendor == "fake"
    assert criar_porta(Config(vendor="anthropic")).vendor == "anthropic"
