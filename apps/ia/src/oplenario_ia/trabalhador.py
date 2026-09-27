"""O trabalhador da Faixa A (ADR-0008): puxa o feed do core, transcreve cada gravação vinculada, redige o rascunho
da ata quando a secretaria pede, e devolve o resultado à caixa de entrada do core.

Três tipos de trabalho na fila própria do satélite:
- `transcrever` (um por evento `GravacaoVinculada`): contexto + download + ASR + diarização + Caminho C; guarda a
  transcrição e enfileira a notificação NA MESMA operação (reiniciar no meio não transcreve de novo).
- `redigir_ata` (um por evento `AtaSolicitada`, A.6b): contexto + as transcrições guardadas aqui -> núcleo (filtro,
  porta, citação conferida, incerteza, registro); guarda o rascunho e enfileira `AtaRascunhoPronta` junto.
- `registrar_revisao` (um por `AtaRevisadaEPublicada`, A.6c): lê a ata publicada, confere o hash e mede quanto a pessoa
  mudou do rascunho — vira `RevisaoHumana` no registro (a taxa de aceitação da ata por Casa). Uma vez por versão.
- `indexar_transcricao` / `indexar_proposicao` (A.4) e `indexar_norma` (B.4b, a versão vigente de uma norma):
  trechos -> embeddings self-host -> índice (substitui o que havia do mesmo documento). A transcrição entra no índice
  assim que fica pronta; a proposição e a norma, pelos eventos do core.
- `redigir_resumo` (A.8, junto do `indexar_proposicao` de cada `ProposicaoProtocolada`/`ProposicaoAtualizada`): lê o
  texto no core; se a versão do texto já tem rascunho aqui, não faz nada; senão núcleo -> rascunho do resumo cidadão
  guardado + `ResumoCidadaoPronto` na mesma operação. Proposição é pública; o publicado vive no core.
- `notificar`: entrega os eventos ao core (`Transcricao*`, `Ata*`, `Resumo*`); tenta até o core aceitar.

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
    NovoResumo,
    NovoTrabalho,
    RascunhoGuardado,
    ResumoGuardado,
    RevisaoAta,
    Trabalho,
    TranscricaoGuardada,
)
from oplenario_ia.ata.redacao import OPERACAO as ATA_REDIGIR
from oplenario_ia.ata.redacao import PROMPT_VERSAO, pedido_de_ata, pontos_a_confirmar, texto_limpo
from oplenario_ia.busca.embeddings import Embedder
from oplenario_ia.busca.indice import trechos_de_norma, trechos_de_proposicao, trechos_de_transcricao
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
    NormaVigenteV1,
    ProposicaoIndexavelV1,
    ResumoCidadaoProntoV1,
    ResumoFalhouV1,
    TranscricaoConcluidaV1,
    TranscricaoFalhouV1,
)
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.resumo.redacao import PROMPT_VERSAO as PROMPT_RESUMO
from oplenario_ia.resumo.redacao import pedido_de_resumo
from oplenario_ia.transcricao.porta import Diarizador, Transcritor
from oplenario_ia.transcricao.servico import transcrever_segmento

log = logging.getLogger("oplenario_ia.trabalhador")

MAX_TENTATIVAS = {
    "transcrever": 5,
    "redigir_ata": 5,
    "registrar_revisao": 10,
    "indexar_transcricao": 5,
    "indexar_proposicao": 5,
    "indexar_norma": 5,
    "redigir_resumo": 5,
    "notificar": 20,
}
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
        embedder: Embedder | None = None,
        agora: Callable[[], datetime] = lambda: datetime.now(UTC),
        dir_temp: Path | None = None,
    ) -> None:
        self.core = core
        self.armazem = armazem
        self.transcritor = transcritor
        self.diarizador = diarizador
        self.idioma = idioma
        self.nucleo = nucleo
        self.embedder = embedder
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
            elif (ev.tipo, ev.versao) in (("ProposicaoProtocolada", 1), ("ProposicaoAtualizada", 1)):
                novos.append(NovoTrabalho("indexar_proposicao", ev.chave, ev.ente_id, ev.payload))
                novos.append(
                    NovoTrabalho(
                        "redigir_resumo", f"resumo:{ev.chave}", ev.ente_id, ev.payload | {"correlation-id": ev.chave}
                    )
                )
            elif (ev.tipo, ev.versao) == ("NormaVigente", 1):
                novos.append(NovoTrabalho("indexar_norma", ev.chave, ev.ente_id, ev.payload))
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
            elif t.tipo == "indexar_transcricao":
                self._indexar_transcricao(t)
            elif t.tipo == "indexar_proposicao":
                self._indexar_proposicao(t)
            elif t.tipo == "indexar_norma":
                self._indexar_norma(t)
            elif t.tipo == "redigir_resumo":
                self._redigir_resumo(t)
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
            else [self._aviso_de_falha_do_resumo(t, e)]
            if t.tipo == "redigir_resumo"
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
        guardada = self.armazem.concluir_transcricao(t.id, nova, lambda g: self._aviso_de_conclusao(g, correlacao))
        # a busca acha o que foi dito em plenário: a transcrição nova entra no índice (trabalho próprio, com retry)
        self.armazem.enfileirar([self.trabalho_de_indexacao(guardada)])

    @staticmethod
    def trabalho_de_indexacao(g: TranscricaoGuardada) -> NovoTrabalho:
        return NovoTrabalho("indexar_transcricao", f"indexar:transcricao:{g.id}", g.ente_id, {"transcricao-id": g.id})

    def reindexar_transcricoes(self) -> int:
        """Enfileira a (re)indexação da versão mais recente de cada gravação — depois de trocar o modelo de embeddings,
        ou na primeira vez que o índice liga. Idempotente: a mesma transcrição não entra duas vezes na fila."""
        return self.armazem.enfileirar([self.trabalho_de_indexacao(g) for g in self.armazem.ultimas_transcricoes()])

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

    # ---------- o índice (A.4) ----------

    def _exigir_embedder(self) -> Embedder:
        if self.embedder is None:
            raise ErroIA(Categoria.INFRAESTRUTURA, "embeddings não configurados", retentavel=False)
        return self.embedder

    def _indexar_transcricao(self, t: Trabalho) -> None:
        emb = self._exigir_embedder()
        g = self.armazem.transcricao(str(t.payload["transcricao-id"]))
        if g is None:
            raise ErroIA(Categoria.ENTRADA, "transcrição inexistente neste satélite", retentavel=False)
        trechos = trechos_de_transcricao(g)
        vetores = emb.embed([x.texto for x in trechos], "documento") if trechos else []
        self.armazem.indexar(g.ente_id, "transcricao", g.segmento_id, trechos, vetores, emb.modelo)
        self.armazem.concluir(t.id)

    def _indexar_proposicao(self, t: Trabalho) -> None:
        emb = self._exigir_embedder()
        ev = ProposicaoIndexavelV1.model_validate(t.payload)
        trechos = trechos_de_proposicao(ev.ementa, ev.autor_texto)
        vetores = emb.embed([x.texto for x in trechos], "documento")
        self.armazem.indexar(t.ente_id, "proposicao", ev.proposicao_id, trechos, vetores, emb.modelo)
        self.armazem.concluir(t.id)

    def _indexar_norma(self, t: Trabalho) -> None:
        """B.4b: os dispositivos da versão vigente entram no índice como `dispositivo`, por NORMA — publicar uma versão
        nova substitui os trechos da anterior. Se a versão já não é a vigente, não há o que indexar."""
        emb = self._exigir_embedder()
        ev = NormaVigenteV1.model_validate(t.payload)
        n = self.core.dispositivos_norma(t.ente_id, ev.versao_id)
        if n is not None:
            trechos = trechos_de_norma(n)
            vetores = emb.embed([x.texto for x in trechos], "documento") if trechos else []
            self.armazem.indexar(t.ente_id, "dispositivo", n.norma_id, trechos, vetores, emb.modelo)
        self.armazem.concluir(t.id)

    # ---------- o resumo cidadão (A.8) ----------

    def _redigir_resumo(self, t: Trabalho) -> None:
        if self.nucleo is None:
            raise ErroIA(Categoria.INFRAESTRUTURA, "o núcleo de IA não está configurado", retentavel=False)
        proposicao_id = str(t.payload["proposicao-id"])
        texto = self.core.texto_proposicao(t.ente_id, proposicao_id)
        ultimo = self.armazem.ultimo_resumo(t.ente_id, proposicao_id)
        if ultimo is not None and ultimo.texto_base_sha256 == texto.texto_sha256:
            # a mesma versão do texto (edição só de metadado, reentrega, carga inicial repetida): nada a redigir
            self.armazem.concluir(t.id)
            return
        correlacao = str(t.payload.get("correlation-id") or t.chave)
        r = self.nucleo.executar(pedido_de_resumo(texto, t.ente_id, correlacao), "por_paragrafo")
        if isinstance(r, Indisponivel):
            raise ErroIA(r.categoria or Categoria.ENTRADA, r.mensagem, retentavel=r.retentavel)
        novo = NovoResumo(
            ente_id=t.ente_id,
            proposicao_id=proposicao_id,
            texto_base_sha256=texto.texto_sha256,
            execucao_id=r.execucao_id,
            texto=r.texto,
            citacoes=[c.model_dump(mode="json") for c in r.citacoes],
            paragrafos_sem_fonte=list(r.paragrafos_sem_fonte),
            incerteza=r.incerteza.model_dump(mode="json"),
            vendor=r.vendor,
            modelo=r.modelo,
            prompt_versao=PROMPT_RESUMO,
        )
        self.armazem.concluir_resumo(t.id, novo, lambda g: self._aviso_resumo_pronto(g, correlacao))

    def _aviso_resumo_pronto(self, g: ResumoGuardado, correlacao: str) -> NovoTrabalho:
        payload = ResumoCidadaoProntoV1(
            proposicao_id=g.proposicao_id,
            rascunho_id=g.id,
            texto_base_sha256=g.texto_base_sha256,
            modelo_llm_id=f"{g.vendor}:{g.modelo}",
            prompt_versao=g.prompt_versao,
            incerteza=g.incerteza["nivel"],
            n_citacoes=len(g.citacoes),
            n_citacoes_conferidas=sum(c["status"] == "conferida" for c in g.citacoes),
            n_paragrafos_sem_fonte=len(g.paragrafos_sem_fonte),
        )
        chave = f"ResumoCidadaoPronto:v1:{g.id}"
        return self._notificacao("ResumoCidadaoPronto", chave, g.ente_id, correlacao, payload.model_dump(by_alias=True))

    def _aviso_de_falha_do_resumo(self, t: Trabalho, e: ErroIA) -> NovoTrabalho:
        categoria = e.categoria if e.categoria.value in CATEGORIAS_DE_FALHA else Categoria.MODELO
        payload = ResumoFalhouV1(
            proposicao_id=str(t.payload["proposicao-id"]),
            categoria=categoria.value,
            detalhe=e.detalhe[:2000],
            retentavel=e.retentavel,
        )
        correlacao = str(t.payload.get("correlation-id") or t.chave)
        chave = f"ResumoFalhou:v1:{hashlib.sha256(t.chave.encode()).hexdigest()[:32]}"
        return self._notificacao("ResumoFalhou", chave, t.ente_id, correlacao, payload.model_dump(by_alias=True))

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
