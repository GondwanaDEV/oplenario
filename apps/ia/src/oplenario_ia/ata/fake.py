"""O redator FAKE da ata — roteiro determinístico do fornecedor fake para a operação `ata.redigir` (dev, CI e demo).

Lê as fontes que o filtro montou e devolve uma ata esquemática que CITA cada fala e cada votação (a conferência roda
de verdade) e termina com um parágrafo sem fonte e um ponto a confirmar — o caminho completo da revisão aparece na tela
sem nenhum fornecedor real.

A votação sai do registro do sistema (a fonte `votacao:`): o resultado e o placar vêm dali, nunca da fala. Quando a
sessão tem UMA votação e a gravação diz outro placar para ela, o roteiro faz o que a instrução manda ao modelo real:
escreve o dado do sistema e deixa `[confirmar: a gravação indica X; o sistema registra Y]` com os dois valores. (Com
várias votações o roteiro não sabe a qual fala se refere e não compara — o modelo real é quem lê o contexto.)
"""

from __future__ import annotations

import html
import re

from oplenario_ia.ata.redacao import NOMES_ABSTENCAO, NOMES_NAO, NOMES_SIM
from oplenario_ia.confianca.numeros import numeros
from oplenario_ia.inferencia.modelo import PedidoInferencia

FONTE = re.compile(r'<fonte id="([^"]+)" rotulo="([^"]*)"[^>]*>\n(.*?)\n</fonte>', re.DOTALL)
LINHA = re.compile(r"^(Matéria votada|Modalidade|Placar|Resultado): (.*)$", re.MULTILINE)
TIPOS = (("sim", NOMES_SIM, "votos sim"), ("não", NOMES_NAO, "votos não"), ("abstenção", NOMES_ABSTENCAO, "abstenções"))


def frase(texto: str, teto: int = 160) -> str:
    t = " ".join(texto.split())
    corte = re.search(r"[.!?](\s|$)", t)
    t = t[: corte.end()].strip() if corte and corte.end() <= teto else t[:teto].rstrip()
    return t


def _juntar(partes: list[str]) -> str:
    return partes[0] if len(partes) == 1 else f"{', '.join(partes[:-1])} e {partes[-1]}"


def _votacao(fonte_id: str, texto: str) -> tuple[str, dict[str, int], str]:
    """(o parágrafo da votação, os totais por tipo, o trecho do placar), lidos do registro do sistema."""
    campos = dict(LINHA.findall(texto))
    placar = campos["Placar"]
    totais = {
        nome: n.valor for nome, nomes, _ in TIPOS for n in numeros(placar) if any(p in nomes for p in n.seguintes)
    }
    if placar.startswith("sem contagem"):
        frase_placar, trecho = "", f"Resultado: {campos['Resultado']}"
    else:
        frase_placar = f", com {_juntar(placar.split(', '))}"
        trecho = f"Placar: {placar}"
    corpo = (
        f"Votação {campos['Modalidade']}: {campos['Matéria votada']}, {campos['Resultado']}{frase_placar}."
        f" [[{fonte_id} | {trecho}]]"
    )
    return corpo, totais, trecho


def _contradicoes(falas: list[str], totais: dict[str, int]) -> list[str]:
    """Placar que a gravação diz e o registro do sistema não confirma: `[confirmar: …]` com os dois valores."""
    achados: list[str] = []
    for texto in falas:
        for n in numeros(texto):
            for nome, nomes, rotulo in TIPOS:
                if nome in totais and any(p in nomes for p in n.seguintes) and n.valor != totais[nome]:
                    achados.append(
                        f"[confirmar: a gravação indica {n.valor} {rotulo}; o sistema registra {totais[nome]}]"
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
            continue  # vão depois da abertura, na ordem em que o sistema as registrou
        trecho = frase(texto)
        if fonte_id.startswith("sessao:"):
            paragrafos.append(f"Reuniu-se a Câmara Municipal em sessão. [[{fonte_id} | {trecho}]]")
            for corpo, totais, _ in votacoes:
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
