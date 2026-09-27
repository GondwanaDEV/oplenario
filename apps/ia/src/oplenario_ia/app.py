"""A borda HTTP do satélite (§22.3.2: síncrono = HTTP/JSON com OpenAPI).

`/saude` é pública. As rotas `/v1/*` são de SERVIÇO: só o core as chama, com o mesmo segredo compartilhado da
fronteira (ADR-0008), comparado em tempo constante; sem segredo configurado respondem 503. O tenant vem explícito no
caminho e é conferido contra o dado — transcrição de outra Casa responde 404, como se não existisse. Toda falha
categorizada vira `ErroEstruturado` com o status da categoria — nunca um 500 opaco.
"""

from __future__ import annotations

import hmac
from collections.abc import Callable
from typing import Annotated, Any, Protocol

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from oplenario_ia import __version__
from oplenario_ia.agente import laco
from oplenario_ia.agente.mcp import ClienteMCP
from oplenario_ia.armazem.porta import Armazem, Resultado
from oplenario_ia.ata.redacao import pontos_a_confirmar, texto_limpo
from oplenario_ia.busca.embeddings import Embedder, criar_embedder
from oplenario_ia.busca.indice import TIPOS, TIPOS_PADRAO
from oplenario_ia.confianca.consumo import consumo_do_mes
from oplenario_ia.confianca.cota import Cota, Fonte
from oplenario_ia.confianca.registro import ConsultaConsumo, RegistroConfianca, RegistroJsonl, RegistroMemoria
from oplenario_ia.config import Config, carregar
from oplenario_ia.erros import ErroIA, para_estruturado
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.requerimento import copiloto
from oplenario_ia.requerimento.copiloto import PedidoCopiloto


def _armazem_do_config(cfg: Config) -> Armazem | None:
    if not cfg.database_url:
        return None
    from oplenario_ia.armazem.postgres import ArmazemPostgres

    return ArmazemPostgres(cfg.database_url)


class PedidoAgente(BaseModel):
    pergunta: str = Field(min_length=2, max_length=1_000)
    credencial: str = Field(min_length=20, max_length=200)
    correlation_id: str = Field(min_length=1, max_length=100)


class PedidoBusca(BaseModel):
    consulta: str = Field(min_length=2, max_length=300)
    tipos: list[str] = Field(default_factory=lambda: list(TIPOS_PADRAO))
    limite: int = Field(default=20, ge=1, le=50)


class _Registro(RegistroConfianca, ConsultaConsumo, Protocol):
    """O registro que a API usa: anexa execuções e responde o consumo da Casa (B.9)."""


