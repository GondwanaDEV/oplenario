"""Os tipos da transcrição. Tempos em SEGUNDOS a partir do início do arquivo gravado."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Frase:
    """Saída do ASR: um trecho de fala contínua (o VAD corta nas pausas) com o texto reconhecido."""

    inicio: float
    fim: float
    texto: str
    confianca: float | None = None


@dataclass(frozen=True)
class Voz:
    """Saída da diarização: quem fala quando, sem saber quem é ("SPK_0")."""

    inicio: float
    fim: float
    grupo: str


@dataclass(frozen=True)
class Palavra:
    """A âncora do Caminho C: um orador tinha a palavra na tribuna neste intervalo (registro da Mesa)."""

    inicio: float
    fim: float
    orador_id: str
    orador_nome: str | None


@dataclass(frozen=True)
class Trecho:
    """Uma frase da transcrição final, com o orador atribuído — ou sem (None): melhor "não sei" do que pôr na ata a
    fala na boca da pessoa errada."""

    inicio: float
    fim: float
    texto: str
    grupo: str | None
    orador_id: str | None
    orador_nome: str | None
    confianca: float | None = None


def sobreposicao(a_ini: float, a_fim: float, b_ini: float, b_fim: float) -> float:
    return max(0.0, min(a_fim, b_fim) - max(a_ini, b_ini))
