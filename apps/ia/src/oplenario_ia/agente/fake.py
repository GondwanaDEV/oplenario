"""O agente FAKE — roteiros determinísticos do fornecedor fake para `agente.planejar` e `agente.responder` (dev, CI,
demo). Planeja por padrões da pergunta ("PL 12/2026", "tramitação", "pauta", "art. 45 do Regimento", "quórum")
entre as ferramentas que o core ofereceu, e responde citando linhas LITERAIS do que a ferramenta devolveu (a
conferência roda de verdade) — dispositivo de norma é citado pelo artigo, com a data até quando o texto foi conferido.
"""

from __future__ import annotations

import html
import json
import re

from oplenario_ia.ata.fake import frase
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
_ARTIGO = re.compile(r"\bart(?:igo|\.)?\s*(\d+)", re.IGNORECASE)
_REGIMENTO = re.compile(r"regimento", re.IGNORECASE)
_LOM = re.compile(r"lei org[âa]nica|\bLOM\b", re.IGNORECASE)
_NORMATIVA = re.compile(
    r"regimento|lei org[âa]nica|\bLOM\b|qu[óo]rum|prazo|\bveto\b|maioria|compet[êe]ncia|\brito\b|dispositivo",
    re.IGNORECASE,
)
_FONTE = re.compile(r'<fonte id="([^"]+)" rotulo="([^"]*)"(?: versao="([^"]*)")?>\n(.*?)\n</fonte>', re.DOTALL)
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
    ja_consultou = any(c.startswith(("<fonte id=", "A ferramenta ")) for c in pedido.conteudo)
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
    artigo = _ARTIGO.search(pergunta)
    especie = "regimento_interno" if _REGIMENTO.search(pergunta) else "lei_organica" if _LOM.search(pergunta) else None
    if artigo and especie and "ler_dispositivo" in disponiveis:
        argumentos = {"especie": especie, "endereco": f"art{int(artigo.group(1))}"}
        return json.dumps({"acao": "ferramenta", "nome": "ler_dispositivo", "argumentos": argumentos})
    if _NORMATIVA.search(pergunta) and "buscar_dispositivos" in disponiveis:
        argumentos = {"consulta": pergunta[:300]}
        return json.dumps({"acao": "ferramenta", "nome": "buscar_dispositivos", "argumentos": argumentos})
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
    fontes = [
        (fid, html.unescape(rotulo), html.unescape(versao), html.unescape(texto))
        for bruto in pedido.conteudo
        for fid, rotulo, versao, texto in _FONTE.findall(bruto)
    ]
    if not fontes:
        return "Não encontrei nas informações da Casa o que responder a essa pergunta."
    paragrafos = []
    for fid, rotulo, versao, texto in fontes[:2]:
        if fid.startswith("norma:"):
            trecho = frase(texto).rstrip(" .;:")
            quando = f" ({versao})" if versao else ""
            paragrafos.append(f"{rotulo}{quando}: “{trecho}”. [[{fid} | {trecho}]]")
            continue
        linha = _linha_informativa(texto)[:200].rstrip(" .")
        if linha:
            valor = linha.split(": ", 1)[-1]
            paragrafos.append(f"Segundo o sistema da Casa: {valor}. [[{fid} | {linha}]]")
    return "\n\n".join(paragrafos) or "Não encontrei nas informações da Casa o que responder a essa pergunta."
