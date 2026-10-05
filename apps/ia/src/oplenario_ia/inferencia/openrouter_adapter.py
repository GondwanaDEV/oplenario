"""Adaptador do OpenRouter — o gateway por onde a plataforma fala com os modelos de linguagem (ADR-0023).

Decisões (ADR-0023, sobre a ADR-0006):
- API de chat do OpenRouter (`/chat/completions`, formato OpenAI) por `httpx`, a mesma biblioteca da fronteira com o
  core: é o formato que serve QUALQUER modelo do catálogo. Não há SDK oficial estável; o contrato é HTTP+JSON.
- Um `model` por requisição, nunca `models` (o fallback de modelo do OpenRouter): o modelo não troca em silêncio
  (§22.11.8). O failover de modelo é NOSSO (§22.3.5, Eixo 13): os `reservas` da config, tentados em ordem, uma vez
  cada, só quando o modelo anterior falha de forma retentável (429, 503, 408, 5xx, rede, timeout, saída vazia) ou
  some (404, modelo ou provedor inexistente); nunca em erro de entrada (400, 403) nem de conta (401, 402). Cada
  troca vai ao log, e o modelo registrado é o que atendeu. Sem reservas, uma tentativa só.
- Roteamento travado em TODA requisição. Política `zdr` (padrão): `data_collection: "deny"` e `zdr: true` (nenhum
  provedor que guarde ou treine com o dado), `require_parameters: true` (nenhum que ignore um parâmetro do pedido), e
  `only` com a lista de provedores aprovados quando a config a define. O failover entre provedores fica restrito a
  essa lista. Política `excecao-gratuita` (EXCEÇÃO TEMPORÁRIA à ADR-0023, decisão do dono do produto, 05/10/2026):
  só `require_parameters` (e `only`), porque os provedores gratuitos dos melhores modelos não cumprem ZDR nem "sem
  coleta"; a config só a aceita com modelos `:free` da lista própria.
- Proveniência: o modelo e o PROVEDOR que de fato atenderam vêm da resposta (`model`, `provider`), não da config.
- O custo informado pelo OpenRouter (`usage.cost`) viaja na resposta; a taxa da plataforma é somada na tabela.
- Erros mapeados às 6 categorias. O `detalhe` nunca leva o conteúdo nem a mensagem do provedor (que pode ecoá-lo).
"""

from __future__ import annotations

import logging
import time
from collections.abc import Sequence
from decimal import Decimal
from typing import Any, Literal

import httpx

from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.inferencia.modelo import Parada, PedidoInferencia, RespostaInferencia, Uso

VENDOR = "openrouter"
URL_PADRAO = "https://openrouter.ai/api/v1"

log = logging.getLogger("oplenario_ia.inferencia.openrouter")

Politica = Literal["zdr", "excecao-gratuita"]


class _FalhaDeStatus(Exception):
    """Interna: o erro categorizado e se ele abre a vez da reserva (o 404 não é retentável, mas troca de modelo)."""

    def __init__(self, erro: ErroIA, status: int) -> None:
        super().__init__(erro.detalhe)
        self.erro = erro
        self.reserva = erro.retentavel or status == 404


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
        politica: Politica = "zdr",
        reservas: Sequence[tuple[str, int]] = (),
        cliente: httpx.Client | None = None,
    ) -> None:
        # o principal e as reservas (slug, folga), na ordem em que são tentados. Num modelo que raciocina,
        # `max_tokens` cobre raciocinio + resposta: a folga de cada um e' somada ao limite do pedido
        self._modelos: list[tuple[str, int]] = [(modelo, folga_raciocinio), *reservas]
        self._politica: Politica = politica
        self._chave = chave  # OPENROUTER_API_KEY, vinda do cofre (Eixo 11f)
        self._url = url.rstrip("/")
        self._provedores = list(provedores or [])
        self._cliente = cliente or httpx.Client(timeout=timeout_s)

    @property
    def vendor(self) -> str:
        return VENDOR

    @property
    def modelos(self) -> list[str]:
        """Os slugs na ordem de tentativa: o principal, depois as reservas."""
        return [m for m, _ in self._modelos]

    def corpo(self, pedido: PedidoInferencia, indice: int = 0) -> dict[str, Any]:
        modelo, folga = self._modelos[indice]
        # `require_parameters`: só atende quem honra todos os parâmetros do pedido (ex.: `max_tokens`), em vez de um
        # provedor que os ignore em silêncio. Na exceção gratuita, ZDR e "sem coleta" ficam de fora (ADR-0023, exceção
        # temporária): nenhum provedor gratuito dos modelos escolhidos os cumpre.
        provider: dict[str, Any] = (
            {"require_parameters": True}
            if self._politica == "excecao-gratuita"
            else {"data_collection": "deny", "zdr": True, "require_parameters": True}
        )
        if self._provedores:
            provider["only"] = self._provedores
        corpo: dict[str, Any] = {
            "model": modelo,
            "max_tokens": pedido.max_tokens + folga,
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
        # a latência registrada é a que a pessoa esperou: desde a primeira tentativa, reservas incluídas
        inicio = time.monotonic()
        ultimo = len(self._modelos) - 1
        for indice, (modelo, _) in enumerate(self._modelos):
            try:
                return self._tentar(pedido, indice, inicio)
            except _FalhaDeStatus as f:
                erro, reserva = f.erro, f.reserva
            except ErroIA as e:
                erro, reserva = e, e.retentavel
            if not reserva or indice == ultimo:
                raise erro
            # o detalhe nunca leva conteúdo (B4): só o slug e a categoria
            log.warning(
                "modelo %s falhou (%s: %s); tentando a reserva %s",
                modelo,
                erro.categoria,
                erro.detalhe,
                self._modelos[indice + 1][0],
            )
        raise AssertionError("inalcançável: há sempre ao menos um modelo")  # pragma: no cover

    def _tentar(self, pedido: PedidoInferencia, indice: int, inicio: float) -> RespostaInferencia:
        try:
            r = self._cliente.post(
                f"{self._url}/chat/completions",
                json=self.corpo(pedido, indice),
                headers={"Authorization": f"Bearer {self._chave}", "X-Title": "O Plenario"},
            )
        except httpx.TimeoutException as e:
            raise ErroIA(Categoria.INFRAESTRUTURA, "timeout do fornecedor", retentavel=True, vendor=VENDOR) from e
        except httpx.TransportError as e:
            raise ErroIA(
                Categoria.INFRAESTRUTURA, "falha de rede até o fornecedor", retentavel=True, vendor=VENDOR
            ) from e
        if r.status_code != 200:
            raise _FalhaDeStatus(_erro_status(r.status_code), r.status_code)
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
            status = codigo if isinstance(codigo, int) else 502
            raise _FalhaDeStatus(_erro_status(status), status)
        return _normalizar(dados, int((time.monotonic() - inicio) * 1000), self._modelos[indice][0])


def _normalizar(dados: dict[str, Any], latencia_ms: int, pedido_modelo: str) -> RespostaInferencia:
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
        # quem atendeu, pela resposta; sem o campo, o slug pedido (um modelo por requisição, nunca `models`)
        modelo=str(dados.get("model") or pedido_modelo),
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
