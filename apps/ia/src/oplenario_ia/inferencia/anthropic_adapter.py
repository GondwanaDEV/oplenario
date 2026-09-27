"""Adaptador do fornecedor Anthropic (Claude) — SDK oficial `anthropic` 1.x.

Decisões (ADR-0006):
- `max_retries=0`: retry, backoff e failover são política nossa (§22.3.5, Eixo 13), nunca do SDK — senão a falha
  some dentro do cliente e o failover não enxerga.
- Sem fallback de modelo server-side: trocaria o modelo por conta própria, contra "nunca troca de modelo em
  silêncio" (§22.11.8). Recusa volta como `parada="recusa"`, explícita.
- O modelo EFETIVAMENTE usado vem da resposta (`message.model`), não da config.
- Exceções mapeadas às 6 categorias, da mais específica para a mais geral. O `detalhe` nunca leva o conteúdo.
"""

from __future__ import annotations

import time

import anthropic
from anthropic.types import Message, MessageParam, TextBlockParam

from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.inferencia.modelo import Parada, PedidoInferencia, RespostaInferencia, Uso

VENDOR = "anthropic"

_PARADAS: dict[str, Parada] = {
    "end_turn": "fim",
    "stop_sequence": "fim",
    "max_tokens": "limite_tokens",
    "refusal": "recusa",
}


class PortaAnthropic:
    def __init__(self, modelo: str, *, timeout_s: float, cliente: anthropic.Anthropic | None = None) -> None:
        self._modelo = modelo
        # credencial resolvida pelo SDK a partir do ambiente (ANTHROPIC_API_KEY vinda do cofre, Eixo 11f)
        self._cliente = cliente or anthropic.Anthropic(max_retries=0, timeout=timeout_s)

    @property
    def vendor(self) -> str:
        return VENDOR

    def gerar(self, pedido: PedidoInferencia) -> RespostaInferencia:
        conteudo: list[TextBlockParam] = [{"type": "text", "text": t} for t in pedido.conteudo]
        mensagens: list[MessageParam] = [{"role": "user", "content": conteudo}]
        inicio = time.monotonic()
        try:
            if pedido.esforco is not None:
                msg = self._cliente.messages.create(
                    model=self._modelo,
                    max_tokens=pedido.max_tokens,
                    system=pedido.instrucoes,
                    messages=mensagens,
                    output_config={"effort": pedido.esforco},
                )
            else:
                msg = self._cliente.messages.create(
                    model=self._modelo,
                    max_tokens=pedido.max_tokens,
                    system=pedido.instrucoes,
                    messages=mensagens,
                )
        except anthropic.APITimeoutError as e:
            raise ErroIA(Categoria.INFRAESTRUTURA, "timeout do fornecedor", retentavel=True, vendor=VENDOR) from e
        except anthropic.APIConnectionError as e:
            raise ErroIA(
                Categoria.INFRAESTRUTURA, "falha de rede até o fornecedor", retentavel=True, vendor=VENDOR
            ) from e
        except anthropic.RateLimitError as e:
            raise ErroIA(
                Categoria.SOBRECARGA, "limite de taxa do fornecedor (429)", retentavel=True, vendor=VENDOR
            ) from e
        except (anthropic.BadRequestError, anthropic.UnprocessableEntityError) as e:
            raise ErroIA(
                Categoria.ENTRADA,
                f"pedido recusado pelo fornecedor ({e.status_code})",
                retentavel=False,
                vendor=VENDOR,
            ) from e
        except (anthropic.AuthenticationError, anthropic.PermissionDeniedError, anthropic.NotFoundError) as e:
            # credencial, permissão ou modelo inexistente: é configuração, tentar de novo não resolve
            raise ErroIA(
                Categoria.INFRAESTRUTURA,
                f"configuração do fornecedor ({e.status_code})",
                retentavel=False,
                vendor=VENDOR,
            ) from e
        except anthropic.APIStatusError as e:
            if e.status_code == 529:
                raise ErroIA(
                    Categoria.SOBRECARGA, "fornecedor sobrecarregado (529)", retentavel=True, vendor=VENDOR
                ) from e
            retentavel = e.status_code >= 500
            raise ErroIA(
                Categoria.INFRAESTRUTURA,
                f"erro do fornecedor ({e.status_code})",
                retentavel=retentavel,
                vendor=VENDOR,
            ) from e
        return _normalizar(msg, int((time.monotonic() - inicio) * 1000))


def _normalizar(msg: Message, latencia_ms: int) -> RespostaInferencia:
    parada = _PARADAS.get(msg.stop_reason or "", "fim")
    textos = [b.text for b in msg.content if b.type == "text"]
    if parada != "recusa" and not textos:
        raise ErroIA(
            Categoria.MODELO,
            f"resposta sem texto (stop_reason={msg.stop_reason})",
            retentavel=True,
            vendor=VENDOR,
        )
    u = msg.usage
    return RespostaInferencia(
        texto="".join(textos),
        parada=parada,
        vendor=VENDOR,
        modelo=msg.model,
        uso=Uso(
            entrada=u.input_tokens,
            saida=u.output_tokens,
            cache_leitura=u.cache_read_input_tokens or 0,
            cache_escrita=u.cache_creation_input_tokens or 0,
        ),
        latencia_ms=latencia_ms,
        id_requisicao=msg._request_id,
    )
