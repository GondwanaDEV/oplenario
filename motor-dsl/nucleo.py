"""
Núcleo de expressão A2 (§22.7 Eixo A dec. 1): lexer + AST + parser, mais o loader
do envelope de compliance (§22.7.5). "Sem loops, sem variáveis mutáveis, sem
efeitos colaterais" — herdado de §22.4 eixo C.

O loader é proprietário (não usa YAML de prateleira) por dois motivos: zero
dependência e, sobretudo, FIDELIDADE — um parser YAML misparsearia expressões como
`ato.tipo in { resolucao, ... }` (vira mapping/set). Aqui os campos de expressão
ficam como STRING CRUA e vão para o parser de expressão (honra `fonte_yaml`, B1).
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Any, List, Optional, Tuple


class ErroSintaxe(Exception):
    pass


# ===========================================================================
# AST do núcleo de expressão
# ===========================================================================
@dataclass(frozen=True)
class Lit:
    valor: Any
    tipo_lit: str            # 'Inteiro' | 'Texto' | 'Booleano'


@dataclass(frozen=True)
class ConjuntoLit:
    elementos: Tuple["No", ...]


@dataclass(frozen=True)
class Ident:
    nome: str


@dataclass(frozen=True)
class Campo:
    obj: "No"
    campo: str


@dataclass(frozen=True)
class Chamada:
    nome: str
    args: Tuple["No", ...]


@dataclass(frozen=True)
class BinOp:
    op: str
    esq: "No"
    dir: "No"


@dataclass(frozen=True)
class UnOp:
    op: str
    operando: "No"


No = object  # alias de documentação


# ===========================================================================
# Lexer
# ===========================================================================
_OPS2 = {">=", "<=", "==", "!="}
_BOOL_LIT = {"verdadeiro": True, "falso": False}
_RESERVADAS = {"in", "e", "ou", "nao"}


def tokenizar(s: str) -> List[Tuple[str, Any]]:
    toks: List[Tuple[str, Any]] = []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c.isspace():
            i += 1
            continue
        if c == '"':
            j = i + 1
            while j < n and s[j] != '"':
                j += 1
            if j >= n:
                raise ErroSintaxe(f"string não terminada em: {s!r}")
            toks.append(("STR", s[i + 1:j]))
            i = j + 1
            continue
        if c.isdigit():
            j = i
            while j < n and s[j].isdigit():
                j += 1
            toks.append(("INT", int(s[i:j])))
            i = j
            continue
        if c.isalpha() or c == "_":
            j = i
            while j < n and (s[j].isalnum() or s[j] == "_"):
                j += 1
            toks.append(("IDENT", s[i:j]))
            i = j
            continue
        if s[i:i + 2] in _OPS2:
            toks.append(("OP", s[i:i + 2]))
            i += 2
            continue
        if c in "><*+-":
            toks.append(("OP", c))
            i += 1
            continue
        simples = {"(": "LP", ")": "RP", "{": "LB", "}": "RB", ",": "COMMA", ".": "DOT"}
        if c in simples:
            toks.append((simples[c], c))
            i += 1
            continue
        raise ErroSintaxe(f"caractere inesperado {c!r} em: {s!r}")
    toks.append(("EOF", None))
    return toks


# ===========================================================================
# Parser (descida recursiva; precedência: ou < e < nao < comparação < +,- < * )
# ===========================================================================
class _Parser:
    def __init__(self, toks: List[Tuple[str, Any]]):
        self.toks = toks
        self.i = 0

    def _peek(self):
        return self.toks[self.i]

    def _eh(self, tipo, valor=None) -> bool:
        t, v = self.toks[self.i]
        return t == tipo and (valor is None or v == valor)

    def _avancar(self):
        t = self.toks[self.i]
        self.i += 1
        return t

    def _esperar(self, tipo) -> Any:
        t, v = self.toks[self.i]
        if t != tipo:
            raise ErroSintaxe(f"esperava {tipo}, veio {t}({v!r})")
        self.i += 1
        return v

    # --- gramática ---
    def expr(self):
        return self._ou()

    def _ou(self):
        e = self._e()
        while self._eh("IDENT", "ou"):
            self._avancar()
            e = BinOp("ou", e, self._e())
        return e

    def _e(self):
        e = self._nao()
        while self._eh("IDENT", "e"):
            self._avancar()
            e = BinOp("e", e, self._nao())
        return e

    def _nao(self):
        if self._eh("IDENT", "nao"):
            self._avancar()
            return UnOp("nao", self._nao())
        return self._comparacao()

    def _comparacao(self):
        e = self._soma()
        t, v = self._peek()
        if (t == "OP" and v in (">", ">=", "<", "<=", "==", "!=")) or (t == "IDENT" and v == "in"):
            self._avancar()
            return BinOp(v, e, self._soma())
        return e

    def _soma(self):
        e = self._produto()
        while self._eh("OP", "+") or self._eh("OP", "-"):
            op = self._avancar()[1]
            e = BinOp(op, e, self._produto())
        return e

    def _produto(self):
        e = self._primario()
        while self._eh("OP", "*"):
            self._avancar()
            e = BinOp("*", e, self._primario())
        return e

    def _primario(self):
        t, v = self._peek()
        if t == "INT":
            self._avancar()
            return Lit(v, "Inteiro")
        if t == "STR":
            self._avancar()
            return Lit(v, "Texto")
        if t == "LB":
            return self._conjunto()
        if t == "LP":
            self._avancar()
            e = self.expr()
            self._esperar("RP")
            return e
        if t == "IDENT":
            self._avancar()
            if v in _BOOL_LIT:
                return Lit(_BOOL_LIT[v], "Booleano")
            if v in _RESERVADAS:
                raise ErroSintaxe(f"palavra reservada {v!r} fora de lugar")
            no: Any
            if self._eh("LP"):
                no = Chamada(v, tuple(self._args()))
            else:
                no = Ident(v)
            while self._eh("DOT"):
                self._avancar()
                campo = self._esperar("IDENT")
                no = Campo(no, campo)
            return no
        raise ErroSintaxe(f"token inesperado {t}({v!r})")

    def _args(self):
        self._esperar("LP")
        args: List[Any] = []
        if not self._eh("RP"):
            args.append(self.expr())
            while self._eh("COMMA"):
                self._avancar()
                args.append(self.expr())
        self._esperar("RP")
        return args

    def _conjunto(self):
        self._esperar("LB")
        els: List[Any] = []
        if not self._eh("RB"):
            els.append(self.expr())
            while self._eh("COMMA"):
                self._avancar()
                els.append(self.expr())
        self._esperar("RB")
        return ConjuntoLit(tuple(els))


def parse_expr(s: str):
    p = _Parser(tokenizar(s))
    no = p.expr()
    if not p._eh("EOF"):
        t, v = p._peek()
        raise ErroSintaxe(f"sobra de tokens após a expressão: {t}({v!r})")
    return no


# ===========================================================================
# Loader do envelope de compliance (§22.7.5)
# ===========================================================================
@dataclass
class Envelope:
    template: str
    contexto: str
    dominio: str
    parametros: dict                 # nome -> nome_de_tipo (string)
    aplica_quando: Optional[str]
    exige: Optional[str]
    prazo: Any                       # None | {'janela':str,'a_partir_de':str} | {'__malformado__': str}
    severidade: Optional[str]
    referencia_normativa: Optional[str]
    bruto: dict                      # tudo como lido, p/ diagnóstico


def _sem_comentario(linha: str) -> str:
    out, em_str = [], False
    for ch in linha:
        if ch == '"':
            em_str = not em_str
        if ch == "#" and not em_str:
            break
        out.append(ch)
    return "".join(out).rstrip()


def _parse_flow_map(valor: str) -> dict:
    """Parseia `{ a: A, b: B }` -> {'a':'A','b':'B'}."""
    v = valor.strip()
    if v.startswith("{"):
        v = v[1:]
    if v.endswith("}"):
        v = v[:-1]
    d = {}
    for parte in v.split(","):
        parte = parte.strip()
        if not parte:
            continue
        k, _, t = parte.partition(":")
        d[k.strip()] = t.strip()
    return d


def carregar_envelope(texto: str) -> Envelope:
    linhas = [_sem_comentario(l) for l in texto.splitlines()]
    bruto: dict = {}
    i = 0
    while i < len(linhas):
        raw = linhas[i]
        if not raw.strip() or raw[0] == " ":
            i += 1
            continue
        chave, _, valor = raw.partition(":")
        chave, valor = chave.strip(), valor.strip()
        if valor == "":
            filhos = {}
            j = i + 1
            while j < len(linhas) and linhas[j].startswith(" ") and linhas[j].strip():
                ck, _, cv = linhas[j].strip().partition(":")
                filhos[ck.strip()] = cv.strip()
                j += 1
            bruto[chave] = filhos if filhos else None
            i = j
        else:
            bruto[chave] = valor
            i += 1

    # normalizações
    parametros = _parse_flow_map(bruto.get("parametros", "")) if bruto.get("parametros") else {}

    prazo_raw = bruto.get("prazo", None)
    if isinstance(prazo_raw, dict):
        prazo = prazo_raw
    elif prazo_raw is None and "prazo" not in bruto:
        prazo = None                                  # ausente = obrigação contínua (§22.7.5 S1)
    else:
        prazo = {"__malformado__": str(prazo_raw)}    # ex.: T4 `prazo: ???`

    return Envelope(
        template=bruto.get("template", ""),
        contexto=bruto.get("contexto", ""),
        dominio=bruto.get("dominio", ""),
        parametros=parametros,
        aplica_quando=bruto.get("aplica_quando"),
        exige=bruto.get("exige"),
        prazo=prazo,
        severidade=bruto.get("severidade"),
        referencia_normativa=bruto.get("referencia_normativa"),
        bruto=bruto,
    )
