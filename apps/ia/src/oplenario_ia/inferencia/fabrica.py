"""A única fábrica de portas — onde a config de deploy escolhe o fornecedor (Eixo 10)."""

from __future__ import annotations

from oplenario_ia.ata import fake as ata_fake
from oplenario_ia.ata.redacao import OPERACAO as ATA_REDIGIR
from oplenario_ia.config import Config
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.inferencia.porta import PortaInferencia


def criar_porta(config: Config) -> PortaInferencia:
    if config.vendor == "fake":
        # o fake responde cada capacidade com o seu roteiro determinístico — a tela mostra o caminho inteiro sem
        # fornecedor real (o padrão do deploy até o `[GAP]` jurídico fechar)
        return PortaFake({ATA_REDIGIR: ata_fake.redigir})
    if config.vendor == "anthropic":
        # import tardio: o SDK do fornecedor só carrega quando o deploy o escolhe
        from oplenario_ia.inferencia.anthropic_adapter import PortaAnthropic

        return PortaAnthropic(config.modelo, timeout_s=config.timeout_s)
    raise ValueError(f"vendor desconhecido: {config.vendor}")  # pragma: no cover — o Literal já barra
