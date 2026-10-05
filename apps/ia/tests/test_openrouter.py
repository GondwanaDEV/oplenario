"""O adaptador do OpenRouter (ADR-0023) contra transporte mock — sem rede, sem chave real."""

import json
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import httpx
import pytest

from oplenario_ia.avaliacao.custo import calcular, tabela_padrao
from oplenario_ia.confianca.registro import RegistroExecucao, RegistroMemoria
from oplenario_ia.config import Config, carregar
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Fonte, Peca, Proveniencia, Sigilo
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.inferencia.modelo import PedidoInferencia, Uso
from oplenario_ia.inferencia.openrouter_adapter import PortaOpenRouter
from oplenario_ia.nucleo import Nucleo


def pedido(**over: Any) -> PedidoInferencia:
    base: dict[str, Any] = {
        "ente_id": "e1",
        "correlation_id": "c1",
        "operacao": "resumo",
        "instrucoes": "Resuma.",
        "conteudo": ["texto público"],
    }
    return PedidoInferencia(**{**base, **over})


def conclusao(
    finish_reason: str = "stop",
    texto: str | None = "Olá",
    *,
    nativo: str | None = "end_turn",
    custo: float | None = 0.0042,
    provedor: str = "Groq",
) -> dict[str, Any]:
    usage: dict[str, Any] = {
        "prompt_tokens": 20,
        "completion_tokens": 3,
        "total_tokens": 23,
        "prompt_tokens_details": {"cached_tokens": 4},
    }
    if custo is not None:
        usage["cost"] = custo
    return {
        "id": "gen-1",
        "provider": provedor,
        "model": "openai/gpt-oss-120b",
        "object": "chat.completion",
        "choices": [
            {
                "index": 0,
                "finish_reason": finish_reason,
                "native_finish_reason": nativo,
                "message": {"role": "assistant", "content": texto},
            }
        ],
        "usage": usage,
    }


def porta_com(
    resposta: httpx.Response | Exception,
    visto: list[httpx.Request] | None = None,
    *,
    chave: str | None = "sk-or-teste",
    provedores: list[str] | None = None,
) -> PortaOpenRouter:
    def tratar(req: httpx.Request) -> httpx.Response:
        if visto is not None:
            visto.append(req)
        if isinstance(resposta, Exception):
            raise resposta
        return resposta

    return PortaOpenRouter(
        "openai/gpt-oss-120b",
        timeout_s=5,
        chave=chave,
        provedores=provedores,
        cliente=httpx.Client(transport=httpx.MockTransport(tratar)),
    )


# ---------- pedido ----------


def test_pedido_vai_no_formato_de_chat_com_a_politica_de_dado_travada() -> None:
    visto: list[httpx.Request] = []
    porta_com(httpx.Response(200, json=conclusao()), visto, provedores=["groq", "cerebras"]).gerar(
        pedido(max_tokens=500)
    )
    [req] = visto
    assert str(req.url) == "https://openrouter.ai/api/v1/chat/completions"
    assert req.headers["authorization"] == "Bearer sk-or-teste"
    corpo = json.loads(req.content)
    assert corpo["model"] == "openai/gpt-oss-120b"
    assert corpo["max_tokens"] == 500
    assert corpo["messages"] == [
        {"role": "system", "content": "Resuma."},
        {"role": "user", "content": [{"type": "text", "text": "texto público"}]},
    ]
    assert corpo["provider"] == {
        "data_collection": "deny",
        "zdr": True,
        "require_parameters": True,
        "only": ["groq", "cerebras"],
    }
    assert "models" not in corpo, "sem fallback de modelo: nunca troca de modelo em silêncio"
    assert "reasoning" not in corpo


def test_sem_lista_de_provedores_a_politica_de_dado_continua() -> None:
    visto: list[httpx.Request] = []
    porta_com(httpx.Response(200, json=conclusao()), visto).gerar(pedido(esforco="low"))
    corpo = json.loads(visto[0].content)
    assert corpo["provider"] == {"data_collection": "deny", "zdr": True, "require_parameters": True}
    assert corpo["reasoning"] == {"effort": "low"}


# ---------- resposta ----------


