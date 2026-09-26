"""O fornecedor FAKE determinístico — testes, CI, avaliação e o padrão de deploy (nada sai do cluster).

Guarda tudo o que recebeu (é assim que os testes PROVAM que conteúdo sigiloso nunca chegou ao fornecedor) e
responde por roteiro: um texto fixo por operação, uma função, ou um eco curto. Tokens = palavras, só para o registro
de custo ter número.
"""

from __future__ import annotations

from collections.abc import Callable

from oplenario_ia.erros import ErroIA
from oplenario_ia.inferencia.modelo import Parada, PedidoInferencia, RespostaInferencia, Uso

Roteiro = str | Callable[[PedidoInferencia], str] | ErroIA


class PortaFake:
    def __init__(
        self,
        roteiros: dict[str, Roteiro] | None = None,
        *,
        vendor: str = "fake",
        modelo: str = "fake-1",
        parada: Parada = "fim",
    ) -> None:
        self._roteiros = dict(roteiros or {})
        self._vendor = vendor
        self._modelo = modelo
        self._parada = parada
        self.recebidos: list[PedidoInferencia] = []

    @property
    def vendor(self) -> str:
        return self._vendor

    def gerar(self, pedido: PedidoInferencia) -> RespostaInferencia:
        self.recebidos.append(pedido)
        roteiro = self._roteiros.get(pedido.operacao)
        if isinstance(roteiro, ErroIA):
            raise roteiro
        if roteiro is None:
            texto = f"[{self._vendor}:{pedido.operacao}] {len(pedido.conteudo)} peça(s) recebida(s)"
        elif isinstance(roteiro, str):
            texto = roteiro
        else:
            texto = roteiro(pedido)
        entrada = sum(len(t.split()) for t in [pedido.instrucoes, *pedido.conteudo])
        return RespostaInferencia(
            texto=texto,
            parada=self._parada,
            vendor=self._vendor,
            modelo=self._modelo,
            uso=Uso(entrada=entrada, saida=len(texto.split())),
            latencia_ms=0,
        )
