"""B.8 — a conferência institucional: o agente da Casa (sem pessoa) lê a proposição e os dispositivos da LOM/RI pelo
MCP, o núcleo redige a nota técnica com citação conferida e ela volta ao core como RASCUNHO. Casa que não ligou o
agente: nada roda. A credencial da execução é encerrada ao fim, dê certo ou não."""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from oplenario_ia.agente.mcp import Ferramenta, ResultadoFerramenta
from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.conferencia import fake
from oplenario_ia.conferencia.roteiro import AGENTE, OPERACAO, conferir, consultas, identificacao
from oplenario_ia.confianca.registro import RegistroMemoria
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.fronteira.cliente import ClienteCore
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.trabalhador import Trabalhador
from oplenario_ia.transcricao.fake import DiarizadorFake, TranscritorFake
from test_trabalhador import ENTE, FRASES, VOZES, Relogio

P1 = "20000000-0000-0000-0000-00000000000a"
NORMA = "30000000-0000-0000-0000-000000000001"

MATERIA = {
    "id": P1,
    "tipo": "requerimento",
    "sequencial": 5,
    "ano": 2026,
    "ementa": "Informações sobre a reforma da praça do Centro",
    "autor-texto": "Ver. Ana Prado",
    "tipo-requerimento": "informacao",
    "texto": "Requer à Secretaria de Obras informações sobre a reforma da praça do Centro.",
}
VERSAO = {"id": "v1", "consolidada-ate": "2026-06-30", "conferida-em": "2026-09-27T10:00:00Z"}
ART25 = "Compete à Câmara Municipal solicitar informações ao Prefeito sobre assuntos da administração."


def _ferramentas(nomes: list[str]) -> list[Ferramenta]:
    return [Ferramenta(nome=n, descricao="-", entrada={}, classe="leitura") for n in nomes]


TODAS = ["situacao_da_materia", "buscar_dispositivos", "ler_dispositivo", "registrar_nota_tecnica"]


class McpConferencia:
    def __init__(
        self,
        *,
        ferramentas: list[str] | None = None,
        achados: bool = True,
        publico: bool = True,
        recusar: bool = False,
    ) -> None:
        self._ferramentas = _ferramentas(TODAS if ferramentas is None else ferramentas)
        self._achados, self._publico, self._recusar = achados, publico, recusar
        self.chamadas: list[tuple[str, dict[str, Any]]] = []

    def ferramentas(self) -> list[Ferramenta]:
        return self._ferramentas

    def _ok(self, nome: str, args: dict[str, Any], e: dict[str, Any], publico: bool = True) -> ResultadoFerramenta:
        return ResultadoFerramenta(
            nome=nome, argumentos=args, ok=True, texto=json.dumps(e), estruturado=e, publico=publico, origem="interno"
        )

    def chamar(self, nome: str, args: dict[str, Any]) -> ResultadoFerramenta:
        self.chamadas.append((nome, args))
        if nome == "situacao_da_materia":
            return self._ok(nome, args, MATERIA, self._publico)
        if nome == "buscar_dispositivos":
            res = (
                [
                    {
                        "norma": {"id": NORMA, "titulo": "Lei Orgânica do Município", "especie": "lei_organica"},
                        "versao": VERSAO,
                        "endereco": "art25",
                        "rotulo": "art. 25",
                        "citacao": "Lei Orgânica do Município, art. 25",
                        "texto": ART25,
                        "agrupador": None,
                    }
                ]
                if self._achados
                else []
            )
            return self._ok(nome, args, {"modo": "ia", "aviso": None, "resultados": res})
        if nome == "ler_dispositivo":
            return self._ok(
                nome,
                args,
                {
                    "norma": {"id": NORMA, "titulo": "Lei Orgânica do Município", "especie": "lei_organica"},
                    "versao": VERSAO,
                    "citacao": "Lei Orgânica do Município, art. 25",
                    "dispositivos": [{"endereco": "art25", "rotulo": "art. 25", "tipo": "artigo", "texto": ART25}],
                },
            )
        if nome == "registrar_nota_tecnica":
            if self._recusar:
                return ResultadoFerramenta(nome=nome, argumentos=args, ok=False, texto="Entrada invalida.")
            return self._ok(nome, args, {"nota-id": "n-1", "estado": "pendente", "mensagem": "ok"})
        raise AssertionError(nome)

    def registrado(self) -> dict[str, Any]:
        return next(a for n, a in self.chamadas if n == "registrar_nota_tecnica")


