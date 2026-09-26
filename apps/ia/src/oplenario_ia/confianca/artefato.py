"""O artefato de IA: SEMPRE um rascunho proposto, com citações conferidas e incerteza, até uma pessoa revisar.

O texto fica no satélite (a métrica e o registro não o carregam, B4). Publicar só depois da revisão humana (§16.8):
ata revisada antes de publicada, resumo cidadão revisado antes de publicado, texto de projeto é sempre sugestão.
"""

from __future__ import annotations

from difflib import SequenceMatcher
from typing import Literal

from pydantic import BaseModel

from oplenario_ia.confianca.citacao import Citacao
from oplenario_ia.confianca.incerteza import Incerteza
from oplenario_ia.confianca.registro import Desfecho

EstadoRevisao = Literal["proposto", "aprovado", "editado", "descartado"]


class Artefato(BaseModel):
    execucao_id: str
    ente_id: str
    operacao: str
    texto: str
    citacoes: list[Citacao]
    paragrafos_sem_fonte: list[int]
    incerteza: Incerteza
    vendor: str
    modelo: str
    contaminado: bool  # houve conteúdo de terceiro na entrada (§22.11.4)
    estado: EstadoRevisao = "proposto"
    revisor: str | None = None
    texto_final: str | None = None


class TransicaoInvalida(ValueError):
    pass


def revisar(
    artefato: Artefato, desfecho: Desfecho, revisor: str, texto_final: str | None = None
) -> tuple[Artefato, float]:
    """proposto → aprovado | editado | descartado, uma vez só. Devolve o artefato revisado e a proporção alterada."""
    if artefato.estado != "proposto":
        raise TransicaoInvalida(f"artefato já revisado ({artefato.estado}) — revisão nova é execução nova")
    if desfecho == "editado":
        if texto_final is None or texto_final == artefato.texto:
            raise TransicaoInvalida("'editado' exige o texto final, diferente do rascunho")
        proporcao = 1 - SequenceMatcher(None, artefato.texto, texto_final, autojunk=False).ratio()
    elif desfecho == "aprovado":
        if texto_final is not None and texto_final != artefato.texto:
            raise TransicaoInvalida("texto mudou: isso é 'editado', não 'aprovado'")
        texto_final, proporcao = artefato.texto, 0.0
    else:
        texto_final, proporcao = None, 1.0
    revisado = artefato.model_copy(update={"estado": desfecho, "revisor": revisor, "texto_final": texto_final})
    return revisado, round(proporcao, 4)


def pode_publicar(artefato: Artefato) -> bool:
    return artefato.estado in ("aprovado", "editado")
