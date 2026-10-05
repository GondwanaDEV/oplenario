"""A conferência de NÚMEROS de uma fonte estruturada (§22.11.8): casamento exato + sobra zero, SEM interpretar prosa.

A citação por trecho literal (`citacao.py`) prova que o trecho copiado está na fonte. Para um fato do core (o placar
de uma votação) isso não basta: o modelo pode copiar o trecho certo e escrever, no parágrafo, outro número. Um
interpretador de prosa (ler "dez votos", adivinhar o papel de um número pela palavra vizinha, esconder datas) sempre
terá uma forma que lê diferente da pessoa. Este conferidor não lê nada: o satélite gera, a partir do DADO, um conjunto
FECHADO de frases canônicas (`ata/redacao.py:frases_canonicas`, a mesma função que o redator fake usa para escrever) e
o parágrafo que cita a fonte só confere se

  (a) contém, por substring exata, ao menos uma frase canônica DAQUELA fonte; e
  (b) removidas as frases canônicas e os identificadores canônicos da fonte (também por substring exata), NÃO SOBRA
      nenhum sinal numérico — definido por negação ampla (qualquer caractere de categoria N, qualquer marca
      combinante, caractere invisível ou letra fora do alfabeto latino, mais uma lista fechada de palavras e os
      algarismos romanos isolados).

Uma só normalização (`normalizar`: NFKC, minúsculas, espaços colapsados; nada mais) para o parágrafo, as frases e os
identificadores. Nada é mascarado antes da conferência: data e hora que sobram reprovam o parágrafo; o `[confirmar: …]`
(dúvida declarada, que o texto da ata mantém à vista) é a ÚNICA exclusão, por delimitador exato, e o resto do
parágrafo ainda tem de passar em (a) e (b).

O que isto garante: o parágrafo da votação só tem o placar como o sistema o escreveria. O que NÃO garante: ele não
entende português — uma expressão numérica fora da lista de palavras passa, e uma afirmação sem número ("rejeitada"
junto de uma frase de "aprovada") também. A revisão humana do rascunho continua obrigatória.
"""

from __future__ import annotations

import re
import unicodedata

_PALAVRAS = frozenset(
    {
        # zero … dezenove (um/uma também são artigos: num parágrafo de votação, aparecer é motivo para revisar)
        "zero", "um", "uma", "dois", "duas", "três", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove",
        "dez", "onze", "doze", "treze", "catorze", "quatorze", "quinze", "dezesseis", "dezasseis", "dezessete",
        "dezassete", "dezoito", "dezenove", "dezanove",
        # dezenas e centenas
        "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa", "dezena", "dezenas",
        "cem", "cento", "duzentos", "duzentas", "trezentos", "trezentas", "quatrocentos", "quatrocentas",
        "quinhentos", "quinhentas", "seiscentos", "seiscentas", "setecentos", "setecentas", "oitocentos",
        "oitocentas", "novecentos", "novecentas", "centena", "centenas",
        # ordens de grandeza e quantidades
        "mil", "milhar", "milhares", "milhão", "milhao", "milhões", "milhoes", "bilhão", "bilhao", "bilhões", "bilhoes",
        "meia", "meio", "meias", "meios", "dúzia", "duzia", "dúzias", "duzias", "dobro", "triplo", "quádruplo",
        "quadruplo", "metade", "metades", "terço", "terco", "terços", "tercos", "quarto", "quartos", "quinto",
        "quintos", "maioria", "minoria", "todos", "todas", "ambos", "ambas", "vários", "varios", "várias", "varias",
        "empate", "empatada", "empatado",
        # ordinais por extenso
        "primeiro", "primeira", "primeiros", "primeiras", "segundo", "segunda", "segundos", "segundas", "terceiro",
        "terceira", "terceiros", "terceiras", "quarta", "quartas", "quinta", "quintas", "sexto", "sexta", "sextos",
        "sextas", "sétimo", "setimo", "sétima", "setima", "oitavo", "oitava", "nono", "nona", "décimo", "decimo",
        "décima", "decima",
    }
)  # fmt: skip
_PREFIXOS = ("unanim", "unânim", "nenhum")  # unanimidade, unânime(s), nenhum, nenhuma…
_ROMANO = re.compile(r"m{0,3}(?:cm|cd|d?c{0,3})(?:xc|xl|l?x{0,3})(?:ix|iv|v?i{0,3})")
_PALAVRA = re.compile(r"[^\W\d_]+")
_CONFIRMAR = re.compile(r"\[confirmar: [^\[\]]*\]")
# categorias que não têm lugar numa frase de votação: números, marcas combinantes, controle/formatação/uso privado
_SUSPEITAS = ("N", "M", "Cf", "Cc", "Co", "Cs", "Cn")


def normalizar(texto: str) -> str:
    """O ÚNICO ponto de normalização: NFKC (sobrescritos, largura total, romanos e frações Unicode viram
    algarismos/letras comuns), minúsculas e espaços colapsados. Nada mais: nada é removido nem reescrito."""
    return " ".join(unicodedata.normalize("NFKC", texto).casefold().split())


def sinais_numericos(texto_normalizado: str) -> list[str]:
    """O que, num texto já normalizado, pode ser (ou esconder) um número. Negação ampla: tudo o que não seja letra
    latina, espaço ou pontuação é suspeito, e as palavras-número e os romanos isolados também."""
    sinais: list[str] = []
    for c in texto_normalizado:
        if c.isascii():  # caminho rápido: só dígito e controle importam
            if c.isdigit() or ord(c) < 32 or ord(c) == 127:
                sinais.append(c)
            continue
        if unicodedata.category(c).startswith(_SUSPEITAS):
            sinais.append(c)
        elif c.isalpha() and not unicodedata.name(c, "").startswith("LATIN"):
            sinais.append(c)  # homóglifo (um "e" cirílico dentro de "dez")
    for palavra in _PALAVRA.findall(texto_normalizado):
        if palavra in _PALAVRAS or palavra.startswith(_PREFIXOS) or _ROMANO.fullmatch(palavra):
            sinais.append(palavra)
    return sinais


def divergencias(
    paragrafo: str, frases_canonicas: list[str] | tuple[str, ...], identificadores: list[str]
) -> list[str]:
    """Por que o parágrafo (já sem as marcas de citação) NÃO confere com a fonte estruturada; vazio = confere."""
    texto = _CONFIRMAR.sub(" ", normalizar(paragrafo))
    frases = sorted({normalizar(f) for f in frases_canonicas}, key=len, reverse=True)
    if not any(f in texto for f in frases):
        return ["o parágrafo não traz nenhuma frase canônica do registro do sistema"]
    resto = texto
    for peca in sorted({*frases, *(normalizar(i) for i in identificadores)}, key=len, reverse=True):
        resto = resto.replace(peca, " ")
    return [f"sobrou '{s}' no parágrafo, fora do registro do sistema" for s in sinais_numericos(resto)]
