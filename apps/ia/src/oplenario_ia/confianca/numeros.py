"""A conferência de NÚMEROS contra uma fonte estruturada (§22.11.8).

A citação por trecho literal (`citacao.py`) prova que o trecho copiado está na fonte. Para um fato do core (o placar de
uma votação) isso não basta: o modelo pode copiar o trecho certo e escrever, na frase ao lado, outro número. Aqui a
afirmação que antecede a marca de citação é lida em busca de números — em algarismos ou por extenso — e cada um é
conferido, sem outro modelo:

- todo número da afirmação tem de aparecer na fonte (o texto dela ou um dos seus `fatos`);
- um número que a prosa liga a um fato pelo nome ("10 votos favoráveis") tem de ser o valor desse fato — é o que
  pega o "sim" e o "não" trocados, que o primeiro teste deixaria passar.

Datas e horas saem da afirmação antes da leitura (22/09/2026 não é um placar). A leitura é deliberadamente
conservadora: na dúvida, o número é conferido, e quem erra fica marcado para a revisão humana — nunca some.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from oplenario_ia.governanca.proveniencia import Fato

_UNIDADES = {
    "zero": 0,
    "dois": 2,
    "duas": 2,
    "tres": 3,
    "quatro": 4,
    "cinco": 5,
    "seis": 6,
    "sete": 7,
    "oito": 8,
    "nove": 9,
    "dez": 10,
    "onze": 11,
    "doze": 12,
    "treze": 13,
    "catorze": 14,
    "quatorze": 14,
    "quinze": 15,
    "dezesseis": 16,
    "dezessete": 17,
    "dezoito": 18,
    "dezenove": 19,
}
_DEZENAS = {
    "vinte": 20,
    "trinta": 30,
    "quarenta": 40,
    "cinquenta": 50,
    "sessenta": 60,
    "setenta": 70,
    "oitenta": 80,
    "noventa": 90,
}
_CENTENAS = {
    "cem": 100,
    "cento": 100,
    "duzentos": 200,
    "trezentos": 300,
    "quatrocentos": 400,
    "quinhentos": 500,
    "seiscentos": 600,
    "setecentos": 700,
    "oitocentos": 800,
    "novecentos": 900,
}
_PALAVRAS = {**_UNIDADES, **_DEZENAS, **_CENTENAS}
# "um"/"uma" também é artigo ("um orador"): só vale como número colado ao que se conta
_UM = {"um", "uma"}
_CONTADOS = {"voto", "votos", "abstencao", "abstencoes", "vereador", "vereadores"}

_DATA = re.compile(r"\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b")
_HORA = re.compile(r"\b\d{1,2}\s*(?:h\s*\d{0,2}|:\d{2})\b", re.IGNORECASE)
_TOKEN = re.compile(r"\d+|[a-z]+|[.,;:!?()]")
_PONTUACAO = set(".,;:!?()")
_JANELA = 3  # quantas palavras depois do número procurar o nome do fato

_FIM_DE_FRASE = re.compile(r"[.!?;](?=\s)")
_FIM_DE_MARCA = re.compile(r"\]\]")


@dataclass(frozen=True)
class Numero:
    valor: int
    seguintes: tuple[str, ...]  # as palavras logo depois, até a pontuação ou o próximo número


def _sem_acento(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s.casefold()) if unicodedata.category(c) != "Mn")


def _compor(tokens: list[str], i: int) -> tuple[int, int] | None:
    """O número por extenso que começa em `tokens[i]`: (valor, índice do próximo token). "vinte e três" = 23 e "cento
    e vinte e cinco" = 125; só junta o que a língua junta (dezena + unidade, centena + dezena ou unidade), para
    "dois e três" não virar cinco."""
    if tokens[i] not in _PALAVRAS:
        return None
    valor = ultimo = _PALAVRAS[tokens[i]]
    j = i + 1
    while j + 1 < len(tokens) and tokens[j] == "e" and tokens[j + 1] in _PALAVRAS:
        prox = _PALAVRAS[tokens[j + 1]]
        if not ((20 <= ultimo < 100 and 0 < prox < 10) or (ultimo >= 100 and ultimo % 100 == 0 and 0 < prox < 100)):
            break
        valor += prox
        ultimo = prox
        j += 2
    return valor, j


def numeros(texto: str) -> list[Numero]:
    """Os números do texto, em ordem, em algarismos ou por extenso (0 a 999), com as palavras que vêm depois."""
    limpo = _HORA.sub(" ", _DATA.sub(" ", texto))
    tokens = _TOKEN.findall(_sem_acento(limpo))
    achados: list[tuple[int, int]] = []  # (valor, índice do primeiro token depois do número)
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t.isdigit():
            achados.append((int(t), i + 1))
            i += 1
        elif t in _UM and i + 1 < len(tokens) and tokens[i + 1] in _CONTADOS:
            achados.append((1, i + 1))
            i += 1
        elif (c := _compor(tokens, i)) is not None:
            achados.append(c)
            i = c[1]
        else:
            i += 1
    saida: list[Numero] = []
    for valor, depois in achados:
        seguintes: list[str] = []
        for t in tokens[depois : depois + _JANELA]:
            if t in _PONTUACAO or t.isdigit() or t in _PALAVRAS:
                break
            seguintes.append(t)
        saida.append(Numero(valor, tuple(seguintes)))
    return saida


def afirmacao_antes_da_marca(texto: str, inicio: int) -> str:
    """A frase que a marca de citação (em `texto[inicio:]`) sustenta: do começo da frase — ou do fim da citação
    anterior, ou da linha — até a marca. O ponto que fecha a frase logo antes da marca não conta como começo."""
    antes = texto[:inicio].rstrip()
    antes = antes[:-1].rstrip() if antes and antes[-1] in ".!?;" else antes
    corte = max(
        antes.rfind("\n") + 1,
        max((m.end() for m in _FIM_DE_FRASE.finditer(antes)), default=0),
        max((m.end() for m in _FIM_DE_MARCA.finditer(antes)), default=0),
    )
    return antes[corte:].strip()


def divergencias(afirmacao: str, texto_da_fonte: str, fatos: list[Fato]) -> list[str]:
    """O que na afirmação não bate com a fonte estruturada (vazio = confere). Cada item diz o número e por quê."""
    na_fonte = {n.valor for n in numeros(texto_da_fonte)} | {f.valor for f in fatos}
    problemas: list[str] = []
    for n in numeros(afirmacao):
        dono = next((f for f in fatos if any(p in f.nomes for p in n.seguintes)), None)
        if dono is not None and dono.valor != n.valor:
            problemas.append(f"{n.valor} dito para '{dono.nomes[0]}', mas o registro do sistema é {dono.valor}")
        elif n.valor not in na_fonte:
            problemas.append(f"{n.valor} não consta no registro do sistema")
    return problemas
