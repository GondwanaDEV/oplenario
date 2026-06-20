"""
Type-checker do *save time* (§22.7 Eixo A dec. 2): valida envelope + expressões
contra o catálogo (B3). Regra mal-tipada NÃO vira `vigente` — é o que tira a falha
de compliance do caminho crítico (princípio comercial, §22.7 / Invariante 4).

Resultado separa duas coisas de propósito:
  - `status` VALIDA/INVALIDA  → o veredito que decide persistência (dec. 2)
  - `expressao_ok`            → se o NÚCLEO de expressão tipou, independente do envelope

T4 (quórum) é o caso-chave: `expressao_ok=True` (núcleo reaproveitável) mas
`status=INVALIDA` (envelope de compliance não cabe — é guard de plenário, S4).
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import List, Optional, Set

import catalogo as cat
from nucleo import (
    Envelope, Lit, ConjuntoLit, Ident, Campo, Chamada, BinOp, UnOp,
    parse_expr, ErroSintaxe,
)
from tipos import (
    Tipo, Conjunto, Enum, Registro,
    BOOLEANO, INTEIRO, TEXTO, DATA, INSTANTE, RACIONAL,
    numerico, temporal, compativel_argumento,
)


class ErroTipo(Exception):
    pass


_LIT_TIPO = {"Inteiro": INTEIRO, "Texto": TEXTO, "Booleano": BOOLEANO}


def inferir(no, env: dict) -> Tipo:
    if isinstance(no, Lit):
        return _LIT_TIPO[no.tipo_lit]
    if isinstance(no, Ident):
        return _ident(no.nome, env)
    if isinstance(no, Campo):
        return _campo(no, env)
    if isinstance(no, Chamada):
        return _chamada(no, env)
    if isinstance(no, ConjuntoLit):
        return _conjunto(no, env)
    if isinstance(no, BinOp):
        return _binop(no, env)
    if isinstance(no, UnOp):
        return _unop(no, env)
    raise ErroTipo(f"nó AST desconhecido: {no!r}")


def _ident(nome: str, env: dict) -> Tipo:
    if nome in env:
        return env[nome]
    dom = cat.dominio_do_literal(nome)
    if dom:
        return Enum(dom)
    raise ErroTipo(f"identificador desconhecido: {nome!r}")


def _campo(no: Campo, env: dict) -> Tipo:
    t = inferir(no.obj, env)
    if t.kind != "registro":
        raise ErroTipo(f"acesso a campo .{no.campo} em tipo não-registro {t}")
    campos = cat.REGISTROS.get(t.nome, {})
    if no.campo not in campos:
        raise ErroTipo(f"registro {t.nome} não tem campo {no.campo!r}")
    return campos[no.campo]


def _chamada(no: Chamada, env: dict) -> Tipo:
    if no.nome == "parametro_tenant":
        return _parametro_tenant(no, env)
    sig = cat.buscar_assinatura(no.nome)
    if sig is None:
        raise ErroTipo(f"função desconhecida: {no.nome!r}")
    if len(no.args) != len(sig.params):
        raise ErroTipo(f"{no.nome}: esperava {len(sig.params)} arg(s), veio {len(no.args)}")
    for k, (formal, arg) in enumerate(zip(sig.params, no.args), start=1):
        real = inferir(arg, env)
        if not compativel_argumento(formal, real):
            raise ErroTipo(f"{no.nome}: arg {k} esperava {formal}, veio {real}")
    return sig.retorno


def _parametro_tenant(no: Chamada, env: dict) -> Tipo:
    # tipado por CHAVE (binding B2): a chave precisa ser literal e estar no schema.
    if len(no.args) != 1:
        raise ErroTipo("parametro_tenant: esperava 1 arg (a chave)")
    a = no.args[0]
    if not (isinstance(a, Lit) and a.tipo_lit == "Texto"):
        raise ErroTipo("parametro_tenant: a chave deve ser literal Texto")
    if a.valor not in cat.PARAMETROS_TENANT:
        raise ErroTipo(f"parametro_tenant: chave desconhecida {a.valor!r}")
    return cat.PARAMETROS_TENANT[a.valor]


def _conjunto(no: ConjuntoLit, env: dict) -> Tipo:
    if not no.elementos:
        raise ErroTipo("conjunto vazio sem tipo declarado")
    tipos_el = [inferir(e, env) for e in no.elementos]
    t0 = tipos_el[0]
    for t in tipos_el[1:]:
        if t != t0:
            raise ErroTipo(f"conjunto heterogêneo: {t0} vs {t}")
    return Conjunto(t0)


def _binop(no: BinOp, env: dict) -> Tipo:
    op = no.op
    le, ri = inferir(no.esq, env), inferir(no.dir, env)
    if op in ("e", "ou"):
        if le != BOOLEANO or ri != BOOLEANO:
            raise ErroTipo(f"operador '{op}' exige Booleano, veio {le} e {ri}")
        return BOOLEANO
    if op == "in":
        if ri.kind != "conjunto":
            raise ErroTipo(f"'in' exige Conjunto à direita, veio {ri}")
        if le != ri.elem:
            raise ErroTipo(f"'in': {le} não é elemento de {ri}")
        return BOOLEANO
    if op in (">", ">=", "<", "<="):
        if (numerico(le) and numerico(ri)) or (temporal(le) and temporal(ri)):
            return BOOLEANO
        raise ErroTipo(f"comparação '{op}' exige numérico/temporal homogêneo, veio {le} e {ri}")
    if op in ("==", "!="):
        if le == ri or (numerico(le) and numerico(ri)):
            return BOOLEANO
        raise ErroTipo(f"'{op}' exige tipos compatíveis, veio {le} e {ri}")
    if op in ("+", "-", "*"):
        if numerico(le) and numerico(ri):
            return RACIONAL if (le == RACIONAL or ri == RACIONAL) else INTEIRO
        raise ErroTipo(f"'{op}' exige numérico, veio {le} e {ri}")
    raise ErroTipo(f"operador binário desconhecido: {op}")


def _unop(no: UnOp, env: dict) -> Tipo:
    t = inferir(no.operando, env)
    if no.op == "nao":
        if t != BOOLEANO:
            raise ErroTipo(f"'nao' exige Booleano, veio {t}")
        return BOOLEANO
    raise ErroTipo(f"operador unário desconhecido: {no.op}")


# ===========================================================================
# Verificação de um template inteiro
# ===========================================================================
@dataclass
class Resultado:
    chave: str
    status: str                       # 'VALIDA' | 'INVALIDA'
    erros: List[str]
    avisos: List[str]
    expressao_ok: bool                # núcleo de expressão tipou (independe do envelope)
    registry_versao_ref: Optional[str]  # carimbo (B1) — só quando VALIDA


def _checar_expr(rotulo, fonte, env, esperado: Optional[Set[Tipo]], erros) -> bool:
    if fonte is None:
        erros.append(f"{rotulo}: ausente")
        return False
    try:
        no = parse_expr(fonte)
    except ErroSintaxe as e:
        erros.append(f"{rotulo}: erro de sintaxe — {e}")
        return False
    try:
        t = inferir(no, env)
    except ErroTipo as e:
        erros.append(f"{rotulo}: erro de tipo — {e}")
        return False
    if esperado and t not in esperado:
        alvos = " ou ".join(str(x) for x in esperado)
        erros.append(f"{rotulo}: esperava {alvos}, veio {t}")
        return False
    return True


def verificar_template(env: Envelope) -> Resultado:
    erros: List[str] = []
    avisos: List[str] = []

    # 1) envelope estrutural
    if env.contexto != "compliance":
        erros.append(f"contexto deve ser 'compliance', veio {env.contexto!r}")
    if env.dominio not in ("federal", "tce_estadual", "regimento_tenant"):
        erros.append(f"dominio inválido: {env.dominio!r} (S2: federal|tce_estadual|regimento_tenant)")
    if env.severidade not in ("bloqueante", "aviso"):
        erros.append(f"severidade inválida: {env.severidade!r} (bloqueante|aviso)")

    # 2) ambiente de tipos: parâmetros declarados + `ente` implícito
    tipos_env: dict = {"ente": Registro("Ente")}
    for nome, tnome in env.parametros.items():
        t = cat.resolver_tipo_nome(tnome)
        if t is None:
            erros.append(f"parametro {nome}: tipo desconhecido {tnome!r}")
        else:
            tipos_env[nome] = t

    # 3) expressões obrigatórias
    ok_aq = _checar_expr("aplica_quando", env.aplica_quando, tipos_env, {BOOLEANO}, erros)
    ok_ex = _checar_expr("exige", env.exige, tipos_env, {BOOLEANO}, erros)
    expressao_ok = ok_aq and ok_ex

    # 4) prazo (opcional). Ausente = contínua; bloco = deadline-bound; malformado = erro.
    if env.prazo is None:
        avisos.append("sem prazo: obrigação CONTÍNUA — não materializa instância (§22.7.7)")
    elif "__malformado__" in env.prazo:
        val = env.prazo["__malformado__"]
        erros.append(f"prazo malformado: {val!r} (esperava bloco com janela/a_partir_de)")
    else:
        ok_j = _checar_expr("prazo.janela", env.prazo.get("janela"), tipos_env, {DATA, INSTANTE}, erros)
        ok_a = _checar_expr("prazo.a_partir_de", env.prazo.get("a_partir_de"), tipos_env, {DATA, INSTANTE}, erros)
        expressao_ok = expressao_ok and ok_j and ok_a

    status = "VALIDA" if not erros else "INVALIDA"
    return Resultado(
        chave=env.template,
        status=status,
        erros=erros,
        avisos=avisos,
        expressao_ok=expressao_ok,
        registry_versao_ref=cat.CATALOGO_VERSAO if status == "VALIDA" else None,
    )
