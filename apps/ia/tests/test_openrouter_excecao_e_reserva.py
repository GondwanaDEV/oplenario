"""A exceção gratuita à ADR-0023 e a reserva de modelos (failover nosso) — transporte mock, sem rede, sem chave real.

A exceção é TEMPORÁRIA, por decisão do dono do produto (05/10/2026): modelos `:free` de lista própria, sem ZDR e sem
"sem coleta", desligada por padrão. A reserva troca de modelo só em erro retentável ou 404, uma vez cada, na ordem.
"""

import json
import logging
from collections.abc import Callable
from typing import Any

import httpx
import pytest

from oplenario_ia import config as config_mod
from oplenario_ia.avaliacao import cli
from oplenario_ia.config import (
    AVISO_EXCECAO_GRATUITA,
    MODELOS_OPENROUTER_GRATUITOS_EXCECAO,
    MODELOS_OPENROUTER_PERMITIDOS,
    Config,
    carregar,
    folga_de_raciocinio,
    modelo_openrouter_fixo,
)
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.inferencia.modelo import PedidoInferencia
from oplenario_ia.inferencia.openrouter_adapter import Politica, PortaOpenRouter

# um slug só da lista da exceção
SO_DA_EXCECAO = "teste/gratuito-sem-zdr:free"


@pytest.fixture(autouse=True)
def _aviso_zerado(monkeypatch: pytest.MonkeyPatch) -> None:
    # o aviso é "uma vez por processo": cada teste começa com ele ainda não emitido
    monkeypatch.setattr(config_mod, "_excecao_avisada", False)


