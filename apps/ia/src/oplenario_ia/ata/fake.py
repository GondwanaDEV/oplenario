"""O redator FAKE da ata — roteiro determinístico do fornecedor fake para a operação `ata.redigir` (dev, CI e demo).

Lê as fontes que o filtro montou e devolve uma ata esquemática que CITA cada fala e cada votação (a conferência roda
de verdade) e termina com um parágrafo sem fonte e um ponto a confirmar — o caminho completo da revisão aparece na tela
sem nenhum fornecedor real.

A votação sai do registro do sistema (a fonte `votacao:`): o roteiro COPIA a linha `Frase do resultado`, que o satélite
gerou do dado (`ata/redacao.py:frase_do_resultado`, a mesma frase que o conferidor casa por substring exata), num
parágrafo só da votação, sem nenhum outro número. Quando a sessão tem UMA votação e a gravação diz, em algarismos, outro
placar para ela, o roteiro faz o que a instrução manda ao modelo real: escreve a frase do sistema e deixa `[confirmar: a
gravação indica X; o sistema registra Y]` com os dois valores. (É um roteiro: lê só algarismos e só com uma votação; o
modelo real é quem lê o contexto.)
"""

from __future__ import annotations

import html
import re

from oplenario_ia.inferencia.modelo import PedidoInferencia

FONTE = re.compile(r'<fonte id="([^"]+)" rotulo="([^"]*)"[^>]*>\n(.*?)\n</fonte>', re.DOTALL)
LINHA = re.compile(r"^(Matéria votada|Modalidade|Placar|Frase do resultado): (.*)$", re.MULTILINE)
# o placar do registro ("9 votos sim, 2 votos não, 1 abstenção") e como a gravação o diz em algarismos
PLACAR = (
    ("votos a favor", r"(\d+) votos? sim", r"(\d+)\s+votos?\s+(?:sim|a favor|favor[aá]veis)"),
    ("votos contra", r"(\d+) votos? não", r"(\d+)\s+votos?\s+(?:n[aã]o|contra|contr[aá]rios)"),
    ("abstenções", r"(\d+) abstenç(?:ão|ões)", r"(\d+)\s+absten"),
)


def frase(texto: str, teto: int = 160) -> str:
    t = " ".join(texto.split())
    corte = re.search(r"[.!?](\s|$)", t)
    t = t[: corte.end()].strip() if corte and corte.end() <= teto else t[:teto].rstrip()
    return t


def _votacao(fonte_id: str, texto: str) -> tuple[str, dict[str, int]]:
    """(o parágrafo da votação, os totais do registro por rótulo), lidos do registro do sistema."""
    campos = dict(LINHA.findall(texto))
    totais = {
        rotulo: int(m.group(1)) for rotulo, no_registro, _ in PLACAR if (m := re.search(no_registro, campos["Placar"]))
    }
    resultado = campos["Frase do resultado"]
    corpo = f"Votação {campos['Modalidade']}: {campos['Matéria votada']}, {resultado}. [[{fonte_id} | {resultado}]]"
    return corpo, totais


def _contradicoes(falas: list[str], totais: dict[str, int]) -> list[str]:
    """Placar que a gravação diz e o registro do sistema não confirma: `[confirmar: …]` com os dois valores."""
    achados: list[str] = []
    for texto in falas:
        for rotulo, _, na_fala in PLACAR:
            for m in re.finditer(na_fala, texto, re.IGNORECASE):
                if rotulo in totais and int(m.group(1)) != totais[rotulo]:
                    achados.append(
                        f"[confirmar: a gravação indica {m.group(1)} {rotulo}; o sistema registra {totais[rotulo]}]"
                    )
    return list(dict.fromkeys(achados))


def redigir(pedido: PedidoInferencia) -> str:
    fontes = [
        (fid, html.unescape(rot), html.unescape(txt)) for b in pedido.conteudo for fid, rot, txt in FONTE.findall(b)
    ]
    votacoes = [_votacao(fid, txt) for fid, _, txt in fontes if fid.startswith("votacao:")]
    falas = [txt for fid, _, txt in fontes if fid.startswith("transcricao:")]
    paragrafos: list[str] = []
    for fonte_id, rotulo, texto in fontes:
        if fonte_id.startswith("votacao:"):
            continue  # vão depois da abertura, na ordem em que o sistema as registrou, um parágrafo cada
        trecho = frase(texto)
        if fonte_id.startswith("sessao:"):
            paragrafos.append(f"Reuniu-se a Câmara Municipal em sessão. [[{fonte_id} | {trecho}]]")
            for corpo, totais in votacoes:
                avisos = _contradicoes(falas, totais) if len(votacoes) == 1 else []
                paragrafos.append(" ".join([corpo, *avisos]))
            continue
        quem = rotulo.split(",")[0]
        quem = "Um orador não identificado" if quem == "Orador não identificado" else quem
        paragrafos.append(f"{quem} fez uso da palavra: “{trecho}” [[{fonte_id} | {trecho}]]")
    paragrafos.append(
        "Nada mais havendo a tratar, a Presidência encerrou a sessão. [confirmar: horário de encerramento]"
    )
    return "\n\n".join(paragrafos)
