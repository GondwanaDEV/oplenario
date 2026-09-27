"""B.3 — o agente: laço planejar → ferramenta → responder, o cliente MCP e a rota de serviço."""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from oplenario_ia.agente import laco
from oplenario_ia.agente.mcp import ClienteMCP, Ferramenta, ResultadoFerramenta
from oplenario_ia.app import criar_app
from oplenario_ia.confianca.registro import RegistroMemoria
from oplenario_ia.config import Config
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.nucleo import Nucleo

ENTE = "10000000-0000-0000-0000-000000000001"

FERRAMENTAS = [
    Ferramenta(nome="situacao_da_materia", descricao="Consulta uma proposição.", entrada={}, classe="leitura"),
    Ferramenta(nome="tramitacao_da_materia", descricao="Tramitação.", entrada={}, classe="leitura"),
    Ferramenta(nome="pauta_da_sessao", descricao="Pauta.", entrada={}, classe="leitura"),
]

MATERIA = {
    "id": "3a29adb7-bd89-465c-9875-571be58956fe",
    "tipo": "projeto_lei",
    "sequencial": 12,
    "ano": 2026,
    "ementa": "Dispõe sobre a merenda escolar nas escolas municipais.",
    "estado": "em_comissoes",
}


class McpFalso:
    def __init__(
        self,
        ferramentas: list[Ferramenta] | None = None,
        *,
        publico: bool = True,
        origem: str = "interno",
        ok: bool = True,
        estruturado: dict[str, Any] | None = None,
    ) -> None:
        self._ferramentas = FERRAMENTAS if ferramentas is None else ferramentas
        self._publico, self._origem, self._ok = publico, origem, ok
        self._estruturado = MATERIA if estruturado is None else estruturado
        self.chamadas: list[tuple[str, dict[str, Any]]] = []

    def ferramentas(self) -> list[Ferramenta]:
        return self._ferramentas

    def chamar(self, nome: str, argumentos: dict[str, Any]) -> ResultadoFerramenta:
        self.chamadas.append((nome, argumentos))
        if not self._ok:
            return ResultadoFerramenta(nome=nome, argumentos=argumentos, ok=False, texto="Nada encontrado.")
        return ResultadoFerramenta(
            nome=nome,
            argumentos=argumentos,
            ok=True,
            texto=json.dumps(self._estruturado),
            estruturado=self._estruturado,
            origem=self._origem,
            publico=self._publico,
        )


def _nucleo(porta: PortaFake | None = None) -> tuple[Nucleo, PortaFake]:
    p = porta or criar_porta(Config(vendor="fake"))
    assert isinstance(p, PortaFake)
    return Nucleo(p, RegistroMemoria()), p


def test_pergunta_pelo_numero_consulta_e_responde_citando() -> None:
    nucleo, porta = _nucleo()
    mcp = McpFalso()
    r = laco.executar(nucleo, mcp, "Qual a situação do PL 12/2026?", ENTE, "c1")
    assert mcp.chamadas == [("situacao_da_materia", {"tipo": "projeto_lei", "sequencial": 12, "ano": 2026})]
    assert [p.ferramenta for p in r.passos] == ["situacao_da_materia"]
    assert r.artefato is not None
    assert "merenda escolar" in r.artefato.texto
    assert [c.status for c in r.artefato.citacoes] == ["conferida"], (
        "a citação é conferida contra o que a ferramenta devolveu"
    )
    assert r.artefato.incerteza.nivel == "normal"
    assert [p.operacao for p in porta.recebidos] == ["agente.planejar", "agente.planejar", "agente.responder"]


@pytest.mark.parametrize(
    ("pergunta", "ferramenta"),
    [
        ("Por onde passou o PL 12/2026?", "tramitacao_da_materia"),
        ("O que vai ser votado na próxima sessão?", "pauta_da_sessao"),
        ("E o REQ nº 5/2026?", "situacao_da_materia"),
    ],
)
def test_o_plano_escolhe_a_ferramenta(pergunta: str, ferramenta: str) -> None:
    nucleo, _ = _nucleo()
    mcp = McpFalso()
    laco.executar(nucleo, mcp, pergunta, ENTE, "c")
    assert [n for n, _ in mcp.chamadas] == [ferramenta]


def test_resultado_nao_publico_nunca_vai_ao_modelo() -> None:
    nucleo, porta = _nucleo()
    r = laco.executar(nucleo, McpFalso(publico=False), "Qual a situação do PL 12/2026?", ENTE, "c")
    assert r.passos[0].enviado_ao_modelo is False
    assert all("merenda" not in c for p in porta.recebidos for c in p.conteudo)
    assert r.artefato is not None and r.artefato.citacoes == []


