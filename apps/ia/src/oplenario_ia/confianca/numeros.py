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

E (c) o que sobra só pode ser MOLDURA: uma lista fechada de palavras que apresentam a votação ("votação nominal",
"a matéria", "foi") e pontuação comum. Qualquer outra palavra ou símbolo reprova — é o que barra a negação ("não foi
aprovada por…"), o prefixo colado ("desaprovada por…"), o resultado dito ao lado ("rejeitada") e o número colado.

O que isto garante: o parágrafo da votação só tem o placar como o sistema o escreveria, dentro de uma moldura
conhecida. O que NÃO garante: ele não entende português — a ordem das palavras da moldura não é conferida. A revisão
humana do rascunho continua obrigatória.
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
# A dúvida declarada só sai da conferência quando é a dúvida CANÔNICA: a gravação diz um número e o sistema registra o
# valor do DADO. Qualquer outro `[confirmar: …]` no parágrafo da votação fica à vista do conferidor — senão o bloco
# viraria um lugar para escrever qualquer afirmação ("[confirmar: na verdade rejeitada]") num parágrafo "conferido".
_CONFIRMAR = re.compile(r"\[confirmar: a gravação indica [0-9]{1,4} ([^\[\]]*)\]")
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


# O que PODE sobrar no parágrafo de uma votação depois de tiradas as frases canônicas e os identificadores: só a
# moldura que apresenta a votação. É uma lista de PERMITIDOS, não de proibidos: uma lista de proibidos (números,
# palavras-número) deixava passar o que inverte ou altera o sentido sem usar número — "não foi aprovada por 9 votos…",
# "desaprovada por 9 votos…" (a canônica casa DENTRO da palavra), "dezvotos" colado, "rejeitada" ao lado da frase de
# "aprovada". Com a lista de permitidos, qualquer palavra, prefixo ou símbolo fora dela reprova o parágrafo.
_MOLDURA = frozenset(
    {
        "a", "o", "as", "os", "da", "do", "das", "dos", "de", "em", "na", "no", "e", "foi", "pelo", "pela",
        "votação", "votacao", "matéria", "materia", "proposição", "proposicao", "plenário", "plenario", "sessão",
        "sessao", "nesta", "submetida", "submetido", "colocada", "colocado", "resultado", "registrou", "registrado",
        "registrada", "sistema",
    }
)  # fmt: skip
# A MODALIDADE não é moldura: "votação secreta" numa votação nominal é afirmação falsa feita só com palavras inocentes.
# Ela entra como peça canônica DA FONTE ("votação nominal", gerada do dado), como o identificador da matéria.
# Pontuação: só a de frase. Travessão, hífen, barra, parêntese e aspas ficam de fora — "PL 008/2026-A" é outra matéria.
_PONTUACAO = frozenset(".,;:")
_PECA = re.compile(r"[^\W_]+|\S")


def fora_da_moldura(texto_normalizado: str) -> list[str]:
    """As peças do texto (palavras e símbolos) que não são moldura nem pontuação comum. Vazio = só moldura."""
    return [p for p in _PECA.findall(texto_normalizado) if p not in _MOLDURA and p not in _PONTUACAO]


def _peca(texto_normalizado: str) -> re.Pattern[str]:
    """A peça canônica como PALAVRA INTEIRA: não casa colada em letra ou algarismo ("desaprovada por…", "19 votos")."""
    return re.compile(r"(?<![^\W_])" + re.escape(texto_normalizado) + r"(?![^\W_])")


def divergencias(
    paragrafo: str,
    frases_canonicas: list[str] | tuple[str, ...],
    identificadores: list[str],
    duvidas_canonicas: list[str] | tuple[str, ...] = (),
) -> list[str]:
    """Por que o parágrafo (já sem as marcas de citação) NÃO confere com a fonte estruturada; vazio = confere."""
    duvidas = {normalizar(d) for d in duvidas_canonicas}
    texto = _CONFIRMAR.sub(lambda m: " " if m.group(1) in duvidas else m.group(0), normalizar(paragrafo))
    frases = sorted({normalizar(f) for f in frases_canonicas}, key=len, reverse=True)
    if not any(_peca(f).search(texto) for f in frases):
        return ["o parágrafo não traz nenhuma frase canônica do registro do sistema"]
    resto = texto
    for peca in sorted({*frases, *(normalizar(i) for i in identificadores)}, key=len, reverse=True):
        resto = _peca(peca).sub(" ", resto)
    # as duas redes: o sinal numérico (o motivo mais útil de ler no log) e, por fim, tudo o que não é moldura
    sobras = list(dict.fromkeys([*sinais_numericos(resto), *fora_da_moldura(resto)]))
    return [f"sobrou '{s}' no parágrafo, fora do registro do sistema" for s in sobras]
