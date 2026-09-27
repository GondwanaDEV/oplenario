"""A conferência FAKE — roteiro determinístico do fornecedor fake para `conferencia.redigir` (dev, CI, demo). Identifica
a proposição citando a própria ementa e, para cada dispositivo recebido (até três), diz o que ele traz e o que a
secretaria deve confirmar, citando LITERALMENTE o dispositivo — a conferência das citações roda de verdade."""

from __future__ import annotations

import html
import re

from oplenario_ia.ata.fake import frase
from oplenario_ia.inferencia.modelo import PedidoInferencia

_FONTE = re.compile(r'<fonte id="([^"]+)" rotulo="([^"]*)"(?: versao="([^"]*)")?>\n(.*?)\n</fonte>', re.DOTALL)
MAX_PONTOS = 3


def _trecho(texto: str) -> tuple[str, bool]:
    """Uma frase inteira do texto, ou o começo dela cortado numa palavra inteira (e se foi cortado)."""
    t = frase(texto)
    cortado = not t.rstrip().endswith((".", ";", ":"))
    if cortado and " " in t:
        t = t.rsplit(" ", 1)[0]
    return t.rstrip(" .;:,"), cortado


def redigir(p: PedidoInferencia) -> str:
    fontes = [(fid, html.unescape(r), html.unescape(t)) for c in p.conteudo for fid, r, _v, t in _FONTE.findall(c)]
    materia = next((f for f in fontes if f[0].startswith("materia:")), None)
    normas = [f for f in fontes if f[0].startswith("norma:")][:MAX_PONTOS]
    paragrafos: list[str] = []
    if materia:
        fid, rotulo, texto = materia
        ementa = texto.split("\n", 1)[0].removeprefix("Ementa: ").strip()
        paragrafos.append(
            f"A proposição ({rotulo}) foi conferida contra as normas da Casa que tratam do assunto. "
            f"Ela trata de: “{ementa}”. [[{fid} | {ementa}]]"
        )
    for fid, rotulo, texto in normas:
        trecho, cortado = _trecho(texto)
        paragrafos.append(
            f"Dispositivo aplicável — {rotulo}: “{trecho}{'…' if cortado else ''}”. Confirme se a proposição atende "
            f"ao que ele exige. [[{fid} | {trecho}]]"
        )
    if not normas:
        paragrafos.append(
            "Não foram encontrados, nas normas da Casa, dispositivos sobre o assunto desta proposição; a conferência "
            "fica com a secretaria."
        )
    return "\n\n".join(paragrafos)
