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
    NovoTrabalho,
    RascunhoGuardado,
    Trabalho,
    TranscricaoGuardada,
)
from oplenario_ia.transcricao.modelo import Trecho

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


def _trecho_json(t: Trecho) -> dict[str, Any]:
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
        trechos=[Trecho(**t) for t in r["trechos"]],
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

    def trabalhos(self) -> list[dict[str, Any]]:
        with self._conectar() as c:
            rows = c.execute(
                "SELECT id, tipo, chave, estado, tentativas, ultimo_erro AS erro FROM ia.trabalho ORDER BY id"
            ).fetchall()
        return [dict(r) for r in rows]


__all__ = ["ArmazemPostgres", "migrar"]