def criar_app(
    config: Config | None = None,
    armazem: Armazem | None = None,
    embedder: Embedder | None = None,
    *,
    nucleo: Nucleo | None = None,
    mcp_de: Callable[[str], laco.Porta] | None = None,
    registro: _Registro | None = None,
) -> FastAPI:
    cfg = config or carregar()
    arm = armazem if armazem is not None else _armazem_do_config(cfg)
    emb = embedder or criar_embedder(cfg)
    nucleos: list[Nucleo] = [nucleo] if nucleo is not None else []
    registros: list[_Registro] = [registro] if registro is not None else []

    def registro_do_app() -> _Registro:
        # B.9: com o Postgres do satélite, o MESMO registro do trabalhador — a cota da Casa vê o gasto de todos
        if not registros:
            if cfg.database_url:
                from oplenario_ia.confianca.registro_postgres import RegistroPostgres

                registros.append(RegistroPostgres(cfg.database_url))
            else:
                registros.append(RegistroJsonl(cfg.registro_jsonl) if cfg.registro_jsonl else RegistroMemoria())
        return registros[0]

    def nucleo_do_app() -> Nucleo:
        # o núcleo real nasce na primeira execução do agente (a porta do fornecedor só carrega se alguém a usar)
        if not nucleos:
            reg = registro_do_app()
            cota = Cota(Fonte(arm, reg)) if arm is not None else None
            nucleos.append(Nucleo(criar_porta(cfg), reg, cota=cota))
        return nucleos[0]

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

    @app.get("/v1/entes/{ente_id}/resumos/rascunhos/{rascunho_id}", dependencies=[Depends(servico)])
    def rascunho_resumo(ente_id: str, rascunho_id: str) -> dict[str, Any]:
        """O rascunho do resumo cidadão para a revisão no core (A.8): o texto com as marcas de citação, o texto LIMPO
        (o que vai para o editor), cada citação conferida e os parágrafos sem fonte. De outra Casa: 404."""
        if arm is None:
            raise HTTPException(503, "armazenamento do satélite não configurado")
        g = arm.resumo(rascunho_id)
        if g is None or g.ente_id != ente_id:
            raise HTTPException(404, "rascunho não encontrado")
        return {
            "id": g.id,
            "proposicao-id": g.proposicao_id,
            "texto-base-sha256": g.texto_base_sha256,
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
            "criado-em": g.criado_em.isoformat() if g.criado_em else None,
        }

    @app.post("/v1/entes/{ente_id}/busca", dependencies=[Depends(servico)])
    def busca(ente_id: str, pedido: PedidoBusca) -> dict[str, Any]:
        """A busca intra-câmara (A.4/A.5; §22.3.4): só na Casa do caminho, híbrida (termo exato + sentido). Devolve
        ids com o trecho e o score; o core completa com os dados das tabelas dele (número, ementa, sessão)."""
        if arm is None:
            raise HTTPException(503, "armazenamento do satélite não configurado")
        tipos = [t for t in pedido.tipos if t in TIPOS]
        vetor = emb.embed([pedido.consulta], "consulta")[0]
        achados = arm.buscar(ente_id, pedido.consulta, vetor, emb.modelo, tipos, pedido.limite, emb.distancia_maxima)
        return {
            "modelo": emb.modelo,
            "resultados": [
                {
                    "tipo": r.tipo,
                    "ref-id": r.ref_id,
                    "parte": r.parte,
                    "texto": r.texto,
                    "meta": r.meta,
                    "score": round(r.score, 6),
                }
                for r in achados
            ],
        }

    @app.post("/v1/entes/{ente_id}/agente/execucoes", dependencies=[Depends(servico)])
    def agente(ente_id: str, pedido: PedidoAgente) -> dict[str, Any]:
        """Uma execução do agente (B.3; docs/25 Eixo 5.2: a tela fala com o core, o core chama o satélite). A
        credencial delegada vem do core (ADR-0010) e é a ÚNICA coisa com que o agente chega ao core: as ferramentas
        rodam lá, como a pessoa. Nada aqui é guardado além do registro da execução (texto fica fora, B4)."""
        if not cfg.core_url:
            raise HTTPException(503, "integração com o core desligada")
        mcp = mcp_de(pedido.credencial) if mcp_de else ClienteMCP(cfg.core_url, pedido.credencial)
        r = laco.executar(nucleo_do_app(), mcp, pedido.pergunta, ente_id, pedido.correlation_id)
        a = r.artefato
        return {
            "passos": [
                {
                    "ferramenta": p.ferramenta,
                    "argumentos": p.argumentos,
                    "ok": p.ok,
                    "enviado-ao-modelo": p.enviado_ao_modelo,
                }
                for p in r.passos
            ],
            "resposta": None
            if a is None
            else {
                "execucao-id": a.execucao_id,
                "texto": a.texto,
                "citacoes": [
                    {
                        "fonte-id": c.fonte_id,
                        "rotulo": r.fontes.get(c.fonte_id),
                        "trecho": c.trecho,
                        "status": c.status,
                    }
                    for c in a.citacoes
                ],
                "paragrafos-sem-fonte": a.paragrafos_sem_fonte,
                "incerteza": a.incerteza.nivel,
                "modelo": a.modelo,
                "contaminado": a.contaminado,
            },
            "indisponivel": None
            if r.indisponivel is None
            else {"motivo": r.indisponivel.motivo, "mensagem": r.indisponivel.mensagem},
        }

    @app.get("/v1/entes/{ente_id}/consumo", dependencies=[Depends(servico)])
    def consumo(ente_id: str, mes: str | None = None) -> dict[str, Any]:
        """B.9: o consumo de IA da Casa no mês (civil, America/Fortaleza) × o orçamento, por capacidade, com a revisão
        humana e os erros reportados. Só contagens e valores (8.5) — o insumo do painel da Casa no core."""
        orcamento = arm.orcamento(ente_id) if arm is not None else None
        try:
            c = consumo_do_mes(registro_do_app(), ente_id, orcamento, mes)
        except ValueError as e:
            raise HTTPException(400, str(e)) from e
        return c.model_dump(mode="json", by_alias=True)

    @app.post("/v1/entes/{ente_id}/requerimentos/rascunhos", dependencies=[Depends(servico)])
    def rascunho_requerimento(ente_id: str, pedido: PedidoCopiloto) -> dict[str, Any]:
        """O copiloto do requerimento (B.7): o pedido em palavras vira modelo, ementa, campos e justificativa
        citada. Nada é guardado além do registro da execução (B4); o core confere o preenchimento contra os modelos
        da Casa."""

        def buscar(consulta: str) -> list[Resultado]:
            assert arm is not None
            vetor = emb.embed([consulta], "consulta")[0]
            return arm.buscar(ente_id, consulta, vetor, emb.modelo, ["dispositivo"], 4, emb.distancia_maxima)

        r = copiloto.rascunhar(nucleo_do_app(), pedido, ente_id, buscar if arm is not None else None)
        p, j = r.preenchimento, r.justificativa
        return {
            "preenchimento": None if p is None else {"modelo-id": p.modelo_id, "ementa": p.ementa, "campos": p.campos},
            "justificativa": None
            if j is None
            else {
                "campo": r.campo_justificativa,
                "execucao-id": j.execucao_id,
                "citacoes": [
                    {
                        "fonte-id": c.fonte_id,
                        "rotulo": r.fontes.get(c.fonte_id),
                        "trecho": c.trecho,
                        "status": c.status,
                    }
                    for c in j.citacoes
                ],
                "paragrafos-sem-fonte": j.paragrafos_sem_fonte,
                "incerteza": j.incerteza.nivel,
                "modelo": j.modelo,
                "contaminado": j.contaminado,
            },
            "indisponivel": None
            if r.indisponivel is None
            else {"motivo": r.indisponivel.motivo, "mensagem": r.indisponivel.mensagem},
        }

    return app


app = criar_app()
