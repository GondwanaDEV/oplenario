"""Citação por fonte lida na mesma execução (§22.11.7) — e a conferência objetiva dela (§22.11.8).

O modelo cita com a marca `[[<id da fonte> | <trecho literal>]]`. A conferência é determinística, sem outro modelo:
a fonte citada precisa ter sido LIDA nesta execução (uma peça com aquele `id` foi enviada) e o trecho precisa estar
naquela fonte. Citação inventada, fonte que não foi lida e trecho que a fonte não diz ficam marcados — nunca somem.

Fonte ESTRUTURADA (um fato do core, como o placar de uma votação) pede mais, e sem interpretar prosa: o PARÁGRAFO
que a cita (sem as marcas de citação, que o texto limpo da ata também tira) precisa conter uma frase canônica da fonte
e não pode sobrar nenhum sinal numérico fora das frases canônicas e dos identificadores dela (`numeros.py`). Duas
fontes estruturadas citadas no mesmo parágrafo: nenhuma confere. Reprovado sai `trecho_nao_encontrado`, igual ao trecho
que a fonte não diz — o dado do sistema é o que vale.
"""

from __future__ import annotations

import re
from typing import Literal

from pydantic import BaseModel

from oplenario_ia.confianca.numeros import divergencias
from oplenario_ia.governanca.proveniencia import Fonte

MARCA = re.compile(r"\[\[\s*([^|\]\s\"]+)\s*(?:\|\s*(.*?)\s*)?\]\]", re.DOTALL)
MIN_TRECHO = 12  # abaixo disto o trecho casa com qualquer coisa: não confere nada
_ASPAS = "\"'“”‘’«»"

StatusCitacao = Literal["conferida", "sem_trecho", "trecho_nao_encontrado", "fonte_nao_lida"]
PoliticaCitacao = Literal["nenhuma", "por_paragrafo"]


class FonteLida(BaseModel):
    """Uma fonte como o modelo a viu nesta execução (texto já redigido pelo filtro)."""

    fonte: Fonte
    texto: str


class Citacao(BaseModel):
    fonte_id: str
    trecho: str | None
    inicio: int  # posição da marca no texto do artefato
    fim: int
    status: StatusCitacao
    rotulo: str | None = None  # "art. 12, § 1º, da LOM de Baturité" — só quando a fonte foi lida
    versao: str | None = None


def _normalizar(s: str) -> str:
    return " ".join(s.strip().strip(_ASPAS).casefold().split())


def _paragrafo_confere(texto: str, inicio: int, fonte: Fonte, por_id: dict[str, FonteLida]) -> bool:
    """A regra da fonte estruturada, no parágrafo (bloco separado por linha em branco) que contém a marca."""
    bloco = next((b.group() for b in _BLOCO.finditer(texto) if b.start() <= inicio < b.end()), None)
    if bloco is None:
        return False
    citadas = {x.group(1) for x in MARCA.finditer(bloco)}
    if any(c != fonte.id and c in por_id and por_id[c].fonte.estruturada for c in citadas):
        return False  # uma votação por parágrafo
    return not divergencias(MARCA.sub(" ", bloco), fonte.canonicas, fonte.identificadores)


def conferir(texto: str, lidas: list[FonteLida]) -> list[Citacao]:
    por_id = {f.fonte.id: f for f in lidas}
    citacoes: list[Citacao] = []
    for m in MARCA.finditer(texto):
        fonte_id, trecho = m.group(1), (m.group(2) or None)
        lida = por_id.get(fonte_id)
        status: StatusCitacao
        if lida is None:
            status = "fonte_nao_lida"
        elif trecho is None or len(_normalizar(trecho)) < MIN_TRECHO:
            status = "sem_trecho"
        elif _normalizar(trecho) not in _normalizar(lida.texto) or (
            lida.fonte.estruturada and not _paragrafo_confere(texto, m.start(), lida.fonte, por_id)
        ):
            status = "trecho_nao_encontrado"
        else:
            status = "conferida"
        citacoes.append(
            Citacao(
                fonte_id=fonte_id,
                trecho=trecho,
                inicio=m.start(),
                fim=m.end(),
                status=status,
                rotulo=lida.fonte.rotulo if lida else None,
                versao=lida.fonte.versao if lida else None,
            )
        )
    return citacoes


_BLOCO = re.compile(r"(?:[^\n]|\n(?![ \t]*\n))+")


def paragrafos_sem_fonte(texto: str, citacoes: list[Citacao]) -> list[int]:
    """Índices dos parágrafos (blocos separados por linha em branco; títulos `#` não contam) sem NENHUMA citação
    conferida — saem marcados "sem fonte" (§16.8, v1.46)."""
    conferidas = [c for c in citacoes if c.status == "conferida"]
    sem: list[int] = []
    for i, m in enumerate(
        b for b in _BLOCO.finditer(texto) if b.group().strip() and not b.group().lstrip().startswith("#")
    ):
        if not any(m.start() <= c.inicio < m.end() for c in conferidas):
            sem.append(i)
    return sem
