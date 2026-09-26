"""A porta de armazenamento e os seus tipos."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Protocol

from oplenario_ia.transcricao.modelo import Trecho

EstadoTrabalho = str  # "pendente" | "em_curso" | "concluido" | "falhou" | "descartado"


@dataclass(frozen=True)
class NovoTrabalho:
    tipo: str  # "transcrever" | "notificar"
    chave: str  # idempotência: o mesmo evento nunca vira dois trabalhos
    ente_id: str
    payload: dict[str, Any]


@dataclass(frozen=True)
class Trabalho:
    id: int
    tipo: str
    chave: str
    ente_id: str
    payload: dict[str, Any]
    tentativas: int


@dataclass(frozen=True)
class NovaTranscricao:
    ente_id: str
    sessao_id: str
    segmento_id: str
    idioma: str
    duracao_s: float
    modelo_asr: str
    modelo_diarizacao: str | None
    cobertura: float
    trechos: list[Trecho]


@dataclass(frozen=True)
class TranscricaoGuardada:
    id: str
    versao: int
    ente_id: str
    sessao_id: str
    segmento_id: str
    idioma: str
    duracao_s: float
    modelo_asr: str
    modelo_diarizacao: str | None
    cobertura: float
    trechos: list[Trecho] = field(default_factory=list)
    criado_em: datetime | None = None


class Armazem(Protocol):
    def cursor(self) -> int: ...

    def registrar_feed(self, novos: list[NovoTrabalho], proximo: int) -> int:
        """Enfileira (idempotente) e avança o cursor, atomicamente. Devolve quantos trabalhos eram novos."""
        ...

    def proximo(self, agora: datetime) -> Trabalho | None:
        """Reserva o próximo trabalho pendente e vencido (em_curso). None = nada a fazer."""
        ...

    def concluir(
        self, trabalho_id: int, seguintes: list[NovoTrabalho] | None = None, *, estado: str = "concluido"
    ) -> None: ...

    def adiar(self, trabalho_id: int, erro: str, quando: datetime) -> None: ...

    def desistir(self, trabalho_id: int, erro: str, seguintes: list[NovoTrabalho] | None = None) -> None: ...

    def concluir_transcricao(
        self, trabalho_id: int, nova: NovaTranscricao, notificar: Callable[[TranscricaoGuardada], NovoTrabalho]
    ) -> TranscricaoGuardada:
        """Guarda a transcrição (versão = próxima do segmento), conclui o trabalho e enfileira a notificação ao core,
        tudo de uma vez: reiniciar no meio não transcreve de novo nem perde o aviso."""
        ...

    def transcricao(self, transcricao_id: str) -> TranscricaoGuardada | None: ...

    def trabalhos(self) -> list[dict[str, Any]]:
        """Visão de operação (estado, tentativas, último erro) — sem conteúdo."""
        ...
