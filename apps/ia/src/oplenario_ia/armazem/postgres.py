"""Adaptador Postgres (schema `ia`; ADR-0008). A fila usa `FOR UPDATE SKIP LOCKED` (vários trabalhadores não pegam
o mesmo trabalho) e cada operação é uma transação. As migrações são SQL versionado aplicado em ordem, uma vez."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import asdict
from datetime import datetime
from typing import Any

import psycopg
from psycopg.rows import dict_row, tuple_row
from psycopg.types.json import Jsonb

from oplenario_ia.armazem.porta import (
    NovaTranscricao,
    NovoRascunho,
    NovoResumo,
    NovoTrabalho,
    RascunhoGuardado,
    Resultado,
    ResumoGuardado,
    RevisaoAta,
    Trabalho,
    TranscricaoGuardada,
    TrechoIndice,
)
from oplenario_ia.busca.indice import RRF_K
from oplenario_ia.confianca.cota import Orcamento
from oplenario_ia.transcricao.modelo import Trecho as TrechoTranscricao

MIGRACOES: list[str] = [
    # 1 — cursor, fila, transcrições
    """
    CREATE SCHEMA IF NOT EXISTS ia;
    CREATE TABLE IF NOT EXISTS ia.cursor (nome text PRIMARY KEY, valor bigint NOT NULL);
    CREATE TABLE IF NOT EXISTS ia.trabalho (
      id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
      tipo        text NOT NULL,
      chave       text NOT NULL UNIQUE,
      ente_id     uuid NOT NULL,
      payload     jsonb NOT NULL,
      estado      text NOT NULL DEFAULT 'pendente'
                  CHECK (estado IN ('pendente', 'em_curso', 'concluido', 'falhou', 'descartado')),
      tentativas  integer NOT NULL DEFAULT 0,
      proxima_tentativa timestamptz,
      ultimo_erro text,
      criado_em   timestamptz NOT NULL DEFAULT now(),
      atualizado_em timestamptz NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS idx_trabalho_pendente ON ia.trabalho (id) WHERE estado = 'pendente';
    CREATE TABLE IF NOT EXISTS ia.transcricao (
      id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      ente_id     uuid NOT NULL,
      sessao_id   uuid NOT NULL,
      segmento_id uuid NOT NULL,
      versao      integer NOT NULL,
      idioma      text NOT NULL,
      duracao_s   double precision NOT NULL,
      modelo_asr  text NOT NULL,
      modelo_diarizacao text,
      cobertura   double precision NOT NULL,
      trechos     jsonb NOT NULL,
      criado_em   timestamptz NOT NULL DEFAULT now(),
      UNIQUE (segmento_id, versao)
    );
    """,
    # 2 — rascunhos de ata (Faixa A / A.6b): o texto proposto fica aqui até a secretaria publicar no core
    """
    CREATE TABLE IF NOT EXISTS ia.rascunho_ata (
      id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      ente_id        uuid NOT NULL,
      sessao_id      uuid NOT NULL,
      solicitacao_id uuid NOT NULL UNIQUE,
      execucao_id    text NOT NULL,
      texto          text NOT NULL,
      citacoes       jsonb NOT NULL,
      paragrafos_sem_fonte jsonb NOT NULL,
      incerteza      jsonb NOT NULL,
      vendor         text NOT NULL,
      modelo         text NOT NULL,
      prompt_versao  text NOT NULL,
      transcricoes   jsonb NOT NULL,
      criado_em      timestamptz NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS idx_transcricao_sessao ON ia.transcricao (ente_id, sessao_id);
    """,
    # 3 — a revisão humana de cada rascunho de ata, medida na publicação (A.6c): números, nunca o texto
    """
    CREATE TABLE IF NOT EXISTS ia.revisao_ata (
      rascunho_id        uuid NOT NULL REFERENCES ia.rascunho_ata (id),
      versao_ata         integer NOT NULL,
      ente_id            uuid NOT NULL,
      desfecho           text NOT NULL CHECK (desfecho IN ('aprovado', 'editado')),
      proporcao_alterada double precision NOT NULL CHECK (proporcao_alterada BETWEEN 0 AND 1),
      conteudo_sha256    text NOT NULL,
      publicada_por      uuid NOT NULL,
      criado_em          timestamptz NOT NULL DEFAULT now(),
      PRIMARY KEY (rascunho_id, versao_ata)
    );
    """,
    # 4 — o índice único de busca (A.4): trechos de proposições e transcrições, com o vetor (pgvector, self-host) e o
    # full-text em português; a busca híbrida funde os dois. Cada linha guarda o modelo que gerou o vetor.
    """
    CREATE EXTENSION IF NOT EXISTS vector;
    CREATE TABLE IF NOT EXISTS ia.indice_trecho (
      id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
      ente_id    uuid NOT NULL,
      tipo       text NOT NULL CHECK (tipo IN ('proposicao', 'transcricao')),
      ref_id     uuid NOT NULL,
      parte      integer NOT NULL,
      texto      text NOT NULL,
      meta       jsonb NOT NULL,
      modelo     text NOT NULL,
      embedding  vector(384) NOT NULL,
      tsv        tsvector GENERATED ALWAYS AS (to_tsvector('portuguese', texto)) STORED,
      indexado_em timestamptz NOT NULL DEFAULT now(),
      UNIQUE (tipo, ref_id, parte)
    );
    CREATE INDEX IF NOT EXISTS idx_indice_ente ON ia.indice_trecho (ente_id, tipo);
    CREATE INDEX IF NOT EXISTS idx_indice_tsv ON ia.indice_trecho USING gin (tsv);
    CREATE INDEX IF NOT EXISTS idx_indice_vetor ON ia.indice_trecho USING hnsw (embedding vector_cosine_ops);
    """,
    # 5 — rascunhos de resumo cidadão (A.8): um por versão do texto da proposição; o publicado vive no core
    """
    CREATE TABLE IF NOT EXISTS ia.rascunho_resumo (
      id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      ente_id           uuid NOT NULL,
      proposicao_id     uuid NOT NULL,
      texto_base_sha256 text NOT NULL,
      execucao_id       text NOT NULL,
      texto             text NOT NULL,
      citacoes          jsonb NOT NULL,
      paragrafos_sem_fonte jsonb NOT NULL,
      incerteza         jsonb NOT NULL,
      vendor            text NOT NULL,
      modelo            text NOT NULL,
      prompt_versao     text NOT NULL,
      criado_em         timestamptz NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS idx_rascunho_resumo_prop ON ia.rascunho_resumo (ente_id, proposicao_id, criado_em);
    """,
    # 6 — dispositivos de normas de referência no índice (B.4b, ADR-0011)
    """
    ALTER TABLE ia.indice_trecho DROP CONSTRAINT IF EXISTS indice_trecho_tipo_check;
    ALTER TABLE ia.indice_trecho ADD CONSTRAINT indice_trecho_tipo_check
      CHECK (tipo IN ('proposicao', 'transcricao', 'dispositivo'));
    """,
    # 7 — B.9 (ADR-0014): o registro da Camada de Confiança compartilhado (API + trabalhador), de onde sai o gasto
    # do mês de cada Casa; e o orçamento que o core define para cada Casa (substituído quando o plano muda)
    """
    CREATE TABLE IF NOT EXISTS ia.registro_evento (
      id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
      tipo               text NOT NULL CHECK (tipo IN ('execucao', 'reporte_erro', 'revisao')),
      ente_id            text NOT NULL,
      operacao           text NOT NULL,
      instante           timestamptz NOT NULL,
      custo              numeric,
      custo_desconhecido boolean NOT NULL DEFAULT false,
      corpo              jsonb NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_registro_evento_ente ON ia.registro_evento (ente_id, instante);
    CREATE TABLE IF NOT EXISTS ia.orcamento (
      ente_id     uuid PRIMARY KEY,
      mensal      numeric NOT NULL CHECK (mensal >= 0),
      teto_duro   numeric NOT NULL CHECK (teto_duro >= mensal),
      moeda       text NOT NULL,
      definido_em timestamptz NOT NULL,
      chave       text NOT NULL
    );
    """,
    # Onda E — a observabilidade do operador lê TODAS as Casas numa janela de horas: o índice por (ente, instante)
    # não serve a essa varredura
    """
    CREATE INDEX IF NOT EXISTS idx_registro_evento_instante ON ia.registro_evento (instante) WHERE tipo = 'execucao';
    """,
]


def migrar(conn: psycopg.Connection[Any]) -> None:
    with conn.transaction():
        conn.execute("CREATE SCHEMA IF NOT EXISTS ia")
        conn.execute(
            "CREATE TABLE IF NOT EXISTS ia.migracao (versao integer PRIMARY KEY, em timestamptz DEFAULT now())"
        )
        conn.execute("SELECT pg_advisory_xact_lock(4242)")
        with conn.cursor(row_factory=tuple_row) as cur:
            feitas = {r[0] for r in cur.execute("SELECT versao FROM ia.migracao").fetchall()}
        for n, sql in enumerate(MIGRACOES, start=1):
            if n not in feitas:
                conn.execute(sql)
                conn.execute("INSERT INTO ia.migracao (versao) VALUES (%s)", (n,))


def _vetor(v: list[float]) -> str:
    return "[" + ",".join(f"{x:.7g}" for x in v) + "]"


def _trecho_json(t: TrechoTranscricao) -> dict[str, Any]:
    return asdict(t)


def _guardada(r: dict[str, Any]) -> TranscricaoGuardada:
    return TranscricaoGuardada(
        id=str(r["id"]),
        versao=r["versao"],
        ente_id=str(r["ente_id"]),
        sessao_id=str(r["sessao_id"]),
        segmento_id=str(r["segmento_id"]),
        idioma=r["idioma"],
        duracao_s=r["duracao_s"],
        modelo_asr=r["modelo_asr"],
        modelo_diarizacao=r["modelo_diarizacao"],
        cobertura=r["cobertura"],
        trechos=[TrechoTranscricao(**t) for t in r["trechos"]],
        criado_em=r["criado_em"],
    )


def _resumo(r: dict[str, Any]) -> ResumoGuardado:
    return ResumoGuardado(
        id=str(r["id"]),
        ente_id=str(r["ente_id"]),
        proposicao_id=str(r["proposicao_id"]),
        texto_base_sha256=r["texto_base_sha256"],
        execucao_id=r["execucao_id"],
        texto=r["texto"],
        citacoes=r["citacoes"],
        paragrafos_sem_fonte=r["paragrafos_sem_fonte"],
        incerteza=r["incerteza"],
        vendor=r["vendor"],
        modelo=r["modelo"],
        prompt_versao=r["prompt_versao"],
        criado_em=r["criado_em"],
    )


def _rascunho(r: dict[str, Any]) -> RascunhoGuardado:
    return RascunhoGuardado(
        id=str(r["id"]),
        ente_id=str(r["ente_id"]),
        sessao_id=str(r["sessao_id"]),
        solicitacao_id=str(r["solicitacao_id"]),
        execucao_id=r["execucao_id"],
        texto=r["texto"],
        citacoes=r["citacoes"],
        paragrafos_sem_fonte=r["paragrafos_sem_fonte"],
        incerteza=r["incerteza"],
        vendor=r["vendor"],
        modelo=r["modelo"],
        prompt_versao=r["prompt_versao"],
        transcricoes=r["transcricoes"],
        criado_em=r["criado_em"],
    )


class ArmazemPostgres:
    def __init__(self, url: str) -> None:
        self._url = url
        with self._conectar() as conn:
            migrar(conn)

    def _conectar(self) -> psycopg.Connection[dict[str, Any]]:
        return psycopg.connect(self._url, row_factory=dict_row, autocommit=True)

    def cursor(self) -> int:
        with self._conectar() as c:
            r = c.execute("SELECT valor FROM ia.cursor WHERE nome = 'feed'").fetchone()
            return int(r["valor"]) if r else 0

    @staticmethod
    def _enfileirar(c: psycopg.Connection[dict[str, Any]], n: NovoTrabalho) -> bool:
        r = c.execute(
            "INSERT INTO ia.trabalho (tipo, chave, ente_id, payload) VALUES (%s, %s, %s, %s)"
            " ON CONFLICT (chave) DO NOTHING RETURNING id",
            (n.tipo, n.chave, n.ente_id, Jsonb(n.payload)),
        ).fetchone()
        return r is not None

    def registrar_feed(self, novos: list[NovoTrabalho], proximo: int) -> int:
        with self._conectar() as c, c.transaction():
            n = sum(self._enfileirar(c, t) for t in novos)
            c.execute(
                "INSERT INTO ia.cursor (nome, valor) VALUES ('feed', %s)"
                " ON CONFLICT (nome) DO UPDATE SET valor = GREATEST(ia.cursor.valor, EXCLUDED.valor)",
                (proximo,),
            )
            return n

    def proximo(self, agora: datetime) -> Trabalho | None:
        with self._conectar() as c:
            r = c.execute(
                """UPDATE ia.trabalho SET estado = 'em_curso', atualizado_em = now()
                   WHERE id = (SELECT id FROM ia.trabalho
                               WHERE estado = 'pendente' AND (proxima_tentativa IS NULL OR proxima_tentativa <= %s)
                               ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
                   RETURNING id, tipo, chave, ente_id, payload, tentativas""",
                (agora,),
            ).fetchone()
        if r is None:
            return None
        return Trabalho(r["id"], r["tipo"], r["chave"], str(r["ente_id"]), r["payload"], r["tentativas"])

    def concluir(
        self, trabalho_id: int, seguintes: list[NovoTrabalho] | None = None, *, estado: str = "concluido"
    ) -> None:
        with self._conectar() as c, c.transaction():
            c.execute("UPDATE ia.trabalho SET estado = %s, atualizado_em = now() WHERE id = %s", (estado, trabalho_id))
            for s in seguintes or []:
                self._enfileirar(c, s)

    def adiar(self, trabalho_id: int, erro: str, quando: datetime) -> None:
        with self._conectar() as c:
            c.execute(
                "UPDATE ia.trabalho SET estado = 'pendente', tentativas = tentativas + 1, proxima_tentativa = %s,"
                " ultimo_erro = %s, atualizado_em = now() WHERE id = %s",
                (quando, erro[:2000], trabalho_id),
            )

    def pausar(self, trabalho_id: int, motivo: str, ate: datetime) -> None:
        with self._conectar() as c:
            c.execute(
                "UPDATE ia.trabalho SET estado = 'pendente', proxima_tentativa = %s, ultimo_erro = %s,"
                " atualizado_em = now() WHERE id = %s",
                (ate, motivo[:2000], trabalho_id),
            )

    def definir_orcamento(self, ente_id: str, orcamento: Orcamento, definido_em: datetime, chave: str) -> None:
        # a definição mais recente vale; um evento atrasado (definido antes) não volta o orçamento para trás
        with self._conectar() as c:
            c.execute(
                "INSERT INTO ia.orcamento (ente_id, mensal, teto_duro, moeda, definido_em, chave)"
                " VALUES (%s, %s, %s, %s, %s, %s)"
                " ON CONFLICT (ente_id) DO UPDATE SET mensal = excluded.mensal, teto_duro = excluded.teto_duro,"
                " moeda = excluded.moeda, definido_em = excluded.definido_em, chave = excluded.chave"
                " WHERE ia.orcamento.definido_em <= excluded.definido_em",
                (ente_id, orcamento.mensal, orcamento.teto_duro, orcamento.moeda, definido_em, chave),
            )

    def orcamento(self, ente_id: str) -> Orcamento | None:
        with self._conectar() as c:
            r = c.execute("SELECT mensal, teto_duro, moeda FROM ia.orcamento WHERE ente_id = %s", (ente_id,)).fetchone()
            return Orcamento(mensal=r["mensal"], teto_duro=r["teto_duro"], moeda=r["moeda"]) if r else None

    def desistir(self, trabalho_id: int, erro: str, seguintes: list[NovoTrabalho] | None = None) -> None:
        with self._conectar() as c, c.transaction():
            c.execute(
                "UPDATE ia.trabalho SET estado = 'falhou', tentativas = tentativas + 1, ultimo_erro = %s,"
                " atualizado_em = now() WHERE id = %s",
                (erro[:2000], trabalho_id),
            )
            for s in seguintes or []:
                self._enfileirar(c, s)

    def concluir_transcricao(
        self, trabalho_id: int, nova: NovaTranscricao, notificar: Callable[[TranscricaoGuardada], NovoTrabalho]
    ) -> TranscricaoGuardada:
        with self._conectar() as c, c.transaction():
            c.execute("SELECT pg_advisory_xact_lock(hashtext(%s))", (nova.segmento_id,))
            r = c.execute(
                """INSERT INTO ia.transcricao (ente_id, sessao_id, segmento_id, versao, idioma, duracao_s, modelo_asr,
                                               modelo_diarizacao, cobertura, trechos)
                   VALUES (%s, %s, %s,
                           (SELECT COALESCE(max(versao), 0) + 1 FROM ia.transcricao WHERE segmento_id = %s),
                           %s, %s, %s, %s, %s, %s)
                   RETURNING *""",
                (
                    nova.ente_id,
                    nova.sessao_id,
                    nova.segmento_id,
                    nova.segmento_id,
                    nova.idioma,
                    nova.duracao_s,
                    nova.modelo_asr,
                    nova.modelo_diarizacao,
                    nova.cobertura,
                    Jsonb([_trecho_json(t) for t in nova.trechos]),
                ),
            ).fetchone()
            assert r is not None
            g = _guardada(r)
            c.execute(
                "UPDATE ia.trabalho SET estado = 'concluido', atualizado_em = now() WHERE id = %s", (trabalho_id,)
            )
            self._enfileirar(c, notificar(g))
            return g

    def transcricao(self, transcricao_id: str) -> TranscricaoGuardada | None:
        with self._conectar() as c:
            try:
                r = c.execute("SELECT * FROM ia.transcricao WHERE id = %s", (transcricao_id,)).fetchone()
            except psycopg.errors.InvalidTextRepresentation:
                return None
        return _guardada(r) if r else None

    def transcricoes_da_sessao(self, ente_id: str, sessao_id: str) -> list[TranscricaoGuardada]:
        with self._conectar() as c:
            rows = c.execute(
                """SELECT * FROM (SELECT DISTINCT ON (segmento_id) * FROM ia.transcricao
                                  WHERE ente_id = %s AND sessao_id = %s ORDER BY segmento_id, versao DESC) u
                   ORDER BY criado_em, id""",
                (ente_id, sessao_id),
            ).fetchall()
        return [_guardada(r) for r in rows]

    def concluir_rascunho(
        self, trabalho_id: int, novo: NovoRascunho, notificar: Callable[[RascunhoGuardado], NovoTrabalho]
    ) -> RascunhoGuardado:
        with self._conectar() as c, c.transaction():
            r = c.execute(
                """INSERT INTO ia.rascunho_ata (ente_id, sessao_id, solicitacao_id, execucao_id, texto, citacoes,
                                                paragrafos_sem_fonte, incerteza, vendor, modelo, prompt_versao,
                                                transcricoes)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING *""",
                (
                    novo.ente_id,
                    novo.sessao_id,
                    novo.solicitacao_id,
                    novo.execucao_id,
                    novo.texto,
                    Jsonb(novo.citacoes),
                    Jsonb(novo.paragrafos_sem_fonte),
                    Jsonb(novo.incerteza),
                    novo.vendor,
                    novo.modelo,
                    novo.prompt_versao,
                    Jsonb(novo.transcricoes),
                ),
            ).fetchone()
            assert r is not None
            g = _rascunho(r)
            c.execute(
                "UPDATE ia.trabalho SET estado = 'concluido', atualizado_em = now() WHERE id = %s", (trabalho_id,)
            )
            self._enfileirar(c, notificar(g))
            return g

    def rascunho(self, rascunho_id: str) -> RascunhoGuardado | None:
        with self._conectar() as c:
            try:
                r = c.execute("SELECT * FROM ia.rascunho_ata WHERE id = %s", (rascunho_id,)).fetchone()
            except psycopg.errors.InvalidTextRepresentation:
                return None
        return _rascunho(r) if r else None

    def concluir_resumo(
        self, trabalho_id: int, novo: NovoResumo, notificar: Callable[[ResumoGuardado], NovoTrabalho]
    ) -> ResumoGuardado:
        with self._conectar() as c, c.transaction():
            r = c.execute(
                """INSERT INTO ia.rascunho_resumo (ente_id, proposicao_id, texto_base_sha256, execucao_id, texto,
                                                   citacoes, paragrafos_sem_fonte, incerteza, vendor, modelo,
                                                   prompt_versao)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING *""",
                (
                    novo.ente_id,
                    novo.proposicao_id,
                    novo.texto_base_sha256,
                    novo.execucao_id,
                    novo.texto,
                    Jsonb(novo.citacoes),
                    Jsonb(novo.paragrafos_sem_fonte),
                    Jsonb(novo.incerteza),
                    novo.vendor,
                    novo.modelo,
                    novo.prompt_versao,
                ),
            ).fetchone()
            assert r is not None
            g = _resumo(r)
            c.execute(
                "UPDATE ia.trabalho SET estado = 'concluido', atualizado_em = now() WHERE id = %s", (trabalho_id,)
            )
            self._enfileirar(c, notificar(g))
            return g

    def resumo(self, resumo_id: str) -> ResumoGuardado | None:
        with self._conectar() as c:
            try:
                r = c.execute("SELECT * FROM ia.rascunho_resumo WHERE id = %s", (resumo_id,)).fetchone()
            except psycopg.errors.InvalidTextRepresentation:
                return None
        return _resumo(r) if r else None

    def ultimo_resumo(self, ente_id: str, proposicao_id: str) -> ResumoGuardado | None:
        with self._conectar() as c:
            r = c.execute(
                """SELECT * FROM ia.rascunho_resumo WHERE ente_id = %s AND proposicao_id = %s
                   ORDER BY criado_em DESC, id DESC LIMIT 1""",
                (ente_id, proposicao_id),
            ).fetchone()
        return _resumo(r) if r else None

    def enfileirar(self, novos: list[NovoTrabalho]) -> int:
        with self._conectar() as c, c.transaction():
            return sum(self._enfileirar(c, t) for t in novos)

    def ultimas_transcricoes(self) -> list[TranscricaoGuardada]:
        with self._conectar() as c:
            rows = c.execute(
                "SELECT DISTINCT ON (segmento_id) * FROM ia.transcricao ORDER BY segmento_id, versao DESC"
            ).fetchall()
        return [_guardada(r) for r in rows]

    def indexar(
        self, ente_id: str, tipo: str, ref_id: str, trechos: list[TrechoIndice], vetores: list[list[float]], modelo: str
    ) -> None:
        with self._conectar() as c, c.transaction():
            c.execute("DELETE FROM ia.indice_trecho WHERE tipo = %s AND ref_id = %s", (tipo, ref_id))
            for t, v in zip(trechos, vetores, strict=True):
                c.execute(
                    "INSERT INTO ia.indice_trecho (ente_id, tipo, ref_id, parte, texto, meta, modelo, embedding)"
                    " VALUES (%s, %s, %s, %s, %s, %s, %s, %s::vector)",
                    (ente_id, tipo, ref_id, t.parte, t.texto, Jsonb(t.meta), modelo, _vetor(v)),
                )

    def buscar(
        self,
        ente_id: str,
        consulta: str,
        vetor: list[float],
        modelo: str,
        tipos: list[str],
        limite: int,
        distancia_maxima: float,
    ) -> list[Resultado]:
        v = _vetor(vetor)
        with self._conectar() as c:
            rows = c.execute(
                """WITH sentido AS (
                     SELECT id, row_number() OVER (ORDER BY embedding <=> %(v)s::vector) AS r
                       FROM ia.indice_trecho
                      WHERE ente_id = %(e)s AND modelo = %(m)s AND tipo = ANY(%(t)s)
                        AND embedding <=> %(v)s::vector <= %(d)s
                      ORDER BY embedding <=> %(v)s::vector LIMIT 50),
                   termo AS (
                     SELECT id, row_number() OVER (ORDER BY ts_rank_cd(tsv, q) DESC) AS r
                       FROM ia.indice_trecho, websearch_to_tsquery('portuguese', %(q)s) q
                      WHERE ente_id = %(e)s AND tipo = ANY(%(t)s) AND tsv @@ q
                      ORDER BY ts_rank_cd(tsv, q) DESC LIMIT 50)
                   SELECT i.tipo, i.ref_id, i.parte, i.texto, i.meta,
                          COALESCE(1.0 / (%(k)s + s.r), 0) + COALESCE(1.0 / (%(k)s + t.r), 0) AS score
                     FROM ia.indice_trecho i
                     LEFT JOIN sentido s ON s.id = i.id
                     LEFT JOIN termo t ON t.id = i.id
                    WHERE s.id IS NOT NULL OR t.id IS NOT NULL
                    ORDER BY score DESC, i.id
                    LIMIT %(l)s""",
                {
                    "v": v,
                    "e": ente_id,
                    "m": modelo,
                    "t": tipos,
                    "d": distancia_maxima,
                    "q": consulta,
                    "k": RRF_K,
                    "l": limite,
                },
            ).fetchall()
        return [
            Resultado(r["tipo"], str(r["ref_id"]), r["parte"], r["texto"], r["meta"], float(r["score"])) for r in rows
        ]

    def registrar_revisao(self, revisao: RevisaoAta) -> bool:
        with self._conectar() as c:
            r = c.execute(
                """INSERT INTO ia.revisao_ata (rascunho_id, versao_ata, ente_id, desfecho, proporcao_alterada,
                                               conteudo_sha256, publicada_por)
                   VALUES (%s, %s, %s, %s, %s, %s, %s) ON CONFLICT DO NOTHING RETURNING rascunho_id""",
                (
                    revisao.rascunho_id,
                    revisao.versao_ata,
                    revisao.ente_id,
                    revisao.desfecho,
                    revisao.proporcao_alterada,
                    revisao.conteudo_sha256,
                    revisao.publicada_por,
                ),
            ).fetchone()
        return r is not None

    def trabalhos(self) -> list[dict[str, Any]]:
        with self._conectar() as c:
            rows = c.execute(
                "SELECT id, tipo, chave, estado, tentativas, ultimo_erro AS erro FROM ia.trabalho ORDER BY id"
            ).fetchall()
        return [dict(r) for r in rows]


__all__ = ["ArmazemPostgres", "migrar"]
