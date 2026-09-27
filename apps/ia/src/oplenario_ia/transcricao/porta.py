"""As portas de ASR e diarização. Recebem o caminho de um arquivo de áudio/vídeo (o container bruto do OBS)."""

from __future__ import annotations

from pathlib import Path
from typing import Protocol

from oplenario_ia.transcricao.modelo import Frase, Voz


class Transcritor(Protocol):
    @property
    def modelo(self) -> str: ...

    def transcrever(self, arquivo: Path, idioma: str) -> list[Frase]:
        """Frases com início/fim/texto. Lança `ErroIA` categorizado (entrada = áudio ilegível)."""
        ...


class Diarizador(Protocol):
    @property
    def modelo(self) -> str: ...

    def diarizar(self, arquivo: Path) -> list[Voz]: ...