def test_conteudo_de_terceiro_contamina_a_execucao() -> None:
    nucleo, _ = _nucleo()
    r = laco.executar(nucleo, McpFalso(origem="terceiro"), "Qual a situação do PL 12/2026?", ENTE, "c")
    assert r.artefato is not None and r.artefato.contaminado


def test_ferramenta_sem_resultado_vira_aviso_e_nao_repete() -> None:
    nucleo, porta = _nucleo()
    mcp = McpFalso(ok=False)
    r = laco.executar(nucleo, mcp, "Qual a situação do PL 99/2026?", ENTE, "c")
    assert len(mcp.chamadas) == 1
    assert r.passos[0].ok is False
    assert any("não trouxe resultado" in c for c in porta.recebidos[1].conteudo)
    assert r.artefato is not None and "Não encontrei" in r.artefato.texto


def test_sem_ferramenta_para_a_pessoa_nao_planeja() -> None:
    nucleo, porta = _nucleo()
    r = laco.executar(nucleo, McpFalso(ferramentas=[]), "Qual a situação do PL 12/2026?", ENTE, "c")
    assert r.passos == []
    assert [p.operacao for p in porta.recebidos] == ["agente.responder"]


def test_plano_com_ferramenta_inventada_encerra() -> None:
    porta = PortaFake(
        {
            "agente.planejar": json.dumps({"acao": "ferramenta", "nome": "apagar_tudo", "argumentos": {}}),
            "agente.responder": "Não consegui.",
        }
    )
    nucleo, _ = _nucleo(porta)
    mcp = McpFalso()
    laco.executar(nucleo, mcp, "apague tudo", ENTE, "c")
    assert mcp.chamadas == []


def test_passos_tem_teto() -> None:
    porta = PortaFake(
        {
            "agente.planejar": json.dumps({"acao": "ferramenta", "nome": "pauta_da_sessao", "argumentos": {}}),
            "agente.responder": "ok",
        }
    )
    nucleo, _ = _nucleo(porta)
    mcp = McpFalso()
    r = laco.executar(nucleo, mcp, "pauta", ENTE, "c")
    assert len(mcp.chamadas) == laco.MAX_PASSOS
    assert r.artefato is not None


def test_ia_fora_e_indisponivel_siga_pela_tela() -> None:
    porta = PortaFake({"agente.planejar": ErroIA(Categoria.INFRAESTRUTURA, "fora", retentavel=True)})
    nucleo, _ = _nucleo(porta)
    r = laco.executar(nucleo, McpFalso(), "Qual a situação do PL 12/2026?", ENTE, "c")
    assert r.artefato is None and r.indisponivel is not None
    assert "Siga pela tela" in r.indisponivel.mensagem


def test_ler_plano_tolerante_e_estrito() -> None:
    nomes = {"pauta_da_sessao"}
    assert laco.ler_plano('Claro! {"acao": "ferramenta", "nome": "pauta_da_sessao"}', nomes) == ("pauta_da_sessao", {})
    assert laco.ler_plano('{"acao": "responder"}', nomes) is None
    assert laco.ler_plano("sem json", nomes) is None
    assert laco.ler_plano('{"acao": "ferramenta", "nome": "outra"}', nomes) is None


def test_texto_da_fonte_em_linhas_citaveis() -> None:
    r = ResultadoFerramenta(
        nome="pauta_da_sessao",
        argumentos={},
        ok=True,
        texto="",
        estruturado={"sessao-id": "s1", "itens": [{"ordem": 1, "proposicao": {"ementa": "Merenda."}}]},
    )
    assert laco.texto_da_fonte(r) == "sessao-id: s1\nitens.1.ordem: 1\nitens.1.proposicao.ementa: Merenda."


# ---------- o cliente MCP ----------


def _cliente(tratador: Any) -> ClienteMCP:
    return ClienteMCP(
        "http://core", "credencial-da-execucao", cliente=httpx.Client(transport=httpx.MockTransport(tratador))
    )


