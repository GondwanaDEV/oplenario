"""A borda HTTP do satélite (§22.3.2: síncrono = HTTP/JSON com OpenAPI).

Nesta fatia só a saúde e o contrato de erro. Endpoints nascem com a capacidade que os usa (régua §15). Toda falha
categorizada vira o corpo `ErroEstruturado` com o status HTTP da categoria — nunca um 500 opaco.
"""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from oplenario_ia import __version__
from oplenario_ia.config import Config, carregar
from oplenario_ia.erros import ErroIA, para_estruturado


def criar_app(config: Config | None = None) -> FastAPI:
    cfg = config or carregar()
    app = FastAPI(title="O Plenário — Plataforma de IA", version=__version__)

    @app.exception_handler(ErroIA)
    async def _erro_ia(_: Request, erro: ErroIA) -> JSONResponse:
        status, corpo = para_estruturado(erro)
        return JSONResponse(status_code=status, content=corpo.model_dump(mode="json"))

    @app.get("/saude")
    def saude() -> dict[str, str]:
        # o vendor configurado aparece (é config de deploy, não segredo); a chave de API, nunca
        return {"status": "ok", "versao": __version__, "vendor": cfg.vendor, "modelo": cfg.modelo}

    return app


app = criar_app()
