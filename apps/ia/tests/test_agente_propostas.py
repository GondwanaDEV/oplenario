"""B.6 — o agente PROPÕE um ato: lê os modelos, pede `protocolar_requerimento` (que no core só cria a proposta) e
responde dizendo que nada foi feito ainda, citando a proposta."""

from __future__ import annotations

import json
from typing import Any

from oplenario_ia.agente import laco
from oplenario_ia.agente.mcp import Ferramenta, ResultadoFerramenta
from oplenario_ia.confianca.registro import RegistroMemoria
from oplenario_ia.config import Config
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.nucleo import Nucleo

ENTE = "10000000-0000-0000-0000-000000000001"
PERGUNTA = "Protocole um requerimento de informação à Secretaria de Obras sobre a reforma da praça do Centro."

FERRAMENTAS = [
    Ferramenta(nome="situacao_da_materia", descricao="Consulta.", entrada={}, classe="leitura"),
    Ferramenta(nome="modelos_de_requerimento", descricao="Modelos.", entrada={}, classe="leitura"),
    Ferramenta(nome="protocolar_requerimento", descricao="Propõe.", entrada={}, classe="ato"),
]

MODELOS = {
    "itens": [
        {"id": "m-oficio", "nome": "Requerimento de voto de pesar", "campos": ["homenageado"]},
        {"id": "m-info", "nome": "Requerimento de informação", "campos": ["destinatario", "assunto"]},
    ]
}

PROPOSTA = {
    "proposta-id": "p-1",
    "titulo": "Protocolar o requerimento “Informações sobre a reforma da praça do Centro”",
    "estado": "aguardando_confirmacao",
    "mensagem": "Proposta criada. Nada foi feito ainda.",
}


class McpProposta:
    def __init__(self, ferramentas: list[Ferramenta] | None = None, *, recusar: bool = False) -> None:
        self._ferramentas = FERRAMENTAS if ferramentas is None else ferramentas
        self._recusar = recusar
        self.chamadas: list[tuple[str, dict[str, Any]]] = []

    def ferramentas(self) -> list[Ferramenta]:
        return self._ferramentas

    def chamar(self, nome: str, argumentos: dict[str, Any]) -> ResultadoFerramenta:
        self.chamadas.append((nome, argumentos))
        if nome == "protocolar_requerimento" and self._recusar:
            return ResultadoFerramenta(nome=nome, argumentos=argumentos, ok=False, texto="Entrada invalida.")
        e = MODELOS if nome == "modelos_de_requerimento" else PROPOSTA
        return ResultadoFerramenta(
            nome=nome, argumentos=argumentos, ok=True, texto=json.dumps(e), estruturado=e, publico=True
        )


def _nucleo() -> Nucleo:
    p = criar_porta(Config(vendor="fake"))
    assert isinstance(p, PortaFake)
    return Nucleo(p, RegistroMemoria())


def test_le_os_modelos_e_propoe_com_os_campos_preenchidos() -> None:
    mcp = McpProposta()
    r = laco.executar(_nucleo(), mcp, PERGUNTA, ENTE, "c")
    assert mcp.chamadas == [
        ("modelos_de_requerimento", {}),
        (
            "protocolar_requerimento",
            {
                "modelo-id": "m-info",
                "ementa": "Informações sobre a reforma da praça do Centro",
                "campos": {"destinatario": "Secretaria de Obras", "assunto": "a reforma da praça do Centro"},
            },
        ),
    ], "o modelo de informação, pelo pedido; e para depois da proposta"
    assert r.artefato is not None
    assert "Nada foi protocolado ainda" in r.artefato.texto
    assert "tela Propostas" in r.artefato.texto
    assert [c.status for c in r.artefato.citacoes] == ["conferida"], "a resposta cita a proposta criada"


def test_proposta_recusada_pelo_core_nao_vira_laco() -> None:
    mcp = McpProposta(recusar=True)
    r = laco.executar(_nucleo(), mcp, PERGUNTA, ENTE, "c")
    assert [n for n, _ in mcp.chamadas] == ["modelos_de_requerimento", "protocolar_requerimento"]
    assert r.artefato is not None and "Preparei" not in r.artefato.texto


def test_sem_o_ato_no_catalogo_da_pessoa_nao_propoe() -> None:
    mcp = McpProposta(ferramentas=FERRAMENTAS[:1])
    laco.executar(_nucleo(), mcp, PERGUNTA, ENTE, "c")
    assert mcp.chamadas == []


def test_as_instrucoes_dizem_que_ato_so_propoe() -> None:
    assert "PROPOSTA" in laco.INSTRUCOES_PLANEJAR
    assert "NADA foi feito ainda" in laco.INSTRUCOES_RESPONDER


# ---------- ADR-0019: o pedido de parecer jurídico — lê a matéria (para o id) e PROPÕE ----------

PERGUNTA_PARECER = "Peça o parecer jurídico do PL 12/2026."
ID_MATERIA = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

FERRAMENTAS_PARECER = [
    Ferramenta(nome="situacao_da_materia", descricao="Consulta.", entrada={}, classe="leitura"),
    Ferramenta(nome="pedir_parecer_juridico", descricao="Propõe.", entrada={}, classe="ato"),
]


class McpParecer:
    def __init__(self, ferramentas: list[Ferramenta]) -> None:
        self._ferramentas = ferramentas
        self.chamadas: list[tuple[str, dict[str, Any]]] = []

    def ferramentas(self) -> list[Ferramenta]:
        return self._ferramentas

    def chamar(self, nome: str, argumentos: dict[str, Any]) -> ResultadoFerramenta:
        self.chamadas.append((nome, argumentos))
        e: dict[str, Any] = (
            {"id": ID_MATERIA, "tipo": "projeto_lei", "sequencial": 12, "ano": 2026, "ementa": "Institui a feira."}
            if nome == "situacao_da_materia"
            else {**PROPOSTA, "titulo": "Pedir parecer jurídico sobre PL 012/2026"}
        )
        return ResultadoFerramenta(
            nome=nome, argumentos=argumentos, ok=True, texto=json.dumps(e), estruturado=e, publico=True
        )


def test_le_a_materia_e_propoe_o_pedido_de_parecer_com_o_id_dela() -> None:
    mcp = McpParecer(FERRAMENTAS_PARECER)
    r = laco.executar(_nucleo(), mcp, PERGUNTA_PARECER, ENTE, "c")
    assert mcp.chamadas == [
        ("situacao_da_materia", {"tipo": "projeto_lei", "sequencial": 12, "ano": 2026}),
        ("pedir_parecer_juridico", {"proposicao-id": ID_MATERIA}),
    ]
    assert r.artefato is not None
    assert "parecer foi emitido" not in r.artefato.texto


def test_sem_o_ato_de_parecer_no_catalogo_nao_propoe_nem_inventa() -> None:
    mcp = McpParecer(FERRAMENTAS_PARECER[:1])
    laco.executar(_nucleo(), mcp, PERGUNTA_PARECER, ENTE, "c")
    assert "pedir_parecer_juridico" not in [n for n, _ in mcp.chamadas]