def _nucleo() -> Nucleo:
    return Nucleo(PortaFake({OPERACAO: fake.redigir}), RegistroMemoria())


# ---------- o roteiro ----------


def test_le_a_materia_e_as_normas_e_registra_a_nota_com_citacoes_conferidas() -> None:
    mcp = McpConferencia()
    assert conferir(_nucleo(), mcp, ENTE, P1, "c") == "n-1"
    assert [n for n, _ in mcp.chamadas] == [
        "situacao_da_materia",
        "buscar_dispositivos",
        "buscar_dispositivos",
        "ler_dispositivo",
        "registrar_nota_tecnica",
    ], "a matéria, a busca pela espécie e pela ementa, a leitura do que achou (uma vez só), e o registro"
    assert mcp.chamadas[1][1]["consulta"] == "Requerimento de informacao"
    assert mcp.chamadas[3][1] == {"norma-id": NORMA, "endereco": "art25"}
    r = mcp.registrado()
    assert r["proposicao-id"] == P1
    assert r["texto"].startswith("A proposição (Requerimento nº 5/2026) foi conferida")
    assert "Dispositivo aplicável — Lei Orgânica do Município, art. 25: “Compete à Câmara" in r["texto"]
    assert [c["status"] for c in r["citacoes"]] == ["conferida", "conferida"]
    assert r["citacoes"][1]["rotulo"] == "Lei Orgânica do Município, art. 25 (consolidada até 30/06/2026)"
    assert (r["incerteza"], r["paragrafos-sem-fonte"]) == ("normal", [])
    assert r["modelo"].startswith("fake:")


def test_sem_dispositivo_a_nota_diz_e_sai_marcada_para_revisar() -> None:
    mcp = McpConferencia(achados=False)
    conferir(_nucleo(), mcp, ENTE, P1, "c")
    assert "ler_dispositivo" not in [n for n, _ in mcp.chamadas]
    r = mcp.registrado()
    assert "Não foram encontrados, nas normas da Casa" in r["texto"]
    assert r["paragrafos-sem-fonte"] == [1] and r["incerteza"] == "revisar_com_atencao"
    assert r["motivos-incerteza"] == ["sem_fonte"]


def test_materia_que_o_core_nao_marca_publica_nunca_vai_ao_modelo() -> None:
    mcp = McpConferencia(publico=False)
    assert conferir(_nucleo(), mcp, ENTE, P1, "c") is None
    assert [n for n, _ in mcp.chamadas] == ["situacao_da_materia"]


def test_sem_as_ferramentas_da_conferencia_nao_roda() -> None:
    mcp = McpConferencia(ferramentas=["situacao_da_materia"])
    with pytest.raises(ErroIA) as e:
        conferir(_nucleo(), mcp, ENTE, P1, "c")
    assert e.value.categoria is Categoria.ENTRADA and not e.value.retentavel
    assert mcp.chamadas == []


def test_nota_recusada_pelo_core_e_falha_de_entrada() -> None:
    with pytest.raises(ErroIA) as e:
        conferir(_nucleo(), McpConferencia(recusar=True), ENTE, P1, "c")
    assert "recusou a nota" in e.value.detalhe


def test_identificacao_e_consultas() -> None:
    assert identificacao(MATERIA) == "Requerimento nº 5/2026"
    assert consultas({"tipo": "projeto_lei", "ementa": "Institui hortas."}) == ["Projeto de Lei", "Institui hortas."]
    assert consultas({"tipo": "x", "ementa": ""}) == []


# ---------- o trabalho na fila ----------


