"""O trabalhador da Faixa A (ADR-0008): puxa o feed do core, transcreve cada gravação vinculada e devolve o
resultado à caixa de entrada do core.

Dois tipos de trabalho na fila própria do satélite:
- `transcrever` (um por evento `GravacaoVinculada`): contexto + download + ASR + diarização + Caminho C; guarda a
  transcrição e enfileira a notificação NA MESMA operação (reiniciar no meio não transcreve de novo).
- `notificar`: entrega `TranscricaoConcluida`/`TranscricaoFalhou` ao core; tenta até o core aceitar.

Falhas seguem o §22.3.5: infraestrutura/sobrecarga tentam de novo com espera crescente (limite por tipo); entrada
falha na hora; sigilo (403 do core) DESCARTA o trabalho sem avisar ninguém — fail-closed, não é erro.
"""

from __future__ import annotations

import logging
import tempfile
import time
import uuid
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from oplenario_ia.armazem.porta import Armazem, NovoTrabalho, Trabalho, TranscricaoGuardada
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.fronteira.cliente import ClienteCore, Sigiloso
from oplenario_ia.fronteira.contrato import (
    EventoParaCore,
    GravacaoVinculadaV1,
    TranscricaoConcluidaV1,
    TranscricaoFalhouV1,
)
from oplenario_ia.transcricao.porta import Diarizador, Transcritor
from oplenario_ia.transcricao.servico import transcrever_segmento

log = logging.getLogger("oplenario_ia.trabalhador")

MAX_TENTATIVAS = {"transcrever": 5, "notificar": 20}
ESPERA_BASE_S = 30.0
ESPERA_MAX_S = 1800.0


def espera(tentativa: int) -> timedelta:
    return timedelta(seconds=min(ESPERA_BASE_S * 2**tentativa, ESPERA_MAX_S))


