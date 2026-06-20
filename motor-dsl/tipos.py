"""
Sistema de tipos do núcleo de expressão A2 (§22.7 Eixo A dec. 4/5).

Primitivos + compostos derivados de carga real no Eixo C (§22.7.5):
  - Competencia (período mês/ano)            — forçado por T1
  - Maioria/Limiar (fração + base de cálculo) — forçado por T4
  - Conjunto<T> (literal de conjunto)         — forçado por T3 (operador `in`)
  - Enum<dominio> e Registro (record)         — forçado por T3/T4 (ato.tipo, votacao.materia)
  - Racional (aritmética EXATA, não float)    — forçado por T4 (2/3·N sem erro de arredondamento)

Estes objetos são os do protótipo; o catálogo canônico (B3) declara o conteúdo
em catalogo.py. Tipos concretos de DB ficam para o chat de stack (§22.4.4).
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class Tipo:
    kind: str                       # 'primitivo'|'competencia'|'maioria'|'conjunto'|'enum'|'registro'
    nome: str = ""                  # primitivo: 'Booleano'…; enum/registro: nome do domínio/record
    elem: Optional["Tipo"] = None   # só para 'conjunto'

    def __str__(self) -> str:
        if self.kind == "conjunto":
            return f"Conjunto<{self.elem}>"
        if self.kind == "enum":
            return f"Enum<{self.nome}>"
        return self.nome or self.kind


# ---- primitivos ----
BOOLEANO = Tipo("primitivo", "Booleano")
INTEIRO  = Tipo("primitivo", "Inteiro")
TEXTO    = Tipo("primitivo", "Texto")
DATA     = Tipo("primitivo", "Data")
INSTANTE = Tipo("primitivo", "Instante")
DURACAO  = Tipo("primitivo", "Duracao")
RACIONAL = Tipo("primitivo", "Racional")   # exato (Fraction), nunca float — armadilha do quórum (T4)

# ---- compostos ----
COMPETENCIA = Tipo("competencia", "Competencia")
MAIORIA     = Tipo("maioria", "Maioria")


def Conjunto(elem: Tipo) -> Tipo:
    return Tipo("conjunto", "Conjunto", elem=elem)


def Enum(nome: str) -> Tipo:
    return Tipo("enum", nome)


def Registro(nome: str) -> Tipo:
    return Tipo("registro", nome)


# ---- predicados de compatibilidade usados pelo type-checker ----
_NUMERICOS = {INTEIRO, RACIONAL}
_TEMPORAIS = {DATA, INSTANTE}


def numerico(t: Tipo) -> bool:
    return t in _NUMERICOS


def temporal(t: Tipo) -> bool:
    return t in _TEMPORAIS


def compativel_argumento(formal: Tipo, real: Tipo) -> bool:
    """Aceita igualdade estrutural ou coerção numérica (Inteiro↔Racional).

    Coerção é restrita a numérico↔numérico: passar Inteiro onde se espera Texto
    continua erro (ex.: remessa_enviada(ente, 123, competencia) é rejeitado)."""
    if formal == real:
        return True
    if numerico(formal) and numerico(real):
        return True
    return False
