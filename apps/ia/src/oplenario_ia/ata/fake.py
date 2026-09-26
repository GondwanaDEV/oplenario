"""O redator FAKE da ata — roteiro determinístico do fornecedor fake para a operação `ata.redigir` (dev, CI e demo).

Lê as fontes que o filtro montou e devolve uma ata esquemática que CITA cada fala (a conferência roda de verdade) e
termina com um parágrafo sem fonte e um ponto a confirmar — o caminho completo da revisão aparece na tela sem nenhum
fornecedor real.
"""

from __future__ import annotations

import html
import re

from oplenario_ia.inferencia.modelo import PedidoInferencia

_FONTE = re.compile(r'<fonte id="([^"]+)" rotulo="([^"]*)"[^>]*>\n(.*?)\n</fonte>', re.DOTALL)


def _frase(texto: str, teto: int = 160) -> str:
    t = " ".join(texto.split())
    corte = re.search(r"[.!?](\s|$)", t)
    t = t[: corte.end()].strip() if corte and corte.end() <= teto else t[:teto].rstrip()
    return t


def redigir(pedido: PedidoInferencia) -> str:
    paragrafos: list[str] = []
    for bruto in pedido.conteudo:
        for fonte_id, rotulo, texto in _FONTE.findall(bruto):
            texto = html.unescape(texto)
            trecho = _frase(texto)
            if fonte_id.startswith("sessao:"):
                paragrafos.append(f"Reuniu-se a Câmara Municipal em sessão. [[{fonte_id} | {trecho}]]")
                continue
            quem = html.unescape(rotulo).split(",")[0]
            quem = "Um orador não identificado" if quem == "Orador não identificado" else quem
            paragrafos.append(f"{quem} fez uso da palavra: “{trecho}” [[{fonte_id} | {trecho}]]")
    paragrafos.append(
        "Nada mais havendo a tratar, a Presidência encerrou a sessão. [confirmar: horário de encerramento]"
    )
    return "\n\n".join(paragrafos)