def test_normaliza_texto_uso_custo_modelo_e_provedor_efetivos() -> None:
    r = porta_com(httpx.Response(200, json=conclusao())).gerar(pedido())
    assert (r.texto, r.parada, r.vendor, r.modelo, r.provedor) == (
        "Olá",
        "fim",
        "openrouter",
        "openai/gpt-oss-120b",
        "Groq",
    )
    # prompt_tokens inclui o cache no formato de chat; o Uso o separa
    assert (r.uso.entrada, r.uso.saida, r.uso.cache_leitura) == (16, 3, 4)
    assert r.custo_informado == Decimal("0.0042")
    assert r.id_requisicao == "gen-1"


def test_conteudo_em_partes_e_juntado() -> None:
    corpo = conclusao()
    corpo["choices"][0]["message"]["content"] = [{"type": "text", "text": "Olá, "}, {"type": "text", "text": "mundo"}]
    assert porta_com(httpx.Response(200, json=corpo)).gerar(pedido()).texto == "Olá, mundo"


def test_recusa_e_limite_viram_parada_explicita() -> None:
    assert porta_com(httpx.Response(200, json=conclusao("length"))).gerar(pedido()).parada == "limite_tokens"
    recusa = conclusao("stop", None, nativo="refusal")
    assert porta_com(httpx.Response(200, json=recusa)).gerar(pedido()).parada == "recusa"
    filtro = conclusao("content_filter", None, nativo=None)
    assert porta_com(httpx.Response(200, json=filtro)).gerar(pedido()).parada == "recusa"


def test_resposta_sem_texto_e_falha_de_modelo() -> None:
    with pytest.raises(ErroIA) as e:
        porta_com(httpx.Response(200, json=conclusao(texto=None))).gerar(pedido())
    assert (e.value.categoria, e.value.retentavel) == (Categoria.MODELO, True)


def test_erro_no_corpo_de_um_200_vira_categoria() -> None:
    corpo = {"error": {"code": 502, "message": "upstream caiu — pode ecoar o pedido"}}
    with pytest.raises(ErroIA) as e:
        porta_com(httpx.Response(200, json=corpo)).gerar(pedido())
    assert (e.value.categoria, e.value.retentavel) == (Categoria.INFRAESTRUTURA, True)
    assert "ecoar" not in e.value.detalhe, "o detalhe nunca leva a mensagem do provedor"


def test_finish_reason_error_e_falha_do_provedor() -> None:
    with pytest.raises(ErroIA) as e:
        porta_com(httpx.Response(200, json=conclusao("error"))).gerar(pedido())
    assert (e.value.categoria, e.value.retentavel) == (Categoria.INFRAESTRUTURA, True)


@pytest.mark.parametrize(
    ("status", "categoria", "retentavel"),
    [
        (429, Categoria.SOBRECARGA, True),
        (503, Categoria.SOBRECARGA, True),
        (502, Categoria.INFRAESTRUTURA, True),
        (500, Categoria.INFRAESTRUTURA, True),
        (408, Categoria.INFRAESTRUTURA, True),
        (400, Categoria.ENTRADA, False),
        (403, Categoria.ENTRADA, False),
        (422, Categoria.ENTRADA, False),
        (401, Categoria.INFRAESTRUTURA, False),
        (402, Categoria.INFRAESTRUTURA, False),
        (404, Categoria.INFRAESTRUTURA, False),
    ],
)
def test_status_vira_categoria(status: int, categoria: Categoria, retentavel: bool) -> None:
    with pytest.raises(ErroIA) as e:
        porta_com(httpx.Response(status, json={"error": {"code": status, "message": "m"}})).gerar(pedido())
    assert (e.value.categoria, e.value.retentavel, e.value.vendor) == (categoria, retentavel, "openrouter")


def test_rede_e_timeout_sao_infraestrutura_retentavel() -> None:
    for exc in (httpx.ConnectError("x"), httpx.ReadTimeout("x")):
        with pytest.raises(ErroIA) as e:
            porta_com(exc).gerar(pedido())
        assert (e.value.categoria, e.value.retentavel) == (Categoria.INFRAESTRUTURA, True)


def test_corpo_ilegivel_e_infraestrutura() -> None:
    with pytest.raises(ErroIA) as e:
        porta_com(httpx.Response(200, content=b"<html>")).gerar(pedido())
    assert e.value.categoria is Categoria.INFRAESTRUTURA


def test_nao_tenta_de_novo_por_conta_propria() -> None:
    visto: list[httpx.Request] = []
    with pytest.raises(ErroIA):
        porta_com(httpx.Response(500, json={}), visto).gerar(pedido())
    assert len(visto) == 1, "retry/failover são nossos (§22.3.5)"


