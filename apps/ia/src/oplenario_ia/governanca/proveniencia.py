"""Proveniência + gate determinístico (B1). Fail-closed: sem classificação explícita, a peça não cruza."""

from __future__ import annotations

from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, Field


class Sigilo(StrEnum):
    PUBLICO = "publico"
    RESTRITO = "restrito"  # sigiloso / e-SIC / dado pessoal de terceiro
    SECRETO = "secreto"  # sessão secreta, conteúdo classificado


class Proveniencia(BaseModel):
    """De onde a peça veio e o sigilo dela — dado do domínio, carimbado pelo core (§22.5 eixo E).

    `terceiro` marca conteúdo que não foi escrito pela Casa nem pela pessoa (e-SIC, participação cidadã, e-mail, PDF
    enviado, fala transcrita): vai delimitado ao modelo e contamina a execução (§22.11.4, defesa contra instrução
    escondida).
    """

    origem: str
    sigilo: Sigilo | None = None
    voto_secreto: bool = False
    terceiro: bool = False


class Fato(BaseModel):
    """Um valor numérico que uma fonte ESTRUTURADA afirma (o total de votos sim de uma votação), com o PAPEL dele.
    `nomes` é como a prosa chama o papel (`sim`, `favor`, `favoraveis`; a comparação passa pela mesma normalização do
    resto, então acento e caixa não importam). `lado`: `depois` = o nome vem depois do número ("9 votos sim");
    `qualquer` = pode vir antes ou depois ("eram necessários 9 votos", "9 votos necessários" — o quórum)."""

    valor: int
    nomes: tuple[str, ...]
    lado: Literal["depois", "qualquer"] = "depois"


class Fonte(BaseModel):
    """A peça é uma FONTE citável: um dispositivo de norma, um trecho de transcrição, uma proposição (§22.11.7).

    `id` é o endereço estável (URN LexML + fragmento, `transcricao:<sessao>#<segmento>`, …) — é o que o modelo cita e
    o que a Camada de Confiança confere. `versao` diz qual texto foi lido (a norma consolidada até dd/mm).
    """

    id: str = Field(min_length=1, pattern=r"^[^|\]\s\"]+$")
    rotulo: str
    versao: str | None = None
    # Fonte ESTRUTURADA (um fato do core, não um texto escrito por gente): a conferência vai além do trecho literal e
    # confere os números da afirmação que a cita contra o texto da fonte e contra `fatos` (confianca/numeros.py).
    estruturada: bool = False
    fatos: list[Fato] = []
    livres: list[
        int
    ] = []  # números que identificam a coisa e se citam sem papel (o da matéria: 8 e 2026 em PL 008/2026)
    pares: list[tuple[int, int]] = []  # referências a/b que a fonte confirma ("8/2026"): só passam como par
    unanime: bool = (
        False  # o registro diz que ninguém votou contra nem se absteve ("unanimidade" só confere se verdade)
    )


class Peca(BaseModel):
    texto: str
    proveniencia: Proveniencia | None = None
    fonte: Fonte | None = None


def liberada(peca: Peca) -> bool:
    """Só cruza a peça COMPROVADAMENTE pública e sem voto secreto. Sem proveniência = bloqueada."""
    p = peca.proveniencia
    return p is not None and p.sigilo is Sigilo.PUBLICO and not p.voto_secreto


def motivo_bloqueio(peca: Peca) -> str | None:
    """Por que a peça foi bloqueada (vai para a auditoria — só o motivo, nunca o texto). None se liberada."""
    p = peca.proveniencia
    if p is None:
        return "proveniencia ausente (fail-closed)"
    if p.voto_secreto:
        return "voto secreto"
    if p.sigilo is Sigilo.SECRETO:
        return "sessao/conteudo secreto"
    if p.sigilo is Sigilo.RESTRITO:
        return "conteudo restrito (sigiloso/e-SIC)"
    if p.sigilo is None:
        return "sigilo nao classificado (fail-closed)"
    return None


def gate(pecas: list[Peca]) -> tuple[list[Peca], list[Peca]]:
    """Particiona em (liberadas, bloqueadas). A política sobre as bloqueadas (degradar) é do filtro (B3)."""
    return [p for p in pecas if liberada(p)], [p for p in pecas if not liberada(p)]