class CoreConferencia:
    def __init__(self, *, concedido: bool = True, tipo: str = "ProposicaoProtocolada") -> None:
        self.concedido = concedido
        self.tipo = tipo
        self.pedidos: list[tuple[str, str]] = []

    def __call__(self, req: httpx.Request) -> httpx.Response:
        p = req.url.path
        self.pedidos.append((req.method, p))
        if p == "/integracao/ia/v1/eventos" and req.method == "GET":
            depois = int(req.url.params["depois"])
            ev = {
                "seq": 1,
                "ente-id": ENTE,
                "tipo": self.tipo,
                "versao": 1,
                "chave": f"{self.tipo}:v1:{P1}",
                "criado-em": "2026-09-27T01:00:00Z",
                "payload": {"proposicao-id": P1, "ementa": MATERIA["ementa"]},
            }
            evs = [ev] if depois < 1 else []
            return httpx.Response(200, json={"eventos": evs, "proximo": 1})
        if p == f"/integracao/ia/v1/entes/{ENTE}/agentes/{AGENTE}/execucoes" and req.method == "POST":
            if not self.concedido:
                return httpx.Response(404, json={"erro": "agente institucional nao concedido nesta Casa"})
            return httpx.Response(
                201, json={"execucao-id": "e-1", "credencial": "cred-1", "expira-em": "2026-09-27T01:15:00Z"}
            )
        if p == f"/integracao/ia/v1/entes/{ENTE}/agentes/{AGENTE}/execucoes/e-1" and req.method == "DELETE":
            return httpx.Response(204)
        return httpx.Response(404, json={"erro": "rota do teste"})


def _trabalhador(core: CoreConferencia, mcp: McpConferencia) -> tuple[Trabalhador, ArmazemMemoria, list[str]]:
    arm = ArmazemMemoria()
    abertos: list[str] = []

    def abrir(credencial: str) -> McpConferencia:
        abertos.append(credencial)
        return mcp

    t = Trabalhador(
        ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(core))),
        arm,
        TranscritorFake(FRASES),
        DiarizadorFake(VOZES),
        nucleo=_nucleo(),
        abrir_mcp=abrir,
        agora=Relogio(),
    )
    return t, arm, abertos


def _estado(arm: ArmazemMemoria) -> str:
    return {x["tipo"]: x["estado"] for x in arm.trabalhos()}["conferir_proposicao"]


def test_protocolada_numa_casa_que_ligou_vira_nota_e_a_credencial_e_encerrada() -> None:
    core, mcp = CoreConferencia(), McpConferencia()
    t, arm, abertos = _trabalhador(core, mcp)
    t.ciclo()
    assert abertos == ["cred-1"], "o MCP abre com a credencial da execução"
    assert mcp.registrado()["proposicao-id"] == P1
    assert ("DELETE", f"/integracao/ia/v1/entes/{ENTE}/agentes/{AGENTE}/execucoes/e-1") in core.pedidos
    assert _estado(arm) == "concluido"


def test_casa_que_nao_ligou_descarta_sem_abrir_nada() -> None:
    core, mcp = CoreConferencia(concedido=False), McpConferencia()
    t, arm, abertos = _trabalhador(core, mcp)
    t.ciclo()
    assert abertos == [] and mcp.chamadas == []
    assert _estado(arm) == "descartado"


def test_falha_no_meio_ainda_encerra_a_credencial() -> None:
    core, mcp = CoreConferencia(), McpConferencia(recusar=True)
    t, arm, _ = _trabalhador(core, mcp)
    t.ciclo()
    assert ("DELETE", f"/integracao/ia/v1/entes/{ENTE}/agentes/{AGENTE}/execucoes/e-1") in core.pedidos
    assert _estado(arm) == "falhou"


def test_edicao_da_proposicao_nao_confere_de_novo() -> None:
    core, mcp = CoreConferencia(tipo="ProposicaoAtualizada"), McpConferencia()
    t, arm, _ = _trabalhador(core, mcp)
    t.ciclo()
    assert "conferir_proposicao" not in {x["tipo"] for x in arm.trabalhos()}
