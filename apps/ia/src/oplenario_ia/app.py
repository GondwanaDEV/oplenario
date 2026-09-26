"""A borda HTTP do satélite (§22.3.2: síncrono = HTTP/JSON com OpenAPI).

`/saude` é pública. As rotas `/v1/*` são de SERVIÇO: só o core as chama, com o mesmo segredo compartilhado da
fronteira (ADR-0008), comparado em tempo constante; sem segredo configurado respondem 503. O tenant vem explícito no
caminho e é conferido contra o dado — transcrição de outra Casa responde 404, como se não existisse. Toda falha
categorizada vira `ErroEstruturado` com o status da categoria — nunca um 500 opaco.
"""

from __future__ import annotations

import hmac
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from oplenario_ia import __version__
from oplenario_ia.armazem.porta import Armazem
from oplenario_ia.ata.redacao import pontos_a_confirmar, texto_limpo
from oplenario_ia.config import Config, carregar
from oplenario_ia.erros import ErroIA, para_estruturado


def _armazem_do_config(cfg: Config) -> Armazem | None:
    if not cfg.database_url:
        return None
    from oplenario_ia.armazem.postgres import ArmazemPostgres

    return ArmazemPostgres(cfg.database_url)


def criar_app(config: Config | None = None, armazem: Armazem | None = None) -> FastAPI:
    cfg = config or carregar()
    arm = armazem if armazem is not None else _armazem_do_config(cfg)
    app = FastAPI(title="O Plenário — Plataforma de IA", version=__version__)

    @app.exception_handler(ErroIA)
    async def _erro_ia(_: Request, erro: ErroIA) -> JSONResponse:
        status, corpo = para_estruturado(erro)
        return JSONResponse(status_code=status, content=corpo.model_dump(mode="json"))

    def servico(authorization: Annotated[str | None, Header()] = None) -> None:
        if not cfg.segredo:
            raise HTTPException(503, "integração com o core desligada")
        recebido = (authorization or "").removeprefix("Bearer ")
        if not hmac.compare_digest(recebido.encode(), cfg.segredo.encode()):
            raise HTTPException(401, "credencial de serviço inválida")

    @app.get("/saude")
    def saude() -> dict[str, str]:
        # o vendor configurado aparece (é config de deploy, não segredo); a chave de API, nunca
        return {"status": "ok", "versao": __version__, "vendor": cfg.vendor, "modelo": cfg.modelo}

    @app.get("/v1/entes/{ente_id}/transcricoes/{transcricao_id}", dependencies=[Depends(servico)])
    def transcricao(ente_id: str, transcricao_id: str) -> dict[str, Any]:
        if arm is None:
            raise HTTPException(503, "armazenamento do satélite não configurado")
        g = arm.transcricao(transcricao_id)
        if g is None or g.ente_id != ente_id:
            raise HTTPException(404, "transcrição não encontrada")
        return {
            "id": g.id,
            "versao": g.versao,
            "sessao-id": g.sessao_id,
            "segmento-id": g.segmento_id,
            "idioma": g.idioma,
            "duracao-s": g.duracao_s,
            "modelo-asr": g.modelo_asr,
            "modelo-diarizacao": g.modelo_diarizacao,
            "cobertura": g.cobertura,
            "criado-em": g.criado_em.isoformat() if g.criado_em else None,
            "trechos": [
                {
                    "inicio": t.inicio,
                    "fim": t.fim,
                    "texto": t.texto,
                    "grupo": t.grupo,
                    "orador-id": t.orador_id,
                    "orador-nome": t.orador_nome,
                }
                for t in g.trechos
            ],
        }

    @app.get("/v1/entes/{ente_id}/atas/rascunhos/{rascunho_id}", dependencies=[Depends(servico)])
    def rascunho_ata(ente_id: str, rascunho_id: str) -> dict[str, Any]:
        """O rascunho da ata para a tela de revisão do core (A.6b): o texto com as marcas de citação, o texto LIMPO
        (o que vai para o editor), cada citação com o resultado da conferência, os parágrafos sem fonte e os pontos a
        confirmar. De outra Casa: 404."""
        if arm is None:
            raise HTTPException(503, "armazenamento do satélite não configurado")
        g = arm.rascunho(rascunho_id)
        if g is None or g.ente_id != ente_id:
            raise HTTPException(404, "rascunho não encontrado")
        return {
            "id": g.id,
            "sessao-id": g.sessao_id,
            "solicitacao-id": g.solicitacao_id,
            "texto": g.texto,
            "texto-limpo": texto_limpo(g.texto),
            "prompt-versao": g.prompt_versao,
            "vendor": g.vendor,
            "modelo": g.modelo,
            "incerteza": {"nivel": g.incerteza["nivel"], "motivos": g.incerteza.get("motivos", [])},
            "citacoes": [
                {
                    "fonte-id": c["fonte_id"],
                    "trecho": c.get("trecho"),
                    "inicio": c["inicio"],
                    "fim": c["fim"],
                    "status": c["status"],
                    "rotulo": c.get("rotulo"),
                }
                for c in g.citacoes
            ],
            "paragrafos-sem-fonte": g.paragrafos_sem_fonte,
            "pontos-a-confirmar": pontos_a_confirmar(g.texto),
            "criado-em": g.criado_em.isoformat() if g.criado_em else None,
        }

    return app


app = criar_app()
