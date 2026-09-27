"""O cliente MCP do satélite (docs/25 Eixo 5.1): fala com o servidor MCP do core — o mesmo que o cliente de fora
usará — com a credencial delegada da execução (ADR-0010) como bearer. Transporte 'Streamable HTTP' sem estado: cada
POST é uma mensagem JSON-RPC 2.0.

Falhas de transporte voltam como `ErroIA` (categorias do §22.3.5); erro de FERRAMENTA (negado, entrada inválida,
nada encontrado) volta como resultado com `ok=False`, para o agente corrigir o rumo. Resultado sem `sigilo` público
marcado pelo core é tratado como restrito (fail-closed, B1): nunca vira peça para o modelo.
"""

from __future__ import annotations

import itertools
from typing import Any

import httpx
from pydantic import BaseModel

from oplenario_ia.erros import Categoria, ErroIA

VERSAO_PROTOCOLO = "2025-06-18"
CAMINHO = "/integracao/ia/v1/mcp"


class Ferramenta(BaseModel):
    nome: str
    descricao: str
    entrada: dict[str, Any]
    classe: str


class ResultadoFerramenta(BaseModel):
    nome: str
    argumentos: dict[str, Any]
    ok: bool
    texto: str
    estruturado: dict[str, Any] | None = None
    origem: str = "interno"
    publico: bool = False


class ClienteMCP:
    def __init__(
        self, base_url: str, credencial: str, *, cliente: httpx.Client | None = None, timeout_s: float = 30
    ) -> None:
        self._http = cliente or httpx.Client(timeout=timeout_s)
        self._url = base_url.rstrip("/") + CAMINHO
        self._cab = {"Authorization": f"Bearer {credencial}", "Content-Type": "application/json"}
        self._ids = itertools.count(1)
        self._iniciado = False

    def _rpc(self, metodo: str, params: dict[str, Any]) -> dict[str, Any]:
        msg = {"jsonrpc": "2.0", "id": next(self._ids), "method": metodo, "params": params}
        try:
            r = self._http.post(self._url, headers=self._cab, json=msg)
        except httpx.HTTPError as e:
            raise ErroIA(
                Categoria.INFRAESTRUTURA, f"mcp: core inalcançável ({type(e).__name__})", retentavel=True, vendor="core"
            ) from e
        if r.status_code == 401:
            raise ErroIA(
                Categoria.INFRAESTRUTURA, "mcp: credencial do agente recusada", retentavel=False, vendor="core"
            )
        if r.status_code >= 500:
            raise ErroIA(
                Categoria.INFRAESTRUTURA, f"mcp: core respondeu {r.status_code}", retentavel=True, vendor="core"
            )
        if r.status_code >= 400:
            raise ErroIA(Categoria.ENTRADA, f"mcp: core respondeu {r.status_code}", retentavel=False, vendor="core")
        corpo: dict[str, Any] = r.json()
        return corpo

    def iniciar(self) -> None:
        if self._iniciado:
            return
        r = self._rpc(
            "initialize",
            {
                "protocolVersion": VERSAO_PROTOCOLO,
                "capabilities": {},
                "clientInfo": {"name": "oplenario-ia", "version": "1"},
            },
        )
        if "error" in r:
            raise ErroIA(Categoria.INFRAESTRUTURA, "mcp: initialize recusado", retentavel=False, vendor="core")
        self._http.post(self._url, headers=self._cab, json={"jsonrpc": "2.0", "method": "notifications/initialized"})
        self._iniciado = True

    def ferramentas(self) -> list[Ferramenta]:
        self.iniciar()
        r = self._rpc("tools/list", {})
        return [
            Ferramenta(
                nome=t["name"],
                descricao=t.get("description", ""),
                entrada=t.get("inputSchema", {}),
                classe=str(t.get("_meta", {}).get("oplenario/classe", "ato")),
            )
            for t in r.get("result", {}).get("tools", [])
        ]

    def chamar(self, nome: str, argumentos: dict[str, Any]) -> ResultadoFerramenta:
        self.iniciar()
        r = self._rpc("tools/call", {"name": nome, "arguments": argumentos})
        if "error" in r:
            return ResultadoFerramenta(nome=nome, argumentos=argumentos, ok=False, texto=str(r["error"].get("message")))
        res = r.get("result", {})
        meta = res.get("_meta") or {}
        texto = " ".join(c.get("text", "") for c in res.get("content", []) if c.get("type") == "text")
        estruturado = res.get("structuredContent")
        return ResultadoFerramenta(
            nome=nome,
            argumentos=argumentos,
            ok=not res.get("isError", False),
            texto=texto,
            estruturado=estruturado if isinstance(estruturado, dict) else None,
            origem=str(meta.get("oplenario/origem", "terceiro")),
            publico=meta.get("oplenario/sigilo") == "publico",
        )
