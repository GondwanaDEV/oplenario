"""O protocolo da porta. Trocar de fornecedor = trocar a implementação (Eixo 10), nada mais."""

from __future__ import annotations

from typing import Protocol

from oplenario_ia.inferencia.modelo import PedidoInferencia, RespostaInferencia


class PortaInferencia(Protocol):
    @property
    def vendor(self) -> str: ...

    def gerar(self, pedido: PedidoInferencia) -> RespostaInferencia:
        """Gera uma resposta ou lança `ErroIA` categorizado. Nunca tenta de novo por conta própria."""
        ...
