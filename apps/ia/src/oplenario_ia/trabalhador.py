"""O trabalhador da Faixa A (ADR-0008): puxa o feed do core, transcreve cada gravação vinculada, redige o rascunho
da ata quando a secretaria pede, e devolve o resultado à caixa de entrada do core.

Três tipos de trabalho na fila própria do satélite:
- `transcrever` (um por evento `GravacaoVinculada`): contexto + download + ASR + diarização + Caminho C; guarda a
  transcrição e enfileira a notificação NA MESMA operação (reiniciar no meio não transcreve de novo).
- `redigir_ata` (um por evento `AtaSolicitada`, A.6b): contexto + as transcrições guardadas aqui -> núcleo (filtro,
  porta, citação conferida, incerteza, registro); guarda o rascunho e enfileira `AtaRascunhoPronta` junto.
- `registrar_revisao` (um por `AtaRevisadaEPublicada`, A.6c): lê a ata publicada, confere o hash e mede quanto a pessoa
  mudou do rascunho — vira `RevisaoHumana` no registro (a taxa de aceitação da ata por Casa). Uma vez por versão.
- `notificar`: entrega os eventos ao core (`Transcricao*`, `Ata*`); tenta até o core aceitar.

Falhas seguem o §22.3.5: infraestrutura/sobrecarga tentam de novo com espera crescente (limite por tipo); entrada
falha na hora; sigilo (403 do core) DESCARTA o trabalho sem avisar ninguém — fail-closed, não é erro.
"""

from __future__ import annotations

import hashlib
import logging
import tempfile
import time
import uuid
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from oplenario_ia.armazem.porta import (
    Armazem,
    NovoRascunho,
    NovoTrabalho,
    RascunhoGuardado,
    RevisaoAta,
    Trabalho,
    TranscricaoGuardada,
)
from oplenario_ia.ata.redacao import OPERACAO as ATA_REDIGIR
from oplenario_ia.ata.redacao import PROMPT_VERSAO, pedido_de_ata, pontos_a_confirmar, texto_limpo
from oplenario_ia.confianca.artefato import proporcao_alterada
from oplenario_ia.confianca.indisponivel import Indisponivel
from oplenario_ia.confianca.registro import Desfecho
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.fronteira.cliente import ClienteCore, Sigiloso
from oplenario_ia.fronteira.contrato import (
    AtaFalhouV1,
    AtaRascunhoProntaV1,
    AtaRevisadaEPublicadaV1,
    AtaSolicitadaV1,
    EventoParaCore,
    GravacaoVinculadaV1,
    TranscricaoConcluidaV1,
    TranscricaoFalhouV1,
)
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.transcricao.porta import Diarizador, Transcritor
from oplenario_ia.transcricao.servico import transcrever_segmento

log = logging.getLogger("oplenario_ia.trabalhador")

