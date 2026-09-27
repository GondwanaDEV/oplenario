"""Uma porta que envolve outra e guarda cada pedido que atravessou — para a avaliação provar o que chegou (ou nunca
chegou) ao fornecedor, com o fake ou com um fornecedor real."""

from __future__ import annotations

from oplenario_ia.inferencia.modelo import PedidoInferencia, RespostaInferencia
from oplenario_ia.inferencia.porta import PortaInferencia


class PortaGravadora:
    def __init__(self, porta: PortaInferencia) -> None:
        self._porta = porta
        self.recebidos: list[PedidoInferencia] = []

    @property
    def vendor(self) -> str:
        return self._porta.vendor

    def gerar(self, pedido: PedidoInferencia) -> RespostaInferencia:
        self.recebidos.append(pedido)
        return self._porta.gerar(pedido)
