"""Envio da gravação ao core: `POST /gravacoes` com o arquivo no corpo (streaming) e a metadata na query.

Credencial, em ordem: um token fixo (`OPLENARIO_TOKEN`, para testes) ou as credenciais de cliente OIDC da Casa
(`client_credentials` — a conta de serviço de papel `captacao`, que só envia arquivos; ADR-0007). O token OIDC é
reaproveitado até perto de expirar.

Falha tem dois tipos: TRANSITÓRIA (rede, 5xx, 408, 429 — tenta de novo mais tarde) e DEFINITIVA (outro 4xx — tentar
de novo não muda nada; o arquivo é marcado e alguém precisa olhar).
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import BinaryIO, Protocol

from oplenario_captacao import __version__
from oplenario_captacao.nome_obs import iso

TRANSITORIOS = {408, 425, 429, 500, 502, 503, 504}


class FalhaEnvio(Exception):
    def __init__(self, mensagem: str, *, transitoria: bool, status: int | None = None) -> None:
        super().__init__(mensagem)
        self.transitoria = transitoria
        self.status = status


class Credencial(Protocol):
    def token(self) -> str: ...


@dataclass
class TokenFixo:
    valor: str

    def token(self) -> str:
        return self.valor


class ClienteOidc:
    """Credenciais de cliente (RFC 6749 §4.4). Guarda o token até 30 s antes de expirar."""

    def __init__(
        self,
        url_token: str,
        client_id: str,
        client_secret: str,
        *,
        abrir: Callable[..., BinaryIO] = urllib.request.urlopen,
        relogio: Callable[[], float] = time.monotonic,
    ) -> None:
        self._url = url_token
        self._id = client_id
        self._segredo = client_secret
        self._abrir = abrir
        self._relogio = relogio
        self._token: str | None = None
        self._expira = 0.0

    def token(self) -> str:
        if self._token is not None and self._relogio() < self._expira - 30:
            return self._token
        corpo = urllib.parse.urlencode(
            {"grant_type": "client_credentials", "client_id": self._id, "client_secret": self._segredo}
        ).encode()
        req = urllib.request.Request(self._url, data=corpo, method="POST")
        req.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            with self._abrir(req, timeout=30) as r:
                dados = json.loads(r.read())
        except urllib.error.HTTPError as e:
            raise FalhaEnvio(
                f"o servidor de identidade recusou a credencial ({e.code})", transitoria=e.code >= 500, status=e.code
            ) from e
        except (urllib.error.URLError, OSError) as e:
            raise FalhaEnvio(f"servidor de identidade inalcançável: {e}", transitoria=True) from e
        self._token = str(dados["access_token"])
        self._expira = self._relogio() + float(dados.get("expires_in", 60))
        return self._token


@dataclass(frozen=True)
class Metadados:
    iniciou_em: datetime
    encerrou_em: datetime | None = None
    fonte: str = "gravacao_local_pos_sessao"
    sessao_id: str | None = None
    acesso_restrito: bool = False

    def query(self) -> str:
        q: dict[str, str] = {
            "fonte-ingestao": self.fonte,
            "motivo-inicio": "inicio_sessao",
            "iniciou-em": iso(self.iniciou_em),
        }
        if self.encerrou_em is not None:
            q["encerrou-em"] = iso(self.encerrou_em)
            q["motivo-fim"] = "fim_sessao"
        if self.sessao_id:
            q["sessao-id"] = self.sessao_id
        if self.acesso_restrito:
            q["acesso-restrito"] = "true"
        return urllib.parse.urlencode(q)


@dataclass(frozen=True)
class Recibo:
    id: str
    audio_hash: str


def enviar(
    servidor: str,
    credencial: Credencial,
    arquivo: Path,
    meta: Metadados,
    *,
    abrir: Callable[..., BinaryIO] = urllib.request.urlopen,
    timeout_s: float = 3600,
) -> Recibo:
    """Envia o arquivo inteiro, em streaming (o urllib lê do disco em blocos: 2 GiB não passam pela memória)."""
    url = f"{servidor.rstrip('/')}/gravacoes?{meta.query()}"
    tamanho = arquivo.stat().st_size
    with arquivo.open("rb") as corpo:
        req = urllib.request.Request(url, data=corpo, method="POST")
        req.add_header("Authorization", f"Bearer {credencial.token()}")
        req.add_header("Content-Type", "application/octet-stream")
        req.add_header("Content-Length", str(tamanho))
        req.add_header("User-Agent", f"oplenario-captar/{__version__}")
        try:
            with abrir(req, timeout=timeout_s) as r:
                dados = json.loads(r.read())
        except urllib.error.HTTPError as e:
            detalhe = _erro_do_corpo(e)
            raise FalhaEnvio(
                f"o core recusou ({e.code}){detalhe}", transitoria=e.code in TRANSITORIOS, status=e.code
            ) from e
        except (urllib.error.URLError, OSError) as e:
            raise FalhaEnvio(f"core inalcançável: {e}", transitoria=True) from e
    return Recibo(id=str(dados["id"]), audio_hash=str(dados.get("audio-hash", "")))


def _erro_do_corpo(e: urllib.error.HTTPError) -> str:
    try:
        return f": {json.loads(e.read())['erro']}"
    except Exception:
        return ""
