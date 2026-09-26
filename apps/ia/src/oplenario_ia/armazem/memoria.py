"""Adaptador em memória (testes). Mesma semântica do Postgres, sem concorrência entre processos."""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import replace
from datetime import datetime
from typing import Any

from oplenario_ia.armazem.porta import NovaTranscricao, NovoTrabalho, Trabalho, TranscricaoGuardada


class ArmazemMemoria:
    def __init__(self) -> None:
        self._cursor = 0
        self._seq = 0
        self._trab: dict[int, dict[str, Any]] = {}
        self._chaves: set[str] = set()
        self._transc: dict[str, TranscricaoGuardada] = {}

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
        g = TranscricaoGuardada(id=str(uuid.uuid4()), versao=versao, **nova.__dict__)
        self._transc[g.id] = g
        self.concluir(trabalho_id, [notificar(g)])
        return g

    def transcricao(self, transcricao_id: str) -> TranscricaoGuardada | None:
        g = self._transc.get(transcricao_id)
        return replace(g) if g else None

    def trabalhos(self) -> list[dict[str, Any]]:
        return [{k: t[k] for k in ("id", "tipo", "chave", "estado", "tentativas", "erro")} for t in self._trab.values()]