MAX_TENTATIVAS = {"transcrever": 5, "redigir_ata": 5, "registrar_revisao": 10, "notificar": 20}
CATEGORIAS_DE_FALHA = {"infraestrutura", "sobrecarga", "entrada", "modelo"}  # 5 e 6 nunca viajam como falha
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
        nucleo: Nucleo | None = None,
        agora: Callable[[], datetime] = lambda: datetime.now(UTC),
        dir_temp: Path | None = None,
    ) -> None:
        self.core = core
        self.armazem = armazem
        self.transcritor = transcritor
        self.diarizador = diarizador
        self.idioma = idioma
        self.nucleo = nucleo
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
            elif (ev.tipo, ev.versao) == ("AtaRevisadaEPublicada", 1):
                novos.append(NovoTrabalho("registrar_revisao", ev.chave, ev.ente_id, ev.payload))
            elif (ev.tipo, ev.versao) == ("AtaSolicitada", 1):
                novos.append(
                    NovoTrabalho("redigir_ata", ev.chave, ev.ente_id, ev.payload | {"correlation-id": ev.chave})
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
            elif t.tipo == "redigir_ata":
                self._redigir_ata(t)
            elif t.tipo == "registrar_revisao":
                self._registrar_revisao(t)
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
        seguintes = (
            [self._aviso_de_falha(t, e)]
            if t.tipo == "transcrever"
            else [self._aviso_de_falha_da_ata(t, e)]
            if t.tipo == "redigir_ata"
            else []
        )
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

    # ---------- redigir a ata (A.6b) ----------

    def _redigir_ata(self, t: Trabalho) -> None:
        ev = AtaSolicitadaV1.model_validate(t.payload)
        if self.nucleo is None:
            raise ErroIA(Categoria.INFRAESTRUTURA, "o núcleo de IA não está configurado", retentavel=False)
        ctx = self.core.contexto(ev.contexto_uri)
        transcricoes = self.armazem.transcricoes_da_sessao(t.ente_id, ev.sessao_id)
        if not transcricoes:
            raise ErroIA(
                Categoria.ENTRADA, "a sessão ainda não tem transcrição concluída para a IA redigir", retentavel=False
            )
        correlacao = str(t.payload.get("correlation-id") or t.chave)
        r = self.nucleo.executar(pedido_de_ata(ctx, transcricoes, t.ente_id, correlacao), "por_paragrafo")
        if isinstance(r, Indisponivel):
            # o núcleo já registrou a execução; aqui só decide se tenta de novo (categoria) ou avisa o core
            raise ErroIA(r.categoria or Categoria.ENTRADA, r.mensagem, retentavel=r.retentavel)
        novo = NovoRascunho(
            ente_id=t.ente_id,
            sessao_id=ev.sessao_id,
            solicitacao_id=ev.solicitacao_id,
            execucao_id=r.execucao_id,
            texto=r.texto,
            citacoes=[c.model_dump(mode="json") for c in r.citacoes],
            paragrafos_sem_fonte=list(r.paragrafos_sem_fonte),
            incerteza=r.incerteza.model_dump(mode="json"),
            vendor=r.vendor,
            modelo=r.modelo,
            prompt_versao=PROMPT_VERSAO,
            transcricoes=[x.id for x in transcricoes],
        )
        self.armazem.concluir_rascunho(t.id, novo, lambda g: self._aviso_ata_pronta(g, correlacao))

    # ---------- a revisão humana (A.6c) ----------

    def _registrar_revisao(self, t: Trabalho) -> None:
        ev = AtaRevisadaEPublicadaV1.model_validate(t.payload)
        g = self.armazem.rascunho(ev.rascunho_id)
        if g is None or g.ente_id != t.ente_id:
            raise ErroIA(Categoria.ENTRADA, "rascunho desconhecido neste satélite", retentavel=False)
        ata = self.core.ata_publicada(ev.conteudo_uri)
        obtido = "sha256:" + hashlib.sha256(ata.texto.encode("utf-8")).hexdigest()
        if obtido != ev.conteudo_sha256 or ata.conteudo_sha256 != ev.conteudo_sha256:
            # o texto lido não é o que foi congelado: nunca mede sobre texto errado (tenta de novo mais tarde)
            raise ErroIA(Categoria.INFRAESTRUTURA, "ata: hash do texto não confere com o publicado", retentavel=True)
        # mede contra o texto LIMPO (o que foi para o editor): marcas de citação não são edição da pessoa
        limpo = texto_limpo(g.texto)
        desfecho: Desfecho = "aprovado" if ata.texto == limpo else "editado"
        proporcao = 0.0 if desfecho == "aprovado" else proporcao_alterada(limpo, ata.texto)
        nova = self.armazem.registrar_revisao(
            RevisaoAta(t.ente_id, g.id, ev.versao_ata, desfecho, proporcao, ev.conteudo_sha256, ev.publicada_por)
        )
        # reentrega (a mesma versão já medida) não conta duas vezes; perder a métrica num crash entre as duas linhas
        # é preferível a contá-la em dobro
        if nova and self.nucleo is not None:
            self.nucleo.registrar_revisao(g.execucao_id, t.ente_id, ATA_REDIGIR, ev.publicada_por, desfecho, proporcao)
        self.armazem.concluir(t.id)

    def _aviso_ata_pronta(self, g: RascunhoGuardado, correlacao: str) -> NovoTrabalho:
        payload = AtaRascunhoProntaV1(
            solicitacao_id=g.solicitacao_id,
            sessao_id=g.sessao_id,
            rascunho_id=g.id,
            modelo_llm_id=f"{g.vendor}:{g.modelo}",
            prompt_versao=g.prompt_versao,
            incerteza=g.incerteza["nivel"],
            n_citacoes=len(g.citacoes),
            n_citacoes_conferidas=sum(c["status"] == "conferida" for c in g.citacoes),
            n_paragrafos_sem_fonte=len(g.paragrafos_sem_fonte),
            n_pontos_a_confirmar=len(pontos_a_confirmar(g.texto)),
        )
        chave = f"AtaRascunhoPronta:v1:{g.solicitacao_id}"
        return self._notificacao("AtaRascunhoPronta", chave, g.ente_id, correlacao, payload.model_dump(by_alias=True))

    def _aviso_de_falha_da_ata(self, t: Trabalho, e: ErroIA) -> NovoTrabalho:
        ev = AtaSolicitadaV1.model_validate(t.payload)
        categoria = e.categoria if e.categoria.value in CATEGORIAS_DE_FALHA else Categoria.MODELO
        payload = AtaFalhouV1(
            solicitacao_id=ev.solicitacao_id,
            sessao_id=ev.sessao_id,
            categoria=categoria.value,
            detalhe=e.detalhe[:2000],
            retentavel=e.retentavel,
        )
        correlacao = str(t.payload.get("correlation-id") or t.chave)
        chave = f"AtaFalhou:v1:{ev.solicitacao_id}"
        return self._notificacao("AtaFalhou", chave, t.ente_id, correlacao, payload.model_dump(by_alias=True))

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