@pytest.fixture
def lista_da_excecao(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setitem(MODELOS_OPENROUTER_GRATUITOS_EXCECAO, SO_DA_EXCECAO, 3000)


def pedido(**over: Any) -> PedidoInferencia:
    base: dict[str, Any] = {
        "ente_id": "e1",
        "correlation_id": "c1",
        "operacao": "resumo",
        "instrucoes": "Resuma.",
        "conteudo": ["texto público"],
        "max_tokens": 1000,
    }
    return PedidoInferencia(**{**base, **over})


def conclusao(modelo: str, provedor: str = "Prov") -> dict[str, Any]:
    return {
        "id": "gen-1",
        "provider": provedor,
        "model": modelo,
        "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "Olá"}}],
        "usage": {"prompt_tokens": 5, "completion_tokens": 1, "cost": 0},
    }


def porta(
    responder: Callable[[str], httpx.Response | Exception],
    visto: list[dict[str, Any]],
    *,
    modelo: str = "a/principal",
    reservas: list[tuple[str, int]] | None = None,
    politica: Politica = "zdr",
    provedores: list[str] | None = None,
) -> PortaOpenRouter:
    def tratar(req: httpx.Request) -> httpx.Response:
        corpo = json.loads(req.content)
        visto.append(corpo)
        r = responder(corpo["model"])
        if isinstance(r, Exception):
            raise r
        return r

    return PortaOpenRouter(
        modelo,
        timeout_s=5,
        chave="sk-or-teste",
        provedores=provedores,
        folga_raciocinio=100,
        politica=politica,
        reservas=reservas or [],
        cliente=httpx.Client(transport=httpx.MockTransport(tratar)),
    )


# ---------- corpo do pedido nas duas políticas ----------


def test_politica_zdr_e_o_padrao_e_trava_zdr_e_sem_coleta() -> None:
    visto: list[dict[str, Any]] = []
    porta(lambda m: httpx.Response(200, json=conclusao(m)), visto).gerar(pedido())
    assert visto[0]["provider"] == {"data_collection": "deny", "zdr": True, "require_parameters": True}
    assert Config().openrouter_politica == "zdr", "a exceção vem desligada"


def test_excecao_gratuita_leva_so_require_parameters_e_only() -> None:
    visto: list[dict[str, Any]] = []

    def responder(m: str) -> httpx.Response:
        return httpx.Response(200, json=conclusao(m))

    porta(responder, visto, politica="excecao-gratuita").gerar(pedido())
    porta(responder, visto, politica="excecao-gratuita", provedores=["chutes"]).gerar(pedido())
    assert visto[0]["provider"] == {"require_parameters": True}
    assert visto[1]["provider"] == {"require_parameters": True, "only": ["chutes"]}
    for corpo in visto:
        assert "zdr" not in corpo["provider"] and "data_collection" not in corpo["provider"]
        assert "models" not in corpo, "um modelo por requisição, mesmo na exceção"


# ---------- subida (config) ----------


def test_lista_da_excecao_so_tem_slug_fixo_gratuito_com_folga() -> None:
    assert MODELOS_OPENROUTER_GRATUITOS_EXCECAO, "a lista não pode ficar vazia"
    for slug, folga in MODELOS_OPENROUTER_GRATUITOS_EXCECAO.items():
        assert slug.endswith(":free") and modelo_openrouter_fixo(slug), slug
        assert folga >= 0


def test_sobe_com_a_excecao_e_modelo_da_lista(lista_da_excecao: None) -> None:
    c = carregar(
        {
            "OPLENARIO_IA_VENDOR": "openrouter",
            "OPLENARIO_IA_OPENROUTER_POLITICA": "excecao-gratuita",
            "OPLENARIO_IA_MODELO": SO_DA_EXCECAO,
        }
    )
    assert (c.openrouter_politica, c.modelo) == ("excecao-gratuita", SO_DA_EXCECAO)
    assert folga_de_raciocinio(SO_DA_EXCECAO, "excecao-gratuita") == 3000
    assert folga_de_raciocinio(SO_DA_EXCECAO) == 0, "na política zdr ele não é da lista"


def test_sem_modelo_a_excecao_usa_o_primeiro_da_lista_dela() -> None:
    c = carregar({"OPLENARIO_IA_VENDOR": "openrouter", "OPLENARIO_IA_OPENROUTER_POLITICA": "excecao-gratuita"})
    assert c.modelo == next(iter(MODELOS_OPENROUTER_GRATUITOS_EXCECAO))


def test_modelo_da_excecao_nao_sobe_com_a_politica_zdr(lista_da_excecao: None) -> None:
    assert SO_DA_EXCECAO not in MODELOS_OPENROUTER_PERMITIDOS
    with pytest.raises(ValueError, match="lista de modelos permitidos"):
        carregar({"OPLENARIO_IA_VENDOR": "openrouter", "OPLENARIO_IA_MODELO": SO_DA_EXCECAO})
    with pytest.raises(ValueError, match="lista de modelos permitidos"):
        carregar(
            {
                "OPLENARIO_IA_VENDOR": "openrouter",
                "OPLENARIO_IA_OPENROUTER_POLITICA": "zdr",
                "OPLENARIO_IA_MODELO": SO_DA_EXCECAO,
            }
        )


def test_modelo_pago_nao_sobe_com_a_excecao_nem_na_avaliacao() -> None:
    with pytest.raises(ValueError, match=r"só aceita modelo gratuito"):
        carregar(
            {
                "OPLENARIO_IA_VENDOR": "openrouter",
                "OPLENARIO_IA_OPENROUTER_POLITICA": "excecao-gratuita",
                "OPLENARIO_IA_MODELO": "openai/gpt-oss-120b",
            }
        )
    with pytest.raises(ValueError, match=r"só aceita modelo gratuito"):
        Config(vendor="openrouter", openrouter_politica="excecao-gratuita", modelo="openai/gpt-oss-120b")
    # a avaliação monta a Config direto: qualquer slug fixo `:free`, fora da lista, vale
    assert Config(vendor="openrouter", openrouter_politica="excecao-gratuita", modelo="x/y:free").modelo == "x/y:free"


def test_politica_desconhecida_e_recusada() -> None:
    with pytest.raises(ValueError):
        carregar({"OPLENARIO_IA_VENDOR": "openrouter", "OPLENARIO_IA_OPENROUTER_POLITICA": "tanto-faz"})


def test_reservas_seguem_a_regra_do_principal_na_politica_vigente(lista_da_excecao: None) -> None:
    c = carregar(
        {
            "OPLENARIO_IA_VENDOR": "openrouter",
            "OPLENARIO_IA_OPENROUTER_POLITICA": "excecao-gratuita",
            "OPLENARIO_IA_MODELO": "nvidia/nemotron-3-super-120b-a12b:free",
            "OPLENARIO_IA_MODELOS_RESERVA": " nvidia/nemotron-3-ultra-550b-a55b:free , ",
        }
    )
    assert c.modelos_reserva == ["nvidia/nemotron-3-ultra-550b-a55b:free"]
    with pytest.raises(ValueError, match="MODELOS_RESERVA fora"):
        carregar(
            {
                "OPLENARIO_IA_VENDOR": "openrouter",
                "OPLENARIO_IA_MODELOS_RESERVA": SO_DA_EXCECAO,
            }
        )
    with pytest.raises(ValueError, match="só aceita modelo gratuito"):
        carregar(
            {
                "OPLENARIO_IA_VENDOR": "openrouter",
                "OPLENARIO_IA_OPENROUTER_POLITICA": "excecao-gratuita",
                "OPLENARIO_IA_MODELO": SO_DA_EXCECAO,
                "OPLENARIO_IA_MODELOS_RESERVA": "openai/gpt-oss-120b",
            }
        )
    with pytest.raises(ValueError, match="autor/modelo"):
        Config(vendor="openrouter", modelos_reserva=["openrouter/auto"])
    with pytest.raises(ValueError, match="repetido"):
        Config(vendor="openrouter", modelo="a/b", modelos_reserva=["c/d", "a/b"])


# ---------- aviso ----------


def test_subida_com_a_excecao_avisa_uma_vez_por_processo(
    lista_da_excecao: None, caplog: pytest.LogCaptureFixture, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-x")
    env = {
        "OPLENARIO_IA_VENDOR": "openrouter",
        "OPLENARIO_IA_OPENROUTER_POLITICA": "excecao-gratuita",
        "OPLENARIO_IA_MODELO": SO_DA_EXCECAO,
    }
    with caplog.at_level(logging.WARNING, logger="oplenario_ia.config"):
        cfg = carregar(env)
        carregar(env)
        criar_porta(cfg)
    avisos = [r for r in caplog.records if r.levelno == logging.WARNING and r.name == "oplenario_ia.config"]
    assert [r.getMessage() for r in avisos] == [AVISO_EXCECAO_GRATUITA]
    assert "ADR-0023" in AVISO_EXCECAO_GRATUITA and "GUARDAR" in AVISO_EXCECAO_GRATUITA


def test_politica_zdr_nao_avisa(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.WARNING):
        criar_porta(carregar({"OPLENARIO_IA_VENDOR": "openrouter"}))
    assert not [r for r in caplog.records if r.name == "oplenario_ia.config"]


def test_fabrica_passa_politica_e_reservas_com_a_folga_de_cada_um(
    lista_da_excecao: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-x")
    cfg = Config(
        vendor="openrouter",
        openrouter_politica="excecao-gratuita",
        modelo=SO_DA_EXCECAO,
        modelos_reserva=["nvidia/nemotron-3-ultra-550b-a55b:free"],
    )
    p = criar_porta(cfg)
    assert isinstance(p, PortaOpenRouter)
    assert p.modelos == [SO_DA_EXCECAO, "nvidia/nemotron-3-ultra-550b-a55b:free"]
    visto: list[dict[str, Any]] = []

    def tratar(req: httpx.Request) -> httpx.Response:
        corpo = json.loads(req.content)
        visto.append(corpo)
        return httpx.Response(429 if corpo["model"] == SO_DA_EXCECAO else 200, json=conclusao(corpo["model"]))

    p._cliente = httpx.Client(transport=httpx.MockTransport(tratar))
    p.gerar(pedido(max_tokens=1000))
    assert [(c["model"], c["max_tokens"]) for c in visto] == [
        (SO_DA_EXCECAO, 4000),
        ("nvidia/nemotron-3-ultra-550b-a55b:free", 5000),
    ]
    assert all(c["provider"] == {"require_parameters": True} for c in visto)


# ---------- failover ----------

RESERVAS = [("b/reserva-1", 200), ("c/reserva-2", 300)]


@pytest.mark.parametrize("status", [429, 503, 404, 408, 500, 502])
def test_reserva_entra_em_erro_retentavel_ou_404_e_o_registrado_e_quem_atendeu(status: int) -> None:
    visto: list[dict[str, Any]] = []

    def responder(modelo: str) -> httpx.Response:
        if modelo == "a/principal":
            return httpx.Response(status, json={"error": {"code": status, "message": "m"}})
        return httpx.Response(200, json=conclusao(modelo, "ProvReserva"))

    r = porta(responder, visto, reservas=RESERVAS).gerar(pedido())
    assert [(c["model"], c["max_tokens"]) for c in visto] == [("a/principal", 1100), ("b/reserva-1", 1200)]
    assert (r.modelo, r.provedor) == ("b/reserva-1", "ProvReserva"), "a proveniência é a do modelo que atendeu"


def test_reservas_em_ordem_uma_vez_cada_e_o_erro_final_e_o_do_ultimo() -> None:
    visto: list[dict[str, Any]] = []
    status = {"a/principal": 429, "b/reserva-1": 503, "c/reserva-2": 404}
    with pytest.raises(ErroIA) as e:
        porta(lambda m: httpx.Response(status[m], json={}), visto, reservas=RESERVAS).gerar(pedido())
    assert [c["model"] for c in visto] == ["a/principal", "b/reserva-1", "c/reserva-2"]
    assert (e.value.categoria, e.value.detalhe) == (Categoria.INFRAESTRUTURA, "configuração do fornecedor (404)")


def test_falha_de_rede_e_erro_no_corpo_tambem_abrem_a_reserva() -> None:
    visto: list[dict[str, Any]] = []
    respostas: dict[str, httpx.Response | Exception] = {
        "a/principal": httpx.ConnectError("x"),
        "b/reserva-1": httpx.Response(200, json={"error": {"code": 404, "message": "sem provedor"}}),
        "c/reserva-2": httpx.Response(200, json=conclusao("c/reserva-2")),
    }
    r = porta(lambda m: respostas[m], visto, reservas=RESERVAS).gerar(pedido())
    assert [c["model"] for c in visto] == ["a/principal", "b/reserva-1", "c/reserva-2"]
    assert r.modelo == "c/reserva-2"


def test_timeout_abre_a_reserva() -> None:
    visto: list[dict[str, Any]] = []
    respostas: dict[str, httpx.Response | Exception] = {
        "a/principal": httpx.ReadTimeout("x"),
        "b/reserva-1": httpx.Response(200, json=conclusao("b/reserva-1")),
    }
    assert porta(lambda m: respostas[m], visto, reservas=RESERVAS).gerar(pedido()).modelo == "b/reserva-1"


@pytest.mark.parametrize("status", [400, 401, 402, 403, 422])
def test_sem_reserva_em_erro_de_entrada_ou_de_conta(status: int) -> None:
    visto: list[dict[str, Any]] = []
    with pytest.raises(ErroIA) as e:
        porta(lambda m: httpx.Response(status, json={}), visto, reservas=RESERVAS).gerar(pedido())
    assert [c["model"] for c in visto] == ["a/principal"]
    assert e.value.retentavel is False


def test_troca_de_modelo_vai_ao_log_sem_conteudo(caplog: pytest.LogCaptureFixture) -> None:
    visto: list[dict[str, Any]] = []
    with caplog.at_level(logging.WARNING, logger="oplenario_ia.inferencia.openrouter"):
        porta(
            lambda m: httpx.Response(429, json={}) if m == "a/principal" else httpx.Response(200, json=conclusao(m)),
            visto,
            reservas=RESERVAS,
        ).gerar(pedido(conteudo=["segredo-de-teste"]))
    [registro] = [r for r in caplog.records if r.name == "oplenario_ia.inferencia.openrouter"]
    assert "a/principal" in registro.getMessage() and "b/reserva-1" in registro.getMessage()
    assert "segredo-de-teste" not in registro.getMessage()


def test_sem_reservas_uma_tentativa_so() -> None:
    visto: list[dict[str, Any]] = []
    with pytest.raises(ErroIA):
        porta(lambda m: httpx.Response(429, json={}), visto).gerar(pedido())
    assert len(visto) == 1


# ---------- avaliação ----------


class _Parou(Exception):
    pass


def _capturar_config(monkeypatch: pytest.MonkeyPatch) -> list[Config]:
    vistos: list[Config] = []

    def criar(cfg: Config) -> Any:
        vistos.append(cfg)
        raise _Parou  # nunca monta a porta de verdade: nada sai para a rede

    monkeypatch.setattr(cli, "criar_porta", criar)
    return vistos


def test_cli_aceita_politica_e_passa_para_a_porta(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    vistos = _capturar_config(monkeypatch)
    with pytest.raises(_Parou):
        cli.main(["avaliacoes", "--vendor", "openrouter", "--politica", "excecao-gratuita", "--modelo", "x/y:free"])
    assert (vistos[0].openrouter_politica, vistos[0].modelo) == ("excecao-gratuita", "x/y:free")
    assert AVISO_EXCECAO_GRATUITA in capsys.readouterr().err


def test_cli_politica_padrao_e_zdr_e_nao_avisa(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    vistos = _capturar_config(monkeypatch)
    with pytest.raises(_Parou):
        cli.main(["avaliacoes", "--vendor", "openrouter"])
    assert vistos[0].openrouter_politica == "zdr"
    assert AVISO_EXCECAO_GRATUITA not in capsys.readouterr().err


def test_cli_recusa_modelo_pago_na_excecao_e_politica_desconhecida(monkeypatch: pytest.MonkeyPatch) -> None:
    vistos = _capturar_config(monkeypatch)
    argv = ["avaliacoes", "--vendor", "openrouter", "--politica", "excecao-gratuita", "--modelo", "openai/gpt-oss-120b"]
    assert cli.main(argv) == 2
    assert vistos == []
    with pytest.raises(SystemExit):
        cli.main(["avaliacoes", "--politica", "tanto-faz"])
