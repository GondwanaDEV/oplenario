"""O agente FAKE — roteiros determinísticos do fornecedor fake para `agente.planejar` e `agente.responder` (dev, CI,
demo). Planeja por padrões da pergunta ("PL 12/2026", "tramitação", "pauta") entre as ferramentas que o core
ofereceu, e responde citando linhas LITERAIS do que a ferramenta devolveu (a conferência roda de verdade).
"""

from __future__ import annotations

import html
import json
import re

from oplenario_ia.ata.fake import FONTE
from oplenario_ia.inferencia.modelo import PedidoInferencia

SIGLAS = {
    "PL": "projeto_lei",
    "PLC": "projeto_lei_complementar",
    "PR": "projeto_resolucao",
    "PDL": "projeto_decreto_legislativo",
    "PELOM": "proposta_emenda_lom",
    "REQ": "requerimento",
    "IND": "indicacao",
    "MOC": "mocao",
}
_MATERIA = re.compile(r"\b(PLC|PDL|PELOM|PL|PR|REQ|IND|MOC)\s*(?:n[ºo°.]*\s*)?(\d+)\s*/\s*(\d{4})\b", re.IGNORECASE)
_TRAMITACAO = re.compile(r"tramit|andamento|por onde|passou|pr[óo]ximo passo|falta", re.IGNORECASE)
_PAUTA = re.compile(r"pauta|votad|vota[çc][ãa]o|pr[óo]xima sess[ãa]o", re.IGNORECASE)
_PREFERIDAS = ("ementa:", "estado-atual:", "estado:", "itens.1.proposicao.ementa:", "itens.1.texto-descricao:")


def _pergunta(pedido: PedidoInferencia) -> str:
    for c in pedido.conteudo:
        if c.startswith("Pergunta da pessoa:"):
            return c.removeprefix("Pergunta da pessoa:").strip()
    return ""


def _disponiveis(pedido: PedidoInferencia) -> set[str]:
    nomes: set[str] = set()
    for linha in pedido.instrucoes.split("\n"):
        if linha.startswith("{"):
            try:
                nomes.add(json.loads(linha)["nome"])
            except (ValueError, KeyError):
                continue
    return nomes


def planejar(pedido: PedidoInferencia) -> str:
    ja_consultou = any(c.startswith('<fonte id="ferramenta:') or c.startswith("A ferramenta ") for c in pedido.conteudo)
    if ja_consultou:
        return json.dumps({"acao": "responder"})
    pergunta = _pergunta(pedido)
    disponiveis = _disponiveis(pedido)
    m = _MATERIA.search(pergunta)
    if m:
        nome = "tramitacao_da_materia" if _TRAMITACAO.search(pergunta) else "situacao_da_materia"
        argumentos = {"tipo": SIGLAS[m.group(1).upper()], "sequencial": int(m.group(2)), "ano": int(m.group(3))}
        if nome in disponiveis:
            return json.dumps({"acao": "ferramenta", "nome": nome, "argumentos": argumentos})
    if _PAUTA.search(pergunta) and "pauta_da_sessao" in disponiveis:
        return json.dumps({"acao": "ferramenta", "nome": "pauta_da_sessao", "argumentos": {}})
    return json.dumps({"acao": "responder"})


def _linha_informativa(texto: str) -> str:
    linhas = [linha.strip() for linha in texto.split("\n") if linha.strip()]
    for prefixo in _PREFERIDAS:
        for linha in linhas:
            if linha.startswith(prefixo):
                return linha
    return linhas[0] if linhas else ""


def responder(pedido: PedidoInferencia) -> str:
    fontes = [(fid, html.unescape(texto)) for bruto in pedido.conteudo for fid, _rotulo, texto in FONTE.findall(bruto)]
    if not fontes:
        return "Não encontrei nas informações da Casa o que responder a essa pergunta."
    paragrafos = []
    for fid, texto in fontes[:2]:
        linha = _linha_informativa(texto)[:200].rstrip(" .")
        if linha:
            valor = linha.split(": ", 1)[-1]
            paragrafos.append(f"Segundo o sistema da Casa: {valor}. [[{fid} | {linha}]]")
    return "\n\n".join(paragrafos) or "Não encontrei nas informações da Casa o que responder a essa pergunta."
