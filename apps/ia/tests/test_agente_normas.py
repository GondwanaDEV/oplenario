"""B.5 — o agente consulta as normas da Casa: cada dispositivo lido é uma fonte própria, citada pelo artigo e com a
data até quando o texto foi conferido; a resposta cita o dispositivo, e a citação é conferida contra o texto lido."""

from __future__ import annotations

import json
from typing import Any

import pytest

from oplenario_ia.agente import laco
from oplenario_ia.agente.mcp import Ferramenta, ResultadoFerramenta
from oplenario_ia.confianca.registro import RegistroMemoria
from oplenario_ia.config import Config
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.nucleo import Nucleo

ENTE = "10000000-0000-0000-0000-000000000001"
NORMA = {"id": "7b1c0e7e-1111-4222-8333-944455556666", "titulo": "Regimento Interno", "especie": "regimento_interno"}
VERSAO = {"id": "0f0e0d0c-aaaa-4bbb-8ccc-dddddddddddd", "consolidada-ate": "2026-06-30", "conferida-em": "2026-09-20"}

FERRAMENTAS = [
    Ferramenta(nome="pauta_da_sessao", descricao="Pauta.", entrada={}, classe="leitura"),
    Ferramenta(nome="buscar_dispositivos", descricao="Busca nas normas.", entrada={}, classe="leitura"),
    Ferramenta(nome="ler_dispositivo", descricao="Lê um dispositivo.", entrada={}, classe="leitura"),
]

LEITURA = {
    "norma": NORMA,
    "versao": VERSAO,
    "citacao": "Regimento Interno, art. 2º",
    "dispositivos": [
        {
            "endereco": "art2",
            "rotulo": "art. 2º",
            "tipo": "artigo",
            "texto": "As deliberações exigem maioria absoluta:",
        },
        {"endereco": "art2_cpt_inc1", "rotulo": "art. 2º, I", "tipo": "inciso", "texto": "para rejeitar o veto;"},
        {"endereco": "art2_par1u", "rotulo": "art. 2º, parágrafo único", "tipo": "paragrafo", "texto": ""},
    ],
}

BUSCA = {
    "modo": "ia",
    "aviso": None,
    "resultados": [
        {
            "norma": NORMA,
            "versao": {**VERSAO, "consolidada-ate": None},
            "endereco": "art2_cpt_inc1",
            "rotulo": "art. 2º, I",
            "citacao": "Regimento Interno, art. 2º, I",
            "texto": "para rejeitar o veto;",
            "agrupador": None,
        }
    ],
}


class McpNormas:
    def __init__(self) -> None:
        self.chamadas: list[tuple[str, dict[str, Any]]] = []

    def ferramentas(self) -> list[Ferramenta]:
        return FERRAMENTAS

    def chamar(self, nome: str, argumentos: dict[str, Any]) -> ResultadoFerramenta:
        self.chamadas.append((nome, argumentos))
        e = LEITURA if nome == "ler_dispositivo" else BUSCA
        return ResultadoFerramenta(
            nome=nome, argumentos=argumentos, ok=True, texto=json.dumps(e), estruturado=e, publico=True
        )


def _nucleo() -> tuple[Nucleo, PortaFake]:
    p = criar_porta(Config(vendor="fake"))
    assert isinstance(p, PortaFake)
    return Nucleo(p, RegistroMemoria()), p


def _resultado(nome: str, e: dict[str, Any]) -> ResultadoFerramenta:
    return ResultadoFerramenta(nome=nome, argumentos={}, ok=True, texto="", estruturado=e)


def test_cada_dispositivo_lido_e_uma_fonte_com_a_vigencia() -> None:
    ds = laco.dispositivos_do_resultado(_resultado("ler_dispositivo", LEITURA))
    assert [(f.id, f.rotulo, f.versao) for f, _ in ds] == [
        (f"norma:{NORMA['id']}#art2", "Regimento Interno, art. 2º", "consolidada até 30/06/2026"),
        (f"norma:{NORMA['id']}#art2_cpt_inc1", "Regimento Interno, art. 2º, I", "consolidada até 30/06/2026"),
    ], "dispositivo sem texto (o parágrafo vazio) não vira fonte"
    busca = laco.dispositivos_do_resultado(_resultado("buscar_dispositivos", BUSCA))
    assert [(f.rotulo, f.versao, t) for f, t in busca] == [
        ("Regimento Interno, art. 2º, I", "conferida em 20/09/2026", "para rejeitar o veto;")
    ], "sem data de consolidação, vale a data da conferência"
    assert laco.dispositivos_do_resultado(_resultado("pauta_da_sessao", {"itens": []})) == []


@pytest.mark.parametrize(
    ("pergunta", "chamada"),
    [
        (
            "O que diz o art. 2º do Regimento?",
            ("ler_dispositivo", {"especie": "regimento_interno", "endereco": "art2"}),
        ),
        ("E o artigo 10 da Lei Orgânica?", ("ler_dispositivo", {"especie": "lei_organica", "endereco": "art10"})),
        (
            "Qual o quórum para derrubar um veto?",
            ("buscar_dispositivos", {"consulta": "Qual o quórum para derrubar um veto?"}),
        ),
        ("Qual o prazo para emendas?", ("buscar_dispositivos", {"consulta": "Qual o prazo para emendas?"})),
    ],
)
def test_o_plano_vai_as_normas(pergunta: str, chamada: tuple[str, dict[str, Any]]) -> None:
    nucleo, _ = _nucleo()
    mcp = McpNormas()
    laco.executar(nucleo, mcp, pergunta, ENTE, "c")
    assert mcp.chamadas == [chamada]


def test_resposta_normativa_cita_o_dispositivo_conferido() -> None:
    nucleo, porta = _nucleo()
    r = laco.executar(nucleo, McpNormas(), "O que diz o art. 2º do Regimento?", ENTE, "c")
    assert r.artefato is not None
    assert [(c.fonte_id, c.status) for c in r.artefato.citacoes] == [
        (f"norma:{NORMA['id']}#art2", "conferida"),
        (f"norma:{NORMA['id']}#art2_cpt_inc1", "conferida"),
    ]
    assert "consolidada até 30/06/2026" in r.artefato.texto
    assert r.fontes[f"norma:{NORMA['id']}#art2"] == "Regimento Interno, art. 2º (consolidada até 30/06/2026)"
    assert 'versao="consolidada até 30/06/2026"' in porta.recebidos[-1].conteudo[1], "o modelo vê a vigência"
    assert "dispositivo da norma" in porta.recebidos[-1].instrucoes


def test_o_mesmo_dispositivo_lido_duas_vezes_e_uma_fonte() -> None:
    fontes = laco.pecas_do_resultado(_resultado("buscar_dispositivos", BUSCA), 1)
    laco.acrescentar(fontes, laco.pecas_do_resultado(_resultado("ler_dispositivo", LEITURA), 2))
    assert [p.fonte.id for p in fontes if p.fonte] == [
        f"norma:{NORMA['id']}#art2_cpt_inc1",
        f"norma:{NORMA['id']}#art2",
    ]