class Trabalhador:
    def __init__(
        self,
        core: ClienteCore,
        armazem: Armazem,
        transcritor: Transcritor,
        diarizador: Diarizador | None,
        *,
        idioma: str = "pt",
        agora: Callable[[], datetime] = lambda: datetime.now(UTC),
        dir_temp: Path | None = None,
    ) -> None:
        self.core = core
        self.armazem = armazem
        self.transcritor = transcritor
        self.diarizador = diarizador
        self.idioma = idioma
        self.agora = agora
        self.dir_temp = dir_temp

    # ---------- feed ----------

    def puxar_feed(self) -> int:
        feed = self.core.feed(self.armazem.cursor())
        novos = []
        for ev in feed.eventos:
            if (ev.tipo, ev.versao) == ("GravacaoVinculada", 1):
                novos.append(
                    NovoTrabalho("transcrever", ev.chave, ev.ente_id, ev.payload | {"correlation-id": ev.chave})
                )
            else:
                log.info("evento de integração ignorado (tipo/versão desconhecidos): %s v%s", ev.tipo, ev.versao)
        return self.armazem.registrar_feed(novos, feed.proximo)

    # ---------- fila ----------

    def processar_um(self) -> bool:
        t = self.armazem.proximo(self.agora())
        if t is None:
            return False
        try:
            if t.tipo == "transcrever":
                self._transcrever(t)
            elif t.tipo == "notificar":
                self.core.enviar(EventoParaCore.model_validate(t.payload))
                self.armazem.concluir(t.id)
            else:
                self.armazem.desistir(t.id, f"tipo de trabalho desconhecido: {t.tipo}")
        except Sigiloso:
            log.info("trabalho %s descartado: conteúdo sigiloso (o core recusou)", t.id)
            self.armazem.concluir(t.id, estado="descartado")
        except ErroIA as e:
            self._falhou(t, e)
        except Exception as e:  # defeito nosso: registra e segue a fila, nunca derruba o trabalhador
            log.exception("trabalho %s quebrou", t.id)
            self._falhou(t, ErroIA(Categoria.INFRAESTRUTURA, f"erro interno: {type(e).__name__}", retentavel=True))
        return True

    def _falhou(self, t: Trabalho, e: ErroIA) -> None:
        erro = f"{e.categoria}: {e.detalhe}"
        # o aviso ao core insiste em QUALQUER falha de infraestrutura (credencial rotacionada, integração desligada
        # para manutenção): sem ele o core nunca sabe que a transcrição existe. Entrada (contrato) desiste.
        insiste = e.retentavel or (t.tipo == "notificar" and e.categoria is Categoria.INFRAESTRUTURA)
        if insiste and t.tentativas + 1 < MAX_TENTATIVAS.get(t.tipo, 1):
            self.armazem.adiar(t.id, erro, self.agora() + espera(t.tentativas))
            return
        seguintes = [self._aviso_de_falha(t, e)] if t.tipo == "transcrever" else []
        self.armazem.desistir(t.id, erro, seguintes)

    # ---------- transcrever ----------

    def _transcrever(self, t: Trabalho) -> None:
        ev = GravacaoVinculadaV1.model_validate(t.payload)
        ctx = self.core.contexto(ev.contexto_uri)
        with tempfile.TemporaryDirectory(dir=self.dir_temp) as d:
            arquivo = Path(d) / "gravacao"
            self.core.baixar(ev.conteudo_uri, arquivo)
            nova = transcrever_segmento(
                ctx, t.ente_id, ev.segmento_id, arquivo, self.transcritor, self.diarizador, self.idioma
            )
        correlacao = str(t.payload.get("correlation-id") or t.chave)
        self.armazem.concluir_transcricao(t.id, nova, lambda g: self._aviso_de_conclusao(g, correlacao))

    def _aviso_de_conclusao(self, g: TranscricaoGuardada, correlacao: str) -> NovoTrabalho:
        chave = f"TranscricaoConcluida:v1:{g.id}"
        payload = TranscricaoConcluidaV1(
            sessao_id=g.sessao_id,
            segmento_id=g.segmento_id,
            transcricao_id=g.id,
            versao_transcricao=g.versao,
            idioma=g.idioma,
            duracao_s=g.duracao_s,
            n_trechos=len(g.trechos),
            cobertura_atribuida=g.cobertura,
            modelo_asr=g.modelo_asr,
            modelo_diarizacao=g.modelo_diarizacao,
        )
        return self._notificacao(
            "TranscricaoConcluida", chave, g.ente_id, correlacao, payload.model_dump(by_alias=True)
        )

    def _aviso_de_falha(self, t: Trabalho, e: ErroIA) -> NovoTrabalho:
        ev = GravacaoVinculadaV1.model_validate(t.payload)
        chave = f"TranscricaoFalhou:v1:{ev.segmento_id}:{uuid.uuid4()}"
        payload = TranscricaoFalhouV1(
            sessao_id=ev.sessao_id,
            segmento_id=ev.segmento_id,
            categoria=e.categoria.value,
            detalhe=e.detalhe[:2000],
            retentavel=e.retentavel,
        )
        correlacao = str(t.payload.get("correlation-id") or t.chave)
        return self._notificacao("TranscricaoFalhou", chave, t.ente_id, correlacao, payload.model_dump(by_alias=True))

    def _notificacao(
        self, tipo: str, chave: str, ente_id: str, correlacao: str, payload: dict[str, Any]
    ) -> NovoTrabalho:
        ev = EventoParaCore(
            tipo=tipo,
            chave=chave,
            ente_id=ente_id,
            correlation_id=correlacao,
            ocorrido_em=self.agora(),
            payload=payload,
        )
        return NovoTrabalho("notificar", chave, ente_id, ev.model_dump(mode="json", by_alias=True))

    # ---------- laço ----------

    def ciclo(self) -> int:
        """Uma volta: puxa o feed (falha de rede não impede processar o que já está na fila) e esvazia a fila."""
        try:
            self.puxar_feed()
        except (ErroIA, Sigiloso) as e:
            log.warning("feed indisponível: %s", e)
        n = 0
        while self.processar_um():
            n += 1
        return n

    def rodar(self, intervalo_s: float, parar: Callable[[], bool] = lambda: False) -> None:
        while not parar():
            self.ciclo()
            time.sleep(intervalo_s)
