"""
Avaliador executável + loop de runtime do motor (§22.7.7): materializa → avalia →
monitora → audita. Implementa as DUAS decisões centrais do Eixo de runtime:

  - Dois sabores de obrigação: COM prazo materializa instância (`prazo_dominio_ativo`)
    com relógio; CONTÍNUA (sem bloco prazo) não materializa — só audita.
  - `compliance_avaliacao` APPEND-ONLY = a prova de compliance (Invariante 10):
    cada avaliação carimba regra + versão do catálogo.

Ciclo da obrigação = enum FIXO em código (não template): pendente → cumprida |
vencida | dispensada | cancelada (§22.7.7, como emendas §22.4 eixo D).

Relógio é injetado (determinístico, como instante em §22.6) — sem Date.now().
Valores regulatórios (datas de prazo, feriados) são FIXTURES ilustrativos: o [GAP]
de §22.7.5 fica como GAP; aqui só exercita a forma, que independe do valor.
"""
from __future__ import annotations
from dataclasses import dataclass, field
from datetime import date, timedelta
from fractions import Fraction
from typing import Any, Dict, List, Optional, Tuple

import catalogo as cat
from nucleo import (
    Lit, ConjuntoLit, Ident, Campo, Chamada, BinOp, UnOp, parse_expr, Envelope,
)


class ErroRuntime(Exception):
    pass


# ===========================================================================
# Valores de domínio (fixtures) e tabelas-espelho do schema
# ===========================================================================
@dataclass(frozen=True)
class Competencia:
    ano: int
    mes: int

    def chave(self) -> str:
        return f"{self.ano:04d}-{self.mes:02d}"


@dataclass
class Ente:
    id: str
    populacao: int
    membros: int


@dataclass
class AtoDespesa:
    id: str
    registro_contabil: date
    publicada: bool


@dataclass
class AtoLegislativo:
    id: str
    tipo: str
    publicado: bool
    promulgacao: date


@dataclass
class Votacao:
    id: str
    materia: str
    favoraveis: int


@dataclass
class PrazoVigente:
    """Espelho de prazo_dominio_vigente (Eixo B / B4): referência regulatória."""
    jurisdicao: str
    tipo_prazo: str
    chave_periodo: str
    data_limite: date
    fonte: str
    vigente: bool


@dataclass
class Estado:
    feriados: set = field(default_factory=set)
    prazos: List[PrazoVigente] = field(default_factory=list)
    remessas: set = field(default_factory=set)   # (ente_id, sistema, competencia_key)
    bindings: Dict[str, dict] = field(default_factory=dict)  # ente_id -> {param: valor}


# ---- tabelas de runtime (espelho §22.7.7) ----
@dataclass
class ObrigacaoAtiva:
    """Espelho de prazo_dominio_ativo (polimórfico): a obrigação COM prazo."""
    id: str
    ente_id: str
    template_chave: str
    objeto_tipo: str
    objeto_id: str
    vence_em: Optional[date]
    prazo_fonte_ref: Optional[str]
    estado: str                       # enum fixo (pendente|cumprida|vencida|dispensada|cancelada)
    cumprida_em: Optional[date] = None


@dataclass(frozen=True)
class Avaliacao:
    """Espelho de compliance_avaliacao: APPEND-ONLY (sem update/delete)."""
    id: str
    ente_id: str
    obrigacao_id: Optional[str]
    template_chave: str
    registry_versao_ref: str
    veredito: str                     # conforme | nao_conforme | inaplicavel
    severidade: str
    occurred_at: date
    origem_avaliacao: str             # evento | sweep | sob_demanda
    detalhe: str = ""


# ===========================================================================
# Helpers de calendário
# ===========================================================================
def _eh_util(d: date, feriados: set) -> bool:
    return d.weekday() < 5 and d not in feriados


def _prox_dia_util(d: date, feriados: set) -> date:
    x = d + timedelta(days=1)
    while not _eh_util(x, feriados):
        x += timedelta(days=1)
    return x


