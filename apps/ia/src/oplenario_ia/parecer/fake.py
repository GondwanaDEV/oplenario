"""O copiloto do relator FAKE — roteiro determinístico do fornecedor fake para `relator.analisar` (dev, CI, demo).
Identifica o objeto da proposição citando a própria ementa; para cada dispositivo recebido (até três) diz o que ele traz
citando-o LITERALMENTE e aponta o que o relator deve confirmar; sem dispositivo, diz por quê (a Casa não publicou
normas, ou nada foi achado) num `[confirmar: …]`. A conferência das citações roda de verdade."""

from __future__ import annotations

import html
import re

from oplenario_ia.ata.fake import frase
from oplenario_ia.inferencia.modelo import PedidoInferencia
from oplenario_ia.parecer.analise import NORMAS_NAO_PUBLICADAS

_FONTE = re.compile(r'<fonte id="([^"]+)" rotulo="([^"]*)"(?: versao="([^"]*)")?>\n(.*?)\n</fonte>', re.DOTALL)
MAX_PONTOS = 3


def _trecho(texto: str) -> tuple[str, bool]:
    """Uma frase inteira do texto, ou o começo dela cortado numa palavra inteira (e se foi cortado)."""
    t = frase(texto)
    cortado = not t.rstrip().endswith((".", ";", ":"))
    if cortado and " " in t:
        t = t.rsplit(" ", 1)[0]
    return t.rstrip(" .;:,"), cortado


def analisar(p: PedidoInferencia) -> str:
    fontes = [(fid, html.unescape(r), html.unescape(t)) for c in p.conteudo for fid, r, _v, t in _FONTE.findall(c)]
    materia = next((f for f in fontes if f[0].startswith("materia:")), None)
    normas = [f for f in fontes if f[0].startswith("norma:")][:MAX_PONTOS]
    paragrafos: list[str] = []
    if materia:
        fid, rotulo, texto = materia
        ementa = texto.split("\n", 1)[0].removeprefix("Ementa: ").strip()
        paragrafos.append(f"A proposição em exame ({rotulo}) tem por objeto: “{ementa}”. [[{fid} | {ementa}]]")
    for fid, rotulo, texto in normas:
        trecho, cortado = _trecho(texto)
        paragrafos.append(
            f"Quanto às normas da Casa, {rotulo} dispõe: “{trecho}{'…' if cortado else ''}”. [[{fid} | {trecho}]] "
            "[confirmar: se a proposição atende ao que o dispositivo exige]"
        )
    if not normas:
        if any(NORMAS_NAO_PUBLICADAS in c for c in p.conteudo):
            motivo = "a Casa ainda não publicou a Lei Orgânica nem o Regimento Interno na plataforma"
        else:
            motivo = "não foram encontrados, nas normas da Casa, dispositivos sobre o assunto"
        paragrafos.append(
            f"[confirmar: {motivo}; verifique neles a competência do Município, a iniciativa e o quórum desta matéria]"
        )
    paragrafos.append("[confirmar: a técnica legislativa da proposição e o quórum de aprovação desta espécie]")
    return "\n\n".join(paragrafos)
