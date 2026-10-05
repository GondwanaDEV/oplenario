"""A conferência de NÚMEROS contra uma fonte estruturada (§22.11.8).

A citação por trecho literal (`citacao.py`) prova que o trecho copiado está na fonte. Para um fato do core (o placar de
uma votação) isso não basta: o modelo pode copiar o trecho certo e escrever, na frase ao lado, outro número. Aqui a
frase que a marca de citação sustenta é lida em busca de números e cada um é conferido, sem outro modelo.

Regras que não se negociam:

- **Uma só leitura.** O texto da frase e o texto da fonte passam pela MESMA `normalizar` e pela MESMA `ler`: o que o
  conferidor enxerga é o que a pessoa enxerga na ata (dígitos Unicode, sobrescritos, largura total, caracteres
  invisíveis no meio do número: tudo vira o mesmo número que o olho lê).
- **Fail-closed.** O que a leitura não sabe interpretar COM CERTEZA ("1.000", "1,5", "1 0", "9/2", "-7", "7º",
  "mil", "dúzia") não é número conferido: vira uma dúvida, e a citação não confere. Nunca "não reconheci, deixa passar".
- **Papel do número.** "7 votos a favor, 2 contra" confere por PAPEL (sim, não, abstenção, quórum, composição): cada
  número que a prosa liga a um papel tem de ser o valor desse papel NESTA fonte (por votação, nunca de outra). Número
  que conta votos ou pessoas e não tem papel, ou que a fonte não tem, reprova.
- **Token inteiro.** Casamento por número lido por inteiro (17 não é 7 nem 1).

Datas completas (22/09/2026) e horas (18h30, 19:45) saem da frase antes da leitura: não são placar.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

from oplenario_ia.governanca.proveniencia import Fato, Fonte

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
    "dezasseis": 16,
    "dezessete": 17,
    "dezassete": 17,
    "dezoito": 18,
    "dezenove": 19,
    "dezanove": 19,
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
_UM = {"um": 1, "uma": 1}  # também é artigo ("um orador"): só vale colado ao que se conta, ou depois de "vinte e"
_NENHUM = {"nenhum", "nenhuma", "nenhuns", "nenhumas"}
# o que se conta numa votação: um número colado a uma destas palavras É uma contagem e precisa de papel
_CONTAVEIS = {
    "voto",
    "votos",
    "abstencao",
    "abstencoes",
    "abstiveram",
    "vereador",
    "vereadores",
    "parlamentar",
    "parlamentares",
    "membro",
    "membros",
    "presente",
    "presentes",
    "ausente",
    "ausentes",
    "sim",
    "nao",
    "contra",
    "favor",
    "favoravel",
    "favoraveis",
    "contrario",
    "contrarios",
}
_FRACOES = {"terco", "tercos", "quinto", "quintos"}  # "dois terços dos membros": o 2 não é uma contagem de membros
# números que a língua escreve por extenso e esta leitura NÃO interpreta: viram dúvida, nunca passam em silêncio
_NAO_INTERPRETADAS = {
    "mil",
    "milhao",
    "milhoes",
    "bilhao",
    "bilhoes",
    "duzia",
    "duzias",
    "dezena",
    "dezenas",
    "centena",
    "centenas",
}

_SINAIS = "-+−‐‑‒–—"
_DATA = re.compile(r"(?<![\d/])\d{1,2}/\d{1,2}/\d{2,4}(?![\d/])")
_HORA = re.compile(r"(?<!\d)\d{1,2}(?:h\d{0,2}|:\d{2})(?!\d)")
_BARRAS = re.compile(r"(?<![\d/])\d+(?:/\d+)+(?![\d/])")
_PAR = re.compile(r"(?<![\d/])(\d+)/(\d+)(?![\d/])")
_DECIMAL = re.compile(r"\d+(?:[.,]\d+)+")
_SINAL = re.compile(rf"\d+(?:[{_SINAIS}]\d+)+|(?<![a-z\d])[{_SINAIS}]\d+")
_ORDINAL = re.compile(r"\d+(?:o|a|os|as|°)\b")
_ESPACADO = re.compile(r"\d+(?:\s+\d+)+")
_TOKEN = re.compile(r"\d+|[a-z]+|[.,;:!?()\[\]]")
_PONTUACAO = set(".,;:!?()[]")
_JANELA = 3  # quantas palavras olhar para cada lado do número, em busca do papel dele
_FIM_DE_MARCA = re.compile(r"\]\]")
_CONFIRMAR = re.compile(r"\[\s*confirmar\s*:[^\]]*\]", re.IGNORECASE)
_PARAGRAFO = re.compile(r"\n[^\S\n]*\n")


def normalizar(texto: str) -> str:
    """A ÚNICA normalização: compatibilidade Unicode (largura total, sobrescritos, ordinais ª º → a o), sem os
    caracteres invisíveis de formatação (1 e 0 com um espaço de largura zero no meio são 10 para o olho), minúsculas e
    sem acento. Usada nos dois lados: na frase da ata e no texto da fonte."""
    t = unicodedata.normalize("NFKC", texto)
    t = "".join(c for c in t if unicodedata.category(c) != "Cf")
    t = unicodedata.normalize("NFD", t.casefold())
    return "".join(c for c in t if unicodedata.category(c) != "Mn")


@dataclass(frozen=True)
class Numero:
    valor: int
    antes: tuple[str, ...]  # as palavras logo antes, até a pontuação ou o outro número
    seguintes: tuple[str, ...]  # as palavras logo depois, até a pontuação ou o próximo número


@dataclass
class Leitura:
    numeros: list[Numero] = field(default_factory=list)
    duvidas: list[str] = field(default_factory=list)  # o que não se soube interpretar: a citação não confere
    unanimidade: bool = False


def _compor(tokens: list[str], i: int) -> tuple[int, int] | None:
    """O número por extenso que começa em `tokens[i]`: (valor, índice do próximo token). "vinte e três" = 23, "cento e
    vinte e um" = 121; só junta o que a língua junta (dezena + unidade, centena + dezena ou unidade), para
    "dois e três" não virar cinco."""
    if tokens[i] not in _PALAVRAS:
        return None
    valor = ultimo = _PALAVRAS[tokens[i]]
    j = i + 1
    while j + 1 < len(tokens) and tokens[j] == "e" and (tokens[j + 1] in _PALAVRAS or tokens[j + 1] in _UM):
        prox = _PALAVRAS.get(tokens[j + 1], _UM.get(tokens[j + 1], 0))
        if not ((20 <= ultimo < 100 and 0 < prox < 10) or (ultimo >= 100 and ultimo % 100 == 0 and 0 < prox < 100)):
            break
        valor += prox
        ultimo = prox
        j += 2
    return valor, j


def _mascarar(t: str, padrao: re.Pattern[str], duvida: str | None, duvidas: list[str]) -> str:
    """Troca por espaço o que o padrão acha; se `duvida`, cada achado vira uma dúvida (não se sabe o que é)."""

    def trocar(m: re.Match[str]) -> str:
        if duvida is not None:
            duvidas.append(f"'{m.group(0).strip()}' {duvida}")
        return " "

    return padrao.sub(trocar, t)


def ler(texto: str, pares_ok: frozenset[tuple[int, int]] | set[tuple[int, int]] = frozenset()) -> Leitura:
    """Os números do texto, em ordem, com as palavras de cada lado, e tudo o que não se soube interpretar.

    `pares_ok`: referências `a/b` (o número de uma matéria, "8/2026") que a fonte confirma — só passam como PAR."""
    duvidas: list[str] = []
    t = normalizar(texto)
    t = _mascarar(t, _DATA, None, duvidas)
    t = _mascarar(t, _HORA, None, duvidas)

    def barras(m: re.Match[str]) -> str:
        partes = tuple(int(p) for p in m.group(0).split("/"))
        if len(partes) == 2 and partes in pares_ok:
            return " "
        duvidas.append(f"'{m.group(0)}' é uma barra que não se sabe ler (data, fração ou placar?)")
        return " "

    t = _BARRAS.sub(barras, t)
    t = _mascarar(t, _DECIMAL, "tem separador de milhar ou decimal e não se sabe ler", duvidas)
    t = _mascarar(t, _SINAL, "tem sinal ou intervalo e não se sabe ler", duvidas)
    t = _mascarar(t, _ORDINAL, "é ordinal, não contagem de votos", duvidas)
    t = _mascarar(t, _ESPACADO, "tem dígitos separados por espaço e não se sabe ler", duvidas)
    tokens = _TOKEN.findall(t)
    achados: list[tuple[int, int, int]] = []  # (valor, índice do primeiro token, índice do primeiro depois)
    i = 0
    while i < len(tokens):
        tk = tokens[i]
        prox = tokens[i + 1] if i + 1 < len(tokens) else ""
        if tk.isdigit():
            achados.append((int(tk), i, i + 1))
            i += 1
        elif tk in _NENHUM or (tk == "sem" and prox in _CONTAVEIS):
            achados.append((0, i, i + 1))  # "nenhuma abstenção", "sem votos contrários": zero
            i += 1
        elif tk in _UM and prox in _CONTAVEIS:
            achados.append((1, i, i + 1))
            i += 1
        elif (c := _compor(tokens, i)) is not None:
            achados.append((c[0], i, c[1]))
            i = c[1]
        else:
            if tk in _NAO_INTERPRETADAS:
                duvidas.append(f"'{tk}' é número por extenso que não se sabe ler")
            i += 1

    def e_barreira(x: str) -> bool:
        return x in _PONTUACAO or x.isdigit() or x in _PALAVRAS or x in _FRACOES

    leitura = Leitura(duvidas=duvidas, unanimidade=any("unanim" in tk for tk in tokens))
    for valor, ini, depois in achados:
        seguintes: list[str] = []
        for x in tokens[depois : depois + _JANELA]:
            if e_barreira(x):
                break
            seguintes.append(x)
        antes: list[str] = []
        for x in reversed(tokens[max(0, ini - _JANELA) : ini]):
            if e_barreira(x):
                break
            antes.insert(0, x)
        leitura.numeros.append(Numero(valor, tuple(antes), tuple(seguintes)))
    return leitura


def numeros(texto: str) -> list[Numero]:
    return ler(texto).numeros


def identificadores(texto: str) -> tuple[list[int], list[tuple[int, int]]]:
    """Os números que IDENTIFICAM uma coisa e podem ser citados sem papel (o número de uma matéria: PL 008/2026 →
    8 e 2026, e o par 8/2026). Lidos com a mesma normalização do resto."""
    t = normalizar(texto)
    return [int(x) for x in re.findall(r"\d+", t)], [(int(a), int(b)) for a, b in _PAR.findall(t)]


def papel(n: Numero, fatos: list[Fato]) -> Fato | None:
    """O papel que a prosa dá ao número: um fato de `lado=qualquer` (o quórum: "eram necessários 9") se o nome dele está
    em qualquer das janelas; senão o primeiro nome de fato que aparece DEPOIS do número ("9 votos sim")."""
    for f in fatos:
        if f.lado == "qualquer" and {normalizar(x) for x in f.nomes} & {*n.antes, *n.seguintes}:
            return f
    for palavra in n.seguintes:
        for f in fatos:
            if f.lado == "depois" and palavra in {normalizar(x) for x in f.nomes}:
                return f
    return None


def afirmacao_da_marca(texto: str, inicio: int, fim: int) -> str:
    """A frase que a marca de citação (`texto[inicio:fim]`) sustenta: tudo desde o fim da citação anterior (ou o começo
    do parágrafo) até a marca — e, se nenhuma outra citação vem depois no parágrafo, o resto do parágrafo também (um
    número escrito DEPOIS da marca não pode escapar da conferência). Não se corta por ponto final: "Sr." não é fim de
    frase, e perder um número antes dele seria deixar passar. Os `[confirmar: …]` ficam de fora: já são dúvida
    declarada, e levam os dois valores de propósito."""
    antes = texto[:inicio]
    cortes = [m.end() for m in _PARAGRAFO.finditer(antes)] + [m.end() for m in _FIM_DE_MARCA.finditer(antes)]
    parte_antes = antes[max(cortes, default=0) :]
    depois = texto[fim:]
    fim_do_paragrafo = _PARAGRAFO.search(depois)
    resto = depois[: fim_do_paragrafo.start()] if fim_do_paragrafo else depois
    parte_depois = "" if "[[" in resto else resto
    return _CONFIRMAR.sub(" ", f"{parte_antes} {parte_depois}")


def divergencias(afirmacao: str, fonte: Fonte, texto_da_fonte: str) -> list[str]:
    """O que na afirmação não bate com a fonte estruturada (vazio = confere). Cada item diz o número e por quê."""
    pares = {(int(a), int(b)) for a, b in _PAR.findall(normalizar(texto_da_fonte))} | {(a, b) for a, b in fonte.pares}
    leitura = ler(afirmacao, pares)
    problemas = list(leitura.duvidas)
    livres = set(fonte.livres)
    for n in leitura.numeros:
        f = papel(n, fonte.fatos)
        if f is not None:
            if f.valor != n.valor:
                problemas.append(f"{n.valor} dito para '{f.nomes[0]}', mas o registro do sistema é {f.valor}")
        elif any(p in _CONTAVEIS for p in n.seguintes):
            problemas.append(f"{n.valor} '{n.seguintes[0]}' não tem papel conhecido no registro do sistema")
        elif n.valor not in livres:
            problemas.append(f"{n.valor} não consta no registro do sistema")
    if leitura.unanimidade and not fonte.unanime:
        problemas.append("unanimidade, mas o registro do sistema tem voto contrário, abstenção ou não tem placar")
    return problemas
