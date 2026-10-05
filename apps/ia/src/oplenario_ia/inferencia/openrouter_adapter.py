"""Adaptador do OpenRouter — o gateway por onde a plataforma fala com os modelos de linguagem (ADR-0023).

Decisões (ADR-0023, sobre a ADR-0006):
- API de chat do OpenRouter (`/chat/completions`, formato OpenAI) por `httpx`, a mesma biblioteca da fronteira com o
  core: é o formato que serve QUALQUER modelo do catálogo. Não há SDK oficial estável; o contrato é HTTP+JSON.
- Sem retry e sem fallback de modelo: um `model` só, nunca `models` — retry, backoff e failover são política nossa
  (§22.3.5, Eixo 13), e o modelo não troca em silêncio (§22.11.8).
- Roteamento travado em TODA requisição: `data_collection: "deny"` e `zdr: true` (nenhum provedor que guarde ou
  treine com o dado), `require_parameters: true` (nenhum que ignore um parâmetro do pedido), e `only` com a lista
  de provedores aprovados quando a config a define. O failover entre provedores fica restrito a essa lista.
- Proveniência: o modelo e o PROVEDOR que de fato atenderam vêm da resposta (`model`, `provider`), não da config.
- O custo informado pelo OpenRouter (`usage.cost`) viaja na resposta; a taxa da plataforma é somada na tabela.
- Erros mapeados às 6 categorias. O `detalhe` nunca leva o conteúdo nem a mensagem do provedor (que pode ecoá-lo).
"""

from __future__ import annotations

import time
from decimal import Decimal
from typing import Any

import httpx

from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.inferencia.modelo import Parada, PedidoInferencia, RespostaInferencia, Uso

VENDOR = "openrouter"
URL_PADRAO = "https://openrouter.ai/api/v1"

_PARADAS: dict[str, Parada] = {
    "stop": "fim",
    "length": "limite_tokens",
    "content_filter": "recusa",
}


def _erro_status(status: int) -> ErroIA:
    if status == 429:
        return ErroIA(Categoria.SOBRECARGA, "limite de taxa do fornecedor (429)", retentavel=True, vendor=VENDOR)
    if status == 503:
        # nenhum provedor que cumpra o roteamento (lista aprovada, ZDR) está disponível agora
        return ErroIA(Categoria.SOBRECARGA, "nenhum provedor disponível (503)", retentavel=True, vendor=VENDOR)
    if status == 408:
        return ErroIA(Categoria.INFRAESTRUTURA, "timeout do fornecedor (408)", retentavel=True, vendor=VENDOR)
    if status == 402:
        return ErroIA(
            Categoria.INFRAESTRUTURA, "créditos do OpenRouter esgotados (402)", retentavel=False, vendor=VENDOR
        )
    if status in (401, 404):
        # credencial, ou modelo inexistente / sem provedor que cumpra a política de dado: é configuração
        return ErroIA(
            Categoria.INFRAESTRUTURA, f"configuração do fornecedor ({status})", retentavel=False, vendor=VENDOR
        )
    if status == 403:
        return ErroIA(Categoria.ENTRADA, "pedido barrado pela moderação (403)", retentavel=False, vendor=VENDOR)
    if status >= 500:
        return ErroIA(Categoria.INFRAESTRUTURA, f"erro do fornecedor ({status})", retentavel=True, vendor=VENDOR)
    return ErroIA(Categoria.ENTRADA, f"pedido recusado pelo fornecedor ({status})", retentavel=False, vendor=VENDOR)