def _soma_dias_uteis(d: date, n: int, feriados: set) -> date:
    x, passos = d, 0
    while passos < n:
        x += timedelta(days=1)
        if _eh_util(x, feriados):
            passos += 1
    return x


def _fim_de(comp: Competencia) -> date:
    prox = date(comp.ano + 1, 1, 1) if comp.mes == 12 else date(comp.ano, comp.mes + 1, 1)
    return prox - timedelta(days=1)


# ===========================================================================
# Avaliador de expressão (tree-walk)
# ===========================================================================
class Avaliador:
    def __init__(self, estado: Estado, agora: date):
        self.estado = estado
        self.agora = agora
        self.ultima_fonte_prazo: Optional[str] = None   # capturado p/ prazo_fonte_ref (S1/S3)

    def avaliar(self, no, amb: dict) -> Any:
        if isinstance(no, Lit):
            return no.valor
        if isinstance(no, Ident):
            if no.nome in amb:
                return amb[no.nome]
            if cat.dominio_do_literal(no.nome):
                return no.nome                     # literal de enum = seu símbolo
            raise ErroRuntime(f"identificador sem valor em runtime: {no.nome!r}")
        if isinstance(no, Campo):
            obj = self.avaliar(no.obj, amb)
            return getattr(obj, no.campo)
        if isinstance(no, ConjuntoLit):
            return frozenset(self.avaliar(e, amb) for e in no.elementos)
        if isinstance(no, Chamada):
            return self._chamada(no, amb)
        if isinstance(no, BinOp):
            return self._binop(no, amb)
        if isinstance(no, UnOp):
            v = self.avaliar(no.operando, amb)
            if no.op == "nao":
                return not v
            raise ErroRuntime(f"operador unário desconhecido: {no.op}")
        raise ErroRuntime(f"nó desconhecido: {no!r}")

    def _binop(self, no: BinOp, amb: dict) -> Any:
        op = no.op
        a = self.avaliar(no.esq, amb)
        b = self.avaliar(no.dir, amb)
        if op == "e":
            return bool(a) and bool(b)
        if op == "ou":
            return bool(a) or bool(b)
        if op == "in":
            return a in b
        if op == ">":
            return a > b
        if op == ">=":
            return a >= b
        if op == "<":
            return a < b
        if op == "<=":
            return a <= b
        if op == "==":
            return a == b
        if op == "!=":
            return a != b
        if op == "*":
            return a * b
        if op == "+":
            return a + b
        if op == "-":
            return a - b
        raise ErroRuntime(f"operador binário desconhecido: {op}")

    def _chamada(self, no: Chamada, amb: dict) -> Any:
        args = [self.avaliar(a, amb) for a in no.args]
        nome = no.nome
        fer = self.estado.feriados

        # builtins
        if nome == "hoje" or nome == "agora":
            return self.agora
        if nome == "fim_de":
            return _fim_de(args[0])
        if nome == "proximo_dia_util":
            return _prox_dia_util(args[0], fer)
        if nome == "soma_dias_uteis":
            return _soma_dias_uteis(args[0], args[1], fer)
        if nome == "arredonda_cima":
            x = args[0]
            return -((-x.numerator) // x.denominator) if isinstance(x, Fraction) else int(x)
        if nome == "fracao":
            return Fraction(args[0], args[1])      # EXATO — nunca float (armadilha T4)
        if nome == "prazo_vigente":
            return self._prazo_vigente(args[0], args[1], args[2])
        if nome == "parametro_tenant":
            ente = amb["ente"]
            binding = self.estado.bindings.get(ente.id, {})
            if args[0] not in binding:
                raise ErroRuntime(f"parametro_tenant: {args[0]!r} não configurado p/ {ente.id}")
            return binding[args[0]]

        # funções de relação
        if nome == "populacao":
            return args[0].populacao
        if nome == "membros_da_casa":
            return args[0].membros
        if nome == "remessa_enviada":
            ente, sistema, comp = args
            return (ente.id, sistema, comp.chave()) in self.estado.remessas
        if nome == "publicada_no_portal":
            return args[0].publicada
        if nome == "data_registro_contabil":
            return args[0].registro_contabil
        if nome == "publicado":
            return args[0].publicado
        if nome == "data_promulgacao":
            return args[0].promulgacao
        if nome == "votos_favoraveis":
            return args[0].favoraveis

        raise ErroRuntime(f"função sem implementação de runtime: {nome!r}")

    def _prazo_vigente(self, jurisdicao: str, tipo: str, comp: Competencia) -> date:
        chave = comp.chave()
        achados = [p for p in self.estado.prazos
                   if p.jurisdicao == jurisdicao and p.tipo_prazo == tipo
                   and p.chave_periodo == chave and p.vigente]
        if not achados:
            raise ErroRuntime(f"prazo_vigente: sem prazo p/ {jurisdicao}/{tipo}/{chave} [GAP de conteúdo]")
        self.ultima_fonte_prazo = achados[0].fonte
        return achados[0].data_limite


# ===========================================================================
# Motor: o loop materializa → avalia → monitora → audita
# ===========================================================================
LIMIAR_A_VENCER_DIAS = 5    # "a vencer" = derivação de leitura, NÃO estado persistido (§22.7.7)


class Motor:
    def __init__(self, estado: Estado, agora: date):
        self.estado = estado
        self.agora = agora
        self.av = Avaliador(estado, agora)
        self.obrigacoes: Dict[Tuple[str, str, str, str], ObrigacaoAtiva] = {}
        self.avaliacoes: List[Avaliacao] = []     # append-only
        self.eventos: List[str] = []
        self._contexto: Dict[str, Tuple[Envelope, dict]] = {}  # obrig_id -> (regra, amb) p/ resweep
        self._seq = 0

    def _id(self, prefixo: str) -> str:
        self._seq += 1
        return f"{prefixo}-{self._seq}"

    def _emitir(self, evt: str) -> None:
        self.eventos.append(evt)

    def avaliar_regra(self, regra: Envelope, registry_versao_ref: str, amb: dict,
                      objeto_tipo: str, objeto_id: str, origem: str = "evento") -> Avaliacao:
        """Um passo do loop para UMA regra contra UM objeto. Idempotente na materialização."""
        ente: Ente = amb["ente"]

        # --- avalia: aplica_quando primeiro (gatilho condicional) ---
        no_aq = parse_expr(regra.aplica_quando)
        if not self.av.avaliar(no_aq, amb):
            return self._auditar(ente.id, None, regra.template, registry_versao_ref,
                                 "inaplicavel", regra.severidade, origem,
                                 "aplica_quando=falso")

        conforme = bool(self.av.avaliar(parse_expr(regra.exige), amb))
        veredito = "conforme" if conforme else "nao_conforme"

        tem_prazo = isinstance(regra.prazo, dict) and "__malformado__" not in regra.prazo
        if not tem_prazo:
            # --- sabor CONTÍNUO: não materializa instância, só audita (§22.7.7) ---
            return self._auditar(ente.id, None, regra.template, registry_versao_ref,
                                 veredito, regra.severidade, origem,
                                 "regra contínua (sem prazo)")

        # --- sabor DEADLINE-BOUND: materializa/atualiza obrigação ---
        self.av.ultima_fonte_prazo = None
        vence_em = self.av.avaliar(parse_expr(regra.prazo["janela"]), amb)
        fonte = self.av.ultima_fonte_prazo

        chave = (ente.id, regra.template, objeto_tipo, objeto_id)
        obrig = self.obrigacoes.get(chave)
        if obrig is None:
            obrig = ObrigacaoAtiva(
                id=self._id("obr"), ente_id=ente.id, template_chave=regra.template,
                objeto_tipo=objeto_tipo, objeto_id=objeto_id,
                vence_em=vence_em, prazo_fonte_ref=fonte, estado="pendente",
            )
            self.obrigacoes[chave] = obrig
            self._contexto[obrig.id] = (regra, amb)
            self._emitir(f"ObrigacaoComplianceMaterializada({obrig.id}, {regra.template}, vence={vence_em})")
        else:
            # re-stamp do prazo só enquanto ABERTA (pendente). cumprida/vencida não movem (S3/§22.7.7).
            if obrig.estado == "pendente" and obrig.vence_em != vence_em:
                self._emitir(f"ObrigacaoComplianceReprazada({obrig.id}: {obrig.vence_em} -> {vence_em}, fonte={fonte})")
                obrig.vence_em = vence_em
                obrig.prazo_fonte_ref = fonte

        # transição de estado (enum fixo)
        if conforme:
            if obrig.estado != "cumprida":
                obrig.estado = "cumprida"
                obrig.cumprida_em = self.agora
                self._emitir(f"ObrigacaoComplianceCumprida({obrig.id})")
        else:
            if obrig.estado == "pendente" and self.agora > obrig.vence_em:
                obrig.estado = "vencida"
                self._emitir(f"ObrigacaoComplianceVencida({obrig.id})")

        return self._auditar(ente.id, obrig.id, regra.template, registry_versao_ref,
                             veredito, regra.severidade, origem, f"obrigacao={obrig.estado}")

    def _auditar(self, ente_id, obrig_id, template, registry_versao_ref,
                 veredito, severidade, origem, detalhe) -> Avaliacao:
        av = Avaliacao(
            id=self._id("aval"), ente_id=ente_id, obrigacao_id=obrig_id,
            template_chave=template, registry_versao_ref=registry_versao_ref,
            veredito=veredito, severidade=severidade or "aviso",
            occurred_at=self.agora, origem_avaliacao=origem, detalhe=detalhe,
        )
        self.avaliacoes.append(av)                # append-only
        self._emitir(f"ObrigacaoComplianceAvaliada({av.id}, {template}, {veredito})")
        return av

    # ---- monitoramento: derivação de LEITURA sobre vence_em (não persiste) ----
    def monitorar(self) -> List[dict]:
        rel = []
        for o in self.obrigacoes.values():
            if o.estado != "pendente" or o.vence_em is None:
                situacao = o.estado
            else:
                dias = (o.vence_em - self.agora).days
                if dias < 0:
                    situacao = "vencida(derivada)"
                elif dias <= LIMIAR_A_VENCER_DIAS:
                    situacao = f"a_vencer({dias}d)"
                else:
                    situacao = f"no_prazo({dias}d)"
            rel.append({"obrigacao": o.id, "template": o.template_chave,
                        "vence_em": o.vence_em, "estado": o.estado, "situacao": situacao})
        return rel

    # ---- S3: Ofício Circular desliza o prazo → re-sweep das obrigações abertas ----
    def aplicar_circular(self, jurisdicao: str, tipo_prazo: str, chave_periodo: str,
                         nova_data: date, fonte: str) -> None:
        for p in self.estado.prazos:
            if (p.jurisdicao == jurisdicao and p.tipo_prazo == tipo_prazo
                    and p.chave_periodo == chave_periodo):
                p.vigente = False
        self.estado.prazos.append(PrazoVigente(jurisdicao, tipo_prazo, chave_periodo,
                                               nova_data, fonte, vigente=True))
        self._emitir(f"PrazoDominioDeslizado({jurisdicao}/{tipo_prazo}/{chave_periodo} -> {nova_data}, {fonte})")
        # re-sweep: reavalia obrigações abertas afetadas (origem=sweep)
        for chave, obrig in list(self.obrigacoes.items()):
            if obrig.estado == "pendente" and obrig.id in self._contexto:
                regra, amb = self._contexto[obrig.id]
                self.avaliar_regra(regra, cat.CATALOGO_VERSAO, amb,
                                   obrig.objeto_tipo, obrig.objeto_id, origem="sweep")
