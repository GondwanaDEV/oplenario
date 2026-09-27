"""ASR e diarização FAKE, determinísticos: CI, testes e o padrão de deploy (nada de modelo pesado até alguém
configurar). Por roteiro: frases e vozes fixas, ou geradas a partir do tamanho do arquivo."""

from __future__ import annotations

from pathlib import Path

from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.transcricao.modelo import Frase, Voz


class TranscritorFake:
    def __init__(self, frases: list[Frase] | None = None, *, erro: ErroIA | None = None) -> None:
        self._frases = frases
        self._erro = erro
        self.chamadas: list[Path] = []

    @property
    def modelo(self) -> str:
        return "fake-asr-1"

    def transcrever(self, arquivo: Path, idioma: str) -> list[Frase]:
        self.chamadas.append(arquivo)
        if self._erro is not None:
            raise self._erro
        if not arquivo.exists() or arquivo.stat().st_size == 0:
            raise ErroIA(Categoria.ENTRADA, "arquivo de áudio vazio ou ausente", retentavel=False, vendor="fake")
        if self._frases is not None:
            return list(self._frases)
        return [Frase(0.0, 5.0, f"[transcrição fake de {arquivo.stat().st_size} bytes]")]


class DiarizadorFake:
    def __init__(self, vozes: list[Voz] | None = None) -> None:
        self._vozes = vozes or []

    @property
    def modelo(self) -> str:
        return "fake-diarizacao-1"

    def diarizar(self, arquivo: Path) -> list[Voz]:
        return list(self._vozes)