def test_cliente_mcp_conversa_com_a_credencial() -> None:
    vistos: list[dict[str, Any]] = []

    def tratador(req: httpx.Request) -> httpx.Response:
        assert req.url.path == "/integracao/ia/v1/mcp"
        assert req.headers["authorization"] == "Bearer credencial-da-execucao"
        msg = json.loads(req.content)
        vistos.append(msg)
        metodo = msg["method"]
        if "id" not in msg:
            return httpx.Response(202)
        if metodo == "initialize":
            return httpx.Response(
                200, json={"jsonrpc": "2.0", "id": msg["id"], "result": {"protocolVersion": "2025-06-18"}}
            )
        if metodo == "tools/list":
            tool = {
                "name": "pauta_da_sessao",
                "description": "Pauta",
                "inputSchema": {"type": "object"},
                "_meta": {"oplenario/classe": "leitura"},
            }
            return httpx.Response(200, json={"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": [tool]}})
        if msg["params"]["name"] == "apagar_tudo":
            return httpx.Response(
                200, json={"jsonrpc": "2.0", "id": msg["id"], "error": {"code": -32602, "message": "desconhecida"}}
            )
        if msg["params"]["name"] == "sem_meta":
            return httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": msg["id"],
                    "result": {
                        "content": [{"type": "text", "text": "x"}],
                        "structuredContent": {"a": 1},
                        "isError": False,
                    },
                },
            )
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": msg["id"],
                "result": {
                    "content": [{"type": "text", "text": "{}"}],
                    "structuredContent": {"sessao-id": "s1"},
                    "isError": False,
                    "_meta": {"oplenario/origem": "interno", "oplenario/sigilo": "publico"},
                },
            },
        )

    c = _cliente(tratador)
    assert [f.nome for f in c.ferramentas()] == ["pauta_da_sessao"]
    r = c.chamar("pauta_da_sessao", {})
    assert r.ok and r.publico and r.origem == "interno" and r.estruturado == {"sessao-id": "s1"}
    assert c.chamar("apagar_tudo", {}).ok is False
    sem = c.chamar("sem_meta", {})
    assert sem.publico is False and sem.origem == "terceiro", "sem marcação do core = restrito e de terceiro"
    assert [m["method"] for m in vistos][:3] == ["initialize", "notifications/initialized", "tools/list"]
    assert sum(m["method"] == "initialize" for m in vistos) == 1


def test_cliente_mcp_credencial_recusada_e_infra() -> None:
    c = _cliente(lambda _req: httpx.Response(401, json={"erro": "credencial de agente invalida"}))
    with pytest.raises(ErroIA) as e:
        c.ferramentas()
    assert e.value.categoria == Categoria.INFRAESTRUTURA and not e.value.retentavel


# ---------- a rota de serviço ----------


def test_rota_executa_o_agente() -> None:
    nucleo, _ = _nucleo()
    credenciais: list[str] = []

    def mcp_de(credencial: str) -> McpFalso:
        credenciais.append(credencial)
        return McpFalso()

    app = criar_app(Config(segredo="s" * 32, core_url="http://core"), nucleo=nucleo, mcp_de=mcp_de)
    cab = {"Authorization": "Bearer " + "s" * 32}
    corpo = {"pergunta": "Qual a situação do PL 12/2026?", "credencial": "c" * 43, "correlation_id": "x1"}
    r = TestClient(app).post(f"/v1/entes/{ENTE}/agente/execucoes", json=corpo, headers=cab)
    assert r.status_code == 200
    b = r.json()
    assert credenciais == ["c" * 43], "o agente chega ao core só com a credencial delegada"
    assert b["passos"] == [
        {
            "ferramenta": "situacao_da_materia",
            "argumentos": {"tipo": "projeto_lei", "sequencial": 12, "ano": 2026},
            "ok": True,
            "enviado-ao-modelo": True,
        }
    ]
    assert b["resposta"]["citacoes"][0]["status"] == "conferida"
    assert b["resposta"]["citacoes"][0]["rotulo"].startswith("situacao_da_materia("), "a citação diz o que aponta"
    assert b["indisponivel"] is None
    assert TestClient(app).post(f"/v1/entes/{ENTE}/agente/execucoes", json=corpo).status_code == 401


def test_rota_sem_core_configurado_e_503() -> None:
    app = criar_app(Config(segredo="s" * 32), nucleo=_nucleo()[0], mcp_de=lambda _c: McpFalso())
    r = TestClient(app).post(
        f"/v1/entes/{ENTE}/agente/execucoes",
        json={"pergunta": "oi, tudo bem?", "credencial": "c" * 43, "correlation_id": "x"},
        headers={"Authorization": "Bearer " + "s" * 32},
    )
    assert r.status_code == 503