class PortaOpenRouter:
    def __init__(
        self,
        modelo: str,
        *,
        timeout_s: float,
        chave: str | None,
        url: str = URL_PADRAO,
        provedores: list[str] | None = None,
        folga_raciocinio: int = 0,
        cliente: httpx.Client | None = None,
    ) -> None:
        self._modelo = modelo
        # num modelo que raciocina, `max_tokens` cobre raciocinio + resposta: a folga e' somada ao limite do pedido
        self._folga_raciocinio = folga_raciocinio
        self._chave = chave  # OPENROUTER_API_KEY, vinda do cofre (Eixo 11f)
        self._url = url.rstrip("/")
        self._provedores = list(provedores or [])
        self._cliente = cliente or httpx.Client(timeout=timeout_s)

    @property
    def vendor(self) -> str:
        return VENDOR

    def corpo(self, pedido: PedidoInferencia) -> dict[str, Any]:
        # `require_parameters`: só atende quem honra todos os parâmetros do pedido (ex.: `max_tokens`), em vez de um
        # provedor que os ignore em silêncio
        provider: dict[str, Any] = {"data_collection": "deny", "zdr": True, "require_parameters": True}
        if self._provedores:
            provider["only"] = self._provedores
        corpo: dict[str, Any] = {
            "model": self._modelo,
            "max_tokens": pedido.max_tokens + self._folga_raciocinio,
            "messages": [
                {"role": "system", "content": pedido.instrucoes},
                {"role": "user", "content": [{"type": "text", "text": t} for t in pedido.conteudo]},
            ],
            "provider": provider,
            "usage": {"include": True},
        }
        if pedido.esforco is not None:
            corpo["reasoning"] = {"effort": pedido.esforco}
        return corpo

    def gerar(self, pedido: PedidoInferencia) -> RespostaInferencia:
        if not self._chave:
            # IA fora por configuração: vira "indisponível" (R-IA-1), nunca 500
            raise ErroIA(Categoria.INFRAESTRUTURA, "OPENROUTER_API_KEY ausente", retentavel=False, vendor=VENDOR)
        inicio = time.monotonic()
        try:
            r = self._cliente.post(
                f"{self._url}/chat/completions",
                json=self.corpo(pedido),
                headers={"Authorization": f"Bearer {self._chave}", "X-Title": "O Plenario"},
            )
        except httpx.TimeoutException as e:
            raise ErroIA(Categoria.INFRAESTRUTURA, "timeout do fornecedor", retentavel=True, vendor=VENDOR) from e
        except httpx.TransportError as e:
            raise ErroIA(
                Categoria.INFRAESTRUTURA, "falha de rede até o fornecedor", retentavel=True, vendor=VENDOR
            ) from e
        if r.status_code != 200:
            raise _erro_status(r.status_code)
        try:
            dados = r.json()
        except ValueError as e:
            raise ErroIA(
                Categoria.INFRAESTRUTURA, "resposta ilegível do fornecedor", retentavel=True, vendor=VENDOR
            ) from e
        if not isinstance(dados, dict):
            raise ErroIA(Categoria.INFRAESTRUTURA, "resposta ilegível do fornecedor", retentavel=True, vendor=VENDOR)
        # o OpenRouter pode devolver 200 com o erro no corpo (falha do provedor depois de aceitar o pedido)
        erro = dados.get("error")
        if isinstance(erro, dict):
            codigo = erro.get("code")
            raise _erro_status(codigo if isinstance(codigo, int) else 502)
        return _normalizar(dados, int((time.monotonic() - inicio) * 1000))


def _normalizar(dados: dict[str, Any], latencia_ms: int) -> RespostaInferencia:
    escolhas = dados.get("choices") or []
    if not escolhas:
        raise ErroIA(Categoria.MODELO, "resposta sem escolha", retentavel=True, vendor=VENDOR)
    escolha = escolhas[0]
    mensagem = escolha.get("message") or {}
    if escolha.get("finish_reason") == "error":
        raise ErroIA(Categoria.INFRAESTRUTURA, "o provedor falhou na geração", retentavel=True, vendor=VENDOR)
    parada = _PARADAS.get(escolha.get("finish_reason") or "", "fim")
    if escolha.get("native_finish_reason") == "refusal" or mensagem.get("refusal"):
        parada = "recusa"
    conteudo = mensagem.get("content")
    if isinstance(conteudo, list):
        texto = "".join(p.get("text", "") for p in conteudo if isinstance(p, dict) and p.get("type") == "text")
    else:
        texto = conteudo or ""
    if parada != "recusa" and not texto:
        # o modelo que raciocina ate' o limite devolve so' o raciocinio: o detalhe diz isso (sem o conteudo, B4)
        esgotou = escolha.get("finish_reason") == "length" and bool(mensagem.get("reasoning"))
        raise ErroIA(
            Categoria.MODELO,
            "o modelo esgotou o limite de tokens raciocinando, sem resposta (finish_reason=length)"
            if esgotou
            else f"resposta sem texto (finish_reason={escolha.get('finish_reason')})",
            retentavel=True,
            vendor=VENDOR,
        )
    u = dados.get("usage") or {}
    detalhes = u.get("prompt_tokens_details") or {}
    cache_leitura = int(detalhes.get("cached_tokens") or 0)
    cache_escrita = int(detalhes.get("cache_write_tokens") or 0)
    # no formato OpenAI `prompt_tokens` INCLUI os tokens de cache; o `Uso` os separa (como a Anthropic)
    entrada = max(int(u.get("prompt_tokens") or 0) - cache_leitura - cache_escrita, 0)
    custo = u.get("cost")
    provedor = dados.get("provider")
    return RespostaInferencia(
        texto=texto,
        parada=parada,
        vendor=VENDOR,
        modelo=str(dados.get("model") or ""),
        provedor=provedor if isinstance(provedor, str) and provedor else None,
        uso=Uso(
            entrada=entrada,
            saida=int(u.get("completion_tokens") or 0),
            cache_leitura=cache_leitura,
            cache_escrita=cache_escrita,
        ),
        custo_informado=Decimal(str(custo)) if isinstance(custo, int | float) and not isinstance(custo, bool) else None,
        latencia_ms=latencia_ms,
        id_requisicao=dados.get("id"),
    )
