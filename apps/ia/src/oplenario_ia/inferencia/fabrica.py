"""A única fábrica de portas — onde a config de deploy escolhe o fornecedor (Eixo 10)."""

from __future__ import annotations

from oplenario_ia.config import Config
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.inferencia.porta import PortaInferencia


def criar_porta(config: Config) -> PortaInferencia:
    if config.vendor == "fake":
        return PortaFake()
    if config.vendor == "anthropic":
        # import tardio: o SDK do fornecedor só carrega quando o deploy o escolhe
        from oplenario_ia.inferencia.anthropic_adapter import PortaAnthropic

        return PortaAnthropic(config.modelo, timeout_s=config.timeout_s)
    raise ValueError(f"vendor desconhecido: {config.vendor}")  # pragma: no cover — o Literal já barra
