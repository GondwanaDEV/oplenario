"""O cliente HTTP do core (ADR-0008). Todas as chamadas levam o segredo de serviço; toda falha volta como `ErroIA`
nas categorias do §22.3.5, para a fila decidir se tenta de novo:

- rede, timeout, 5xx  -> infraestrutura (retentável)
- 429                 -> sobrecarga (retentável)
- 503                 -> infraestrutura, retentável (indisponível agora: deploy, proxy, integração desligada)
- 401                 -> infraestrutura, NÃO retentável (credencial errada é config, não acaso)
- 403 no conteúdo     -> `Sigiloso` (não é falha: a gravação/sessão não vai para a IA, e o trabalho é descartado)
- outros 4xx          -> entrada (não retentável)
"""

from __future__ import annotations

import contextlib
import hashlib
import json
from pathlib import Path
from typing import Any

import httpx

from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.fronteira.contrato import AtaPublicada, ContextoSessao, EventoParaCore, Feed, ReciboCore


class Sigiloso(Exception):
    """O core recusou o conteúdo por sigilo (sessão secreta, gravação restrita) — fail-closed, sem retry."""


def _erro_http(r: httpx.Response, onde: str) -> ErroIA:
    detalhe = f"{onde}: core respondeu {r.status_code}"
    with contextlib.suppress(ValueError, AttributeError):
        detalhe += f" ({r.json().get('erro')})"
    if r.status_code == 429:
        return ErroIA(Categoria.SOBRECARGA, detalhe, retentavel=True, vendor="core")
    if r.status_code >= 500:
        return ErroIA(Categoria.INFRAESTRUTURA, detalhe, retentavel=True, vendor="core")
    if r.status_code == 401:
        return ErroIA(Categoria.INFRAESTRUTURA, detalhe, retentavel=False, vendor="core")
    return ErroIA(Categoria.ENTRADA, detalhe, retentavel=False, vendor="core")


class ClienteCore:
    def __init__(
        self, base_url: str, segredo: str, *, cliente: httpx.Client | None = None, timeout_s: float = 30
    ) -> None:
        self._http = cliente or httpx.Client(timeout=timeout_s)
        self._base = base_url.rstrip("/")
        self._cab = {"Authorization": f"Bearer {segredo}"}

    def _pedir(self, metodo: str, caminho: str, onde: str, **kw: Any) -> httpx.Response:
        try:
            r = self._http.request(metodo, f"{self._base}{caminho}", headers=self._cab, **kw)
        except httpx.HTTPError as e:
            raise ErroIA(
                Categoria.INFRAESTRUTURA,
                f"{onde}: core inalcançável ({type(e).__name__})",
                retentavel=True,
                vendor="core",
            ) from e
        if r.status_code == 403:
            raise Sigiloso(onde)
        if r.status_code >= 400:
            raise _erro_http(r, onde)
        return r

    def feed(self, depois: int, limite: int = 100) -> Feed:
        r = self._pedir("GET", "/integracao/ia/v1/eventos", "feed", params={"depois": depois, "limite": limite})
        return Feed.model_validate(r.json())

    def contexto(self, uri: str) -> ContextoSessao:
        return ContextoSessao.model_validate(self._pedir("GET", uri, "contexto").json())

    def ata_publicada(self, uri: str) -> AtaPublicada:
        return AtaPublicada.model_validate(self._pedir("GET", uri, "ata").json())

    def baixar(self, uri: str, destino: Path) -> str:
        """Baixa a gravação em streaming para `destino` e confere o sha256 que o core informa. Devolve o hash."""
        h = hashlib.sha256()
        try:
            with self._http.stream("GET", f"{self._base}{uri}", headers=self._cab) as r:
                if r.status_code == 403:
                    raise Sigiloso("conteudo")
                if r.status_code >= 400:
                    r.read()
                    raise _erro_http(r, "conteudo")
                esperado = r.headers.get("x-conteudo-sha256")
                with destino.open("wb") as f:
                    for bloco in r.iter_bytes(1 << 20):
                        h.update(bloco)
                        f.write(bloco)
        except httpx.HTTPError as e:
            raise ErroIA(
                Categoria.INFRAESTRUTURA,
                f"conteudo: download interrompido ({type(e).__name__})",
                retentavel=True,
                vendor="core",
            ) from e
        obtido = h.hexdigest()
        if esperado and esperado != obtido:
            raise ErroIA(
                Categoria.INFRAESTRUTURA,
                "conteudo: sha256 não confere (download corrompido)",
                retentavel=True,
                vendor="core",
            )
        return obtido

    def enviar(self, evento: EventoParaCore) -> ReciboCore:
        corpo = json.loads(evento.model_dump_json(by_alias=True))
        r = self._pedir("POST", "/integracao/ia/v1/eventos", "caixa-de-entrada", json=corpo)
        return ReciboCore.model_validate(r.json())
