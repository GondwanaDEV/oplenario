"""O copiloto do requerimento FAKE — roteiros determinísticos do fornecedor fake para `requerimento.preencher` e
`requerimento.justificar` (dev, CI, demo). Preenche pelo que a frase diz ("à Secretaria de Obras", "sobre a reforma
da praça"), escolhe o modelo de informação quando o pedido fala em informação, e justifica citando LITERALMENTE o
primeiro dispositivo recebido (a conferência roda de verdade)."""

from __future__ import annotations

import html
import json
import re
from typing import Any

from oplenario_ia.ata.fake import frase
from oplenario_ia.inferencia.modelo import PedidoInferencia

_DESTINATARIO = re.compile(
    r"\b(?:à|ao|a|para a|para o)\s+((?:Secretaria|Prefeitura|Prefeito|Companhia|Autarquia|Superintend[êe]ncia|"
    r"Procuradoria|Coordenadoria|Guarda)[^,.;?]*?)"
    r"(?=\s+(?:sobre|a respeito|informa\w*|pedindo|solicitando)\b|[,.;?]|$)",
    re.IGNORECASE,
)
_ASSUNTO = re.compile(r"\b(?:sobre|a respeito d[aoe]s?)\s+(.+?)[.?!]*$", re.IGNORECASE)
_FONTE = re.compile(r'<fonte id="([^"]+)" rotulo="([^"]*)"(?: versao="([^"]*)")?>\n(.*?)\n</fonte>', re.DOTALL)


def _pedido(p: PedidoInferencia) -> str:
    for c in p.conteudo:
        if c.startswith("Pedido do vereador:"):
            return c.removeprefix("Pedido do vereador:").split("\n")[0].strip()
    return ""


def _modelos(p: PedidoInferencia) -> list[dict[str, Any]]:
    for c in p.conteudo:
        if c.startswith("Modelos de requerimento da Casa:"):
            return [json.loads(linha) for linha in c.split("\n")[1:] if linha.startswith("{")]
    return []


def _assunto(texto: str) -> str:
    a = _ASSUNTO.search(texto)
    return a.group(1).strip() if a else ""


def preencher(p: PedidoInferencia) -> str:
    pedido, modelos = _pedido(p), _modelos(p)
    if not modelos:
        return "{}"
    informacao = [m for m in modelos if "informa" in m["nome"].lower()]
    modelo = informacao[0] if informacao and "informa" in pedido.lower() else modelos[0]
    d = _DESTINATARIO.search(pedido)
    destinatario, assunto = (d.group(1).strip() if d else ""), _assunto(pedido)
    valores = {"destinatario": destinatario, "assunto": assunto}
    campos = {c: valores[c] for c in modelo["campos"] if valores.get(c)}
    ementa = f"Informações sobre {assunto}" if assunto else pedido[:200]
    return json.dumps({"modelo_id": modelo["id"], "ementa": ementa, "campos": campos}, ensure_ascii=False)


def justificar(p: PedidoInferencia) -> str:
    assunto = _assunto(_pedido(p)) or "o tema do pedido"
    fontes = [(fid, html.unescape(r), html.unescape(t)) for c in p.conteudo for fid, r, _v, t in _FONTE.findall(c)]
    abertura = f"O presente requerimento busca esclarecer {assunto}, assunto de interesse direto da população."
    if not fontes:
        return abertura
    fid, rotulo, texto = fontes[0]
    trecho = frase(texto)
    cortado = not trecho.rstrip().endswith((".", ";", ":"))
    if cortado and " " in trecho:
        trecho = trecho.rsplit(" ", 1)[0]  # o teto da frase cortou no meio: fica a última palavra inteira
    trecho = trecho.rstrip(" .;:,")
    reticencias = "…" if cortado else ""
    base = f"A iniciativa ampara-se nas normas da Casa ({rotulo}): “{trecho}{reticencias}”."
    return f"{abertura} {base} [[{fid} | {trecho}]]"