def test_sem_chave_e_indisponivel_e_nao_chama_ninguem() -> None:
    visto: list[httpx.Request] = []
    with pytest.raises(ErroIA) as e:
        porta_com(httpx.Response(200, json=conclusao()), visto, chave=None).gerar(pedido())
    assert (e.value.categoria, e.value.retentavel) == (Categoria.INFRAESTRUTURA, False)
    assert visto == []


# ---------- config e fábrica ----------


def test_config_do_openrouter() -> None:
    c = carregar(
        {
            "OPLENARIO_IA_VENDOR": "openrouter",
            "OPLENARIO_IA_OPENROUTER_PROVEDORES": "groq, cerebras,",
        }
    )
    assert (c.vendor, c.modelo) == ("openrouter", "openai/gpt-oss-120b"), "sem modelo, o slug do OpenRouter"
    assert c.openrouter_provedores == ["groq", "cerebras"]
    assert (
        carregar({"OPLENARIO_IA_VENDOR": "openrouter", "OPLENARIO_IA_MODELO": "anthropic/claude-opus-5"}).modelo
        == "anthropic/claude-opus-5"
    )
    assert Config().modelo == "claude-opus-5", "o padrão dos outros fornecedores não muda"


@pytest.mark.parametrize(
    "modelo",
    ["openrouter/auto", "~anthropic/claude-opus-latest", "openai/gpt-oss-120b:online", "OpenAI/GPT-OSS", " openai/x"],
)
def test_modelo_que_troca_sozinho_e_recusado_na_config(modelo: str) -> None:
    with pytest.raises(ValueError, match="autor/modelo"):
        Config(vendor="openrouter", modelo=modelo)


def test_variante_fixa_do_modelo_e_aceita() -> None:
    assert Config(vendor="openrouter", modelo="openai/gpt-oss-20b:free").modelo == "openai/gpt-oss-20b:free"
    assert Config(vendor="anthropic", modelo="claude-opus-5").modelo == "claude-opus-5", "só vale no OpenRouter"


def test_fabrica_cria_a_porta_do_openrouter_com_a_chave_do_ambiente(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-x")
    porta = criar_porta(Config(vendor="openrouter"))
    assert isinstance(porta, PortaOpenRouter) and porta.vendor == "openrouter"


# ---------- custo ----------


def test_custo_informado_vale_com_a_taxa_da_plataforma() -> None:
    t = tabela_padrao()
    c = calcular(Uso(entrada=10), "openrouter", "openai/gpt-oss-120b", t, Decimal("0.01"))
    assert c.valor == Decimal("0.01") * Decimal("1.055")
    assert c.tabela == "informado por openrouter"


def test_sem_custo_informado_a_tabela_e_a_reserva_com_a_taxa() -> None:
    t = tabela_padrao()
    c = calcular(Uso(entrada=1_000_000), "openrouter", "anthropic/claude-opus-5", t)
    assert c.valor == Decimal("5.00") * Decimal("1.055")
    assert calcular(Uso(entrada=1), "openrouter", "openai/gpt-oss-120b", t).valor is None, "nunca zero em silêncio"


# ---------- de ponta a ponta pelo núcleo ----------


def test_nucleo_registra_provedor_e_custo_informado_sem_conteudo() -> None:
    porta = porta_com(httpx.Response(200, json=conclusao(texto="Resumo pronto.")))
    reg = RegistroMemoria()
    n = Nucleo(porta, reg, agora=lambda: datetime(2026, 10, 5, tzinfo=UTC), novo_id=lambda: "x1")
    peca = Peca(
        texto="segredo-de-teste público",
        proveniencia=Proveniencia(origem="core.proposicao", sigilo=Sigilo.PUBLICO),
        fonte=Fonte(id="urn:x", rotulo="Matéria"),
    )
    n.executar(
        PedidoGovernado(ente_id="e1", correlation_id="c1", operacao="resumo", instrucoes="Resuma.", pecas=[peca])
    )
    [e] = [x for x in reg.eventos() if isinstance(x, RegistroExecucao)]
    assert (e.vendor, e.modelo, e.provedor) == ("openrouter", "openai/gpt-oss-120b", "Groq")
    assert e.custo is not None and e.custo.valor == Decimal("0.0042") * Decimal("1.055")
    assert "segredo-de-teste" not in e.model_dump_json()
