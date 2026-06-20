"""
Catálogo do registry (§22.7 Eixo B / B3): tipos, enums, registros, builtins e
funções de relação que o type-checker do *save time* lê (Eixo A dec. 2/3).

Modelo B3 (§22.7.6): "código declara → catálogo versionado → type-checker lê →
regra carimba a versão". Aqui o catálogo é *dado em código* (o protótipo não tem
boot/DB); a VERSAO é o que cada regra carimba em `registry_versao_ref`.

Distinção mantida (§22.7.5):
  - BUILTINS         = biblioteca da DSL (hoje, proximo_dia_util, arredonda_cima…)
  - FUNCOES_RELACAO  = expostas pelo bounded context dono (populacao, publicado…)
  - PARAMETROS_TENANT= chave→tipo de parametro_tenant (binding B2, tipado por chave)

Conteúdo regulatório real (valores de prazo, feriados) é [GAP] de §22.7.5 — não
entra aqui; o catálogo modela só ASSINATURAS/TIPOS, cuja forma independe do valor.
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Dict, Optional, Tuple

from tipos import (
    Tipo, Conjunto, Enum, Registro,
    BOOLEANO, INTEIRO, TEXTO, DATA, INSTANTE, DURACAO, RACIONAL,
    COMPETENCIA, MAIORIA,
)

# Versão do catálogo que a regra carimba ao passar no type-check (B1 registry_versao_ref).
CATALOGO_VERSAO = "registry-v1@2026-06-20"


# ---------------------------------------------------------------------------
# Enums de domínio. Símbolos mantidos GLOBALMENTE únicos no protótipo para que o
# literal de enum (`resolucao`, `emenda_lom`) resolva o domínio sem ambiguidade.
# O catálogo real qualificaria o literal pelo domínio (TipoAtoLegislativo.resolucao)
# — simplificação consciente, anotada para não virar premissa.
# ---------------------------------------------------------------------------
ENUMS: Dict[str, set] = {
    "TipoAtoLegislativo": {"resolucao", "decreto_legislativo", "ato_mesa"},
    "Materia": {"emenda_lom", "rejeicao_veto", "cassacao"},
}


def dominio_do_literal(simbolo: str) -> Optional[str]:
    """Resolve o domínio enum de um literal (`resolucao` -> 'TipoAtoLegislativo')."""
    achados = [dom for dom, membros in ENUMS.items() if simbolo in membros]
    return achados[0] if len(achados) == 1 else None


# ---------------------------------------------------------------------------
# Registros (records) de domínio e seus campos tipados (acesso `ato.tipo`, T3/T4).
# ---------------------------------------------------------------------------
REGISTROS: Dict[str, Dict[str, Tipo]] = {
    "Ente": {},                                   # usado só como argumento nos templates
    "AtoDespesa": {},
    "AtoLegislativo": {"tipo": Enum("TipoAtoLegislativo")},
    "Votacao": {"materia": Enum("Materia")},
}


# Resolve um NOME de tipo (como escrito no envelope `parametros`) para um Tipo.
_TIPOS_NOMEADOS: Dict[str, Tipo] = {
    "Booleano": BOOLEANO, "Inteiro": INTEIRO, "Texto": TEXTO, "Data": DATA,
    "Instante": INSTANTE, "Duracao": DURACAO, "Racional": RACIONAL,
    "Competencia": COMPETENCIA, "Maioria": MAIORIA,
}


def resolver_tipo_nome(nome: str) -> Optional[Tipo]:
    if nome in _TIPOS_NOMEADOS:
        return _TIPOS_NOMEADOS[nome]
    if nome in REGISTROS:
        return Registro(nome)
    return None


# ---------------------------------------------------------------------------
# Assinaturas.
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class Assinatura:
    nome: str
    params: Tuple[Tipo, ...]
    retorno: Tipo
    dono: str           # bounded context dono ('builtin' p/ biblioteca da DSL)
    categoria: str      # 'builtin' | 'relacao'


def _b(nome, params, retorno) -> Assinatura:
    return Assinatura(nome, tuple(params), retorno, "builtin", "builtin")


# Builtins (biblioteca da DSL — §22.7.5 §8.1[a]).
BUILTINS: Dict[str, Assinatura] = {a.nome: a for a in [
    _b("hoje", [], DATA),
    _b("agora", [], INSTANTE),
    _b("fim_de", [COMPETENCIA], DATA),
    _b("proximo_dia_util", [DATA], DATA),
    _b("soma_dias_uteis", [DATA, INTEIRO], DATA),
    _b("arredonda_cima", [RACIONAL], INTEIRO),     # aceita Inteiro via coerção numérica
    _b("fracao", [INTEIRO, INTEIRO], RACIONAL),
    _b("prazo_vigente", [TEXTO, TEXTO, COMPETENCIA], DATA),  # lê calendário de domínio c/ override
    # parametro_tenant é tratado à parte: retorno depende da CHAVE (tipado por PARAMETROS_TENANT).
]}

# parametro_tenant(chave) -> tipo declarado por chave (binding B2 tipado).
PARAMETROS_TENANT: Dict[str, Tipo] = {
    "prazo_publicacao_ato_dias": INTEIRO,
}


def _r(nome, params, retorno, dono) -> Assinatura:
    return Assinatura(nome, tuple(params), retorno, dono, "relacao")


# Funções de relação (expostas pelo contexto dono — §22.7.5 §8.1[b]).
FUNCOES_RELACAO: Dict[str, Assinatura] = {a.nome: a for a in [
    _r("populacao", [Registro("Ente")], INTEIRO, "Cadastros/Ente"),
    _r("membros_da_casa", [Registro("Ente")], INTEIRO, "Cadastros/Ente"),
    _r("remessa_enviada", [Registro("Ente"), TEXTO, COMPETENCIA], BOOLEANO, "Remessa-tracking"),
    _r("publicada_no_portal", [Registro("AtoDespesa")], BOOLEANO, "Transparencia"),
    _r("data_registro_contabil", [Registro("AtoDespesa")], DATA, "Execucao/Transparencia"),
    _r("publicado", [Registro("AtoLegislativo")], BOOLEANO, "Atos Legislativos"),
    _r("data_promulgacao", [Registro("AtoLegislativo")], DATA, "Atos Legislativos"),
    _r("votos_favoraveis", [Registro("Votacao")], INTEIRO, "Plenario"),
]}


def buscar_assinatura(nome: str) -> Optional[Assinatura]:
    return BUILTINS.get(nome) or FUNCOES_RELACAO.get(nome)
