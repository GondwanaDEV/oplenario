"""O que entra no índice e como volta: o corte em TRECHOS (a unidade que a busca devolve) e a fusão dos dois rankings
da busca híbrida (termo exato + sentido) por Reciprocal Rank Fusion — sem pesos a calibrar."""

from __future__ import annotations

from typing import Literal

from oplenario_ia.armazem.porta import TranscricaoGuardada, TrechoIndice
from oplenario_ia.ata.redacao import blocos
from oplenario_ia.fronteira.contrato import DispositivosDaNorma

TipoDoc = Literal["proposicao", "transcricao", "dispositivo"]
TIPOS: tuple[str, ...] = ("proposicao", "transcricao", "dispositivo")
# a busca da tela (A.5) pede estes por padrao; dispositivo de norma e' do agente (B.5), que o pede explicitamente
TIPOS_PADRAO: tuple[str, ...] = ("proposicao", "transcricao")
TETO_TRECHO = 900  # caracteres: uma fala longa vira vários trechos (o vetor de um trecho enorme dilui o sentido)
RRF_K = 60


def _janelas(texto: str) -> list[str]:
    if len(texto) <= TETO_TRECHO:
        return [texto]
    saida, atual = [], ""
    for frase in texto.replace("? ", "?\n").replace(". ", ".\n").replace("! ", "!\n").split("\n"):
        if atual and len(atual) + len(frase) + 1 > TETO_TRECHO:
            saida.append(atual)
            atual = ""
        atual = f"{atual} {frase}".strip()
    return [*saida, atual] if atual else saida


def trechos_de_transcricao(t: TranscricaoGuardada) -> list[TrechoIndice]:
    """Um trecho por bloco de fala (quem falou, quando) — o que a busca mostra é 'Fulano disse isto, aos 12:05'."""
    saida: list[TrechoIndice] = []
    for b in blocos(t):
        for texto in _janelas(b.texto):
            meta = {
                "sessao-id": t.sessao_id,
                "segmento-id": t.segmento_id,
                "transcricao-id": t.id,
                "inicio": b.inicio,
                "fim": b.fim,
                "orador": b.orador,
            }
            saida.append(TrechoIndice(len(saida), texto, meta))
    return saida


def trechos_de_proposicao(ementa: str, autor_texto: str | None) -> list[TrechoIndice]:
    """A ementa (com a autoria) é o que a Casa escreve para dizer do que a matéria trata. Número, tipo e estado o core
    completa na hora de mostrar (a IA devolve o id; §22.3.4)."""
    texto = ementa.strip() + (f" Autoria: {autor_texto.strip()}." if autor_texto and autor_texto.strip() else "")
    return [TrechoIndice(i, j) for i, j in enumerate(_janelas(texto))]


def fundir(rankings: list[list[str]], k: int = RRF_K) -> dict[str, float]:
    """Reciprocal Rank Fusion: cada lista contribui 1/(k + posição) para cada chave que traz."""
    score: dict[str, float] = {}
    for lista in rankings:
        for pos, chave in enumerate(lista, start=1):
            score[chave] = score.get(chave, 0.0) + 1.0 / (k + pos)
    return score


def trechos_de_norma(n: DispositivosDaNorma) -> list[TrechoIndice]:
    """Um trecho por dispositivo da versão vigente (B.4b): o texto leva o título da norma e o rótulo de citação
    ("Regimento Interno, art. 12, § 1º: …"), para "art. 12" casar pela palavra exata e o assunto casar pelo sentido. O
    endereço e o rótulo vão na meta: é por eles que o agente lê e cita o dispositivo (B.5)."""
    saida: list[TrechoIndice] = []
    for d in n.dispositivos:
        if not d.texto.strip():
            continue
        for texto in _janelas(f"{n.titulo}, {d.rotulo}: {d.texto.strip()}"):
            meta = {
                "norma-id": n.norma_id,
                "versao-id": n.versao_id,
                "especie": n.especie,
                "titulo": n.titulo,
                "endereco": d.endereco,
                "rotulo": d.rotulo,
                "agrupador": d.agrupador,
                "consolidada-ate": n.consolidada_ate,
            }
            saida.append(TrechoIndice(len(saida), texto, meta))
    return saida
