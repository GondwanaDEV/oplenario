"""Transcreve UM segmento de gravação de uma sessão: ASR + diarização + Caminho C, com as falas da Mesa como âncora.

Os instantes das falas (absolutos, do core) viram segundos relativos ao início do segmento — o arquivo do OBS
começa em `segmento.iniciou_em`. Fala ainda aberta (sem encerramento registrado) vale até o fim do arquivo.
"""

from __future__ import annotations

from pathlib import Path

from oplenario_ia.armazem.porta import NovaTranscricao
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.fronteira.contrato import ContextoSessao
from oplenario_ia.transcricao.caminho_c import atribuir, cobertura
from oplenario_ia.transcricao.modelo import Palavra
from oplenario_ia.transcricao.porta import Diarizador, Transcritor

FIM_ABERTO = 10 * 24 * 3600.0  # fala sem encerramento: vale até o fim do arquivo


def palavras_do_segmento(ctx: ContextoSessao, segmento_id: str) -> list[Palavra]:
    seg = next((s for s in ctx.segmentos if s.id == segmento_id), None)
    if seg is None:
        raise ErroIA(Categoria.ENTRADA, "o segmento não está no contexto da sessão", retentavel=False, vendor="core")
    base = seg.iniciou_em
    palavras = []
    for f in ctx.falas:
        ini = (f.iniciou_em - base).total_seconds()
        fim = (f.encerrou_em - base).total_seconds() if f.encerrou_em else FIM_ABERTO
        if fim > 0:
            palavras.append(Palavra(max(0.0, ini), fim, f.orador_id, f.orador_nome))
    return palavras


def transcrever_segmento(
    ctx: ContextoSessao,
    ente_id: str,
    segmento_id: str,
    arquivo: Path,
    transcritor: Transcritor,
    diarizador: Diarizador | None,
    idioma: str = "pt",
) -> NovaTranscricao:
    palavras = palavras_do_segmento(ctx, segmento_id)
    frases = transcritor.transcrever(arquivo, idioma)
    vozes = diarizador.diarizar(arquivo) if diarizador is not None else []
    trechos = atribuir(frases, vozes, palavras)
    return NovaTranscricao(
        ente_id=ente_id,
        sessao_id=ctx.sessao.id,
        segmento_id=segmento_id,
        idioma=idioma,
        duracao_s=round(max((t.fim for t in trechos), default=0.0), 2),
        modelo_asr=transcritor.modelo,
        modelo_diarizacao=diarizador.modelo if diarizador is not None else None,
        cobertura=cobertura(trechos),
        trechos=trechos,
    )
