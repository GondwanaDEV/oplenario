"""Adaptador em memória (testes). Mesma semântica do Postgres, sem concorrência entre processos."""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import replace
from datetime import UTC, datetime
from typing import Any

from oplenario_ia.armazem.porta import (
    NovaTranscricao,
    NovoRascunho,
    NovoTrabalho,
    RascunhoGuardado,
    Resultado,
    RevisaoAta,
    Trabalho,
    TranscricaoGuardada,
    TrechoIndice,
)
from oplenario_ia.busca.embeddings import normalizar
from oplenario_ia.busca.indice import fundir


class ArmazemMemoria:
    def __init__(self) -> None:
        self._cursor = 0
        self._seq = 0
        self._trab: dict[int, dict[str, Any]] = {}
        self._chaves: set[str] = set()
        self._transc: dict[str, TranscricaoGuardada] = {}
        self._rasc: dict[str, RascunhoGuardado] = {}
        self._revisoes: dict[tuple[str, int], RevisaoAta] = {}
        self._indice: dict[tuple[str, str], list[tuple[str, TrechoIndice, list[float], str]]] = {}

    def cursor(self) -> int:
        return self._cursor

    def _enfileirar(self, n: NovoTrabalho) -> bool:
        if n.chave in self._chaves:
            return False
        self._seq += 1
        self._chaves.add(n.chave)
        self._trab[self._seq] = {
            "id": self._seq,
            "tipo": n.tipo,
            "chave": n.chave,
            "ente_id": n.ente_id,
            "payload": dict(n.payload),
            "estado": "pendente",
            "tentativas": 0,
            "proxima": None,
            "erro": None,
        }
        return True

    def registrar_feed(self, novos: list[NovoTrabalho], proximo: int) -> int:
        n = sum(self._enfileirar(t) for t in novos)
        self._cursor = max(self._cursor, proximo)
        return n

    def proximo(self, agora: datetime) -> Trabalho | None:
        for t in sorted(self._trab.values(), key=lambda t: t["id"]):
            if t["estado"] == "pendente" and (t["proxima"] is None or t["proxima"] <= agora):
                t["estado"] = "em_curso"
                return Trabalho(t["id"], t["tipo"], t["chave"], t["ente_id"], dict(t["payload"]), t["tentativas"])
        return None

    def concluir(
        self, trabalho_id: int, seguintes: list[NovoTrabalho] | None = None, *, estado: str = "concluido"
    ) -> None:
        self._trab[trabalho_id]["estado"] = estado
        for s in seguintes or []:
            self._enfileirar(s)

    def adiar(self, trabalho_id: int, erro: str, quando: datetime) -> None:
        t = self._trab[trabalho_id]
        t.update(estado="pendente", tentativas=t["tentativas"] + 1, proxima=quando, erro=erro)

    def desistir(self, trabalho_id: int, erro: str, seguintes: list[NovoTrabalho] | None = None) -> None:
        t = self._trab[trabalho_id]
        t.update(estado="falhou", tentativas=t["tentativas"] + 1, erro=erro)
        for s in seguintes or []:
            self._enfileirar(s)

    def concluir_transcricao(
        self, trabalho_id: int, nova: NovaTranscricao, notificar: Callable[[TranscricaoGuardada], NovoTrabalho]
    ) -> TranscricaoGuardada:
        versao = 1 + sum(1 for t in self._transc.values() if t.segmento_id == nova.segmento_id)
        g = TranscricaoGuardada(id=str(uuid.uuid4()), versao=versao, criado_em=datetime.now(UTC), **nova.__dict__)
        self._transc[g.id] = g
        self.concluir(trabalho_id, [notificar(g)])
        return g

    def transcricao(self, transcricao_id: str) -> TranscricaoGuardada | None:
        g = self._transc.get(transcricao_id)
        return replace(g) if g else None

    def transcricoes_da_sessao(self, ente_id: str, sessao_id: str) -> list[TranscricaoGuardada]:
        ultimas: dict[str, TranscricaoGuardada] = {}
        for t in self._transc.values():
            da_sessao = t.ente_id == ente_id and t.sessao_id == sessao_id
            if da_sessao and (t.segmento_id not in ultimas or t.versao > ultimas[t.segmento_id].versao):
                ultimas[t.segmento_id] = t
        return sorted(ultimas.values(), key=lambda t: (t.criado_em or datetime.min.replace(tzinfo=UTC), t.id))

    def concluir_rascunho(
        self, trabalho_id: int, novo: NovoRascunho, notificar: Callable[[RascunhoGuardado], NovoTrabalho]
    ) -> RascunhoGuardado:
        if any(r.solicitacao_id == novo.solicitacao_id for r in self._rasc.values()):
            raise ValueError("já existe rascunho para esta solicitação")  # o UNIQUE do Postgres
        g = RascunhoGuardado(id=str(uuid.uuid4()), criado_em=datetime.now(UTC), **novo.__dict__)
        self._rasc[g.id] = g
        self.concluir(trabalho_id, [notificar(g)])
        return g

    def rascunho(self, rascunho_id: str) -> RascunhoGuardado | None:
        return self._rasc.get(rascunho_id)

    def enfileirar(self, novos: list[NovoTrabalho]) -> int:
        return sum(self._enfileirar(t) for t in novos)

    def ultimas_transcricoes(self) -> list[TranscricaoGuardada]:
        ultimas: dict[str, TranscricaoGuardada] = {}
        for t in self._transc.values():
            if t.segmento_id not in ultimas or t.versao > ultimas[t.segmento_id].versao:
                ultimas[t.segmento_id] = t
        return list(ultimas.values())

    def indexar(
        self, ente_id: str, tipo: str, ref_id: str, trechos: list[TrechoIndice], vetores: list[list[float]], modelo: str
    ) -> None:
        self._indice[(tipo, ref_id)] = [(ente_id, t, v, modelo) for t, v in zip(trechos, vetores, strict=True)]

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
        termos = set(normalizar(consulta))
        candidatos = {
            f"{tipo}|{ref}|{t.parte}": (tipo, ref, t, v, m)
            for (tipo, ref), linhas in self._indice.items()
            if tipo in tipos
            for (e, t, v, m) in linhas
            if e == ente_id
        }
        lexico = sorted(
            (k for k, (_, _, t, _, _) in candidatos.items() if termos & set(normalizar(t.texto))),
            key=lambda k: -len(termos & set(normalizar(candidatos[k][2].texto))),
        )
        dist = {
            k: 1 - sum(a * b for a, b in zip(vetor, c[3], strict=True)) for k, c in candidatos.items() if c[4] == modelo
        }
        sentido = sorted((k for k, d in dist.items() if d <= distancia_maxima), key=lambda k: dist[k])
        score = fundir([lexico[:50], sentido[:50]])
        melhores = sorted(score, key=lambda k: -score[k])[:limite]
        return [
            Resultado(
                candidatos[k][0],
                candidatos[k][1],
                candidatos[k][2].parte,
                candidatos[k][2].texto,
                dict(candidatos[k][2].meta),
                score[k],
            )
            for k in melhores
        ]

    def registrar_revisao(self, revisao: RevisaoAta) -> bool:
        k = (revisao.rascunho_id, revisao.versao_ata)
        if k in self._revisoes:
            return False
        self._revisoes[k] = revisao
        return True

    def trabalhos(self) -> list[dict[str, Any]]:
        return [{k: t[k] for k in ("id", "tipo", "chave", "estado", "tentativas", "erro")} for t in self._trab.values()]
