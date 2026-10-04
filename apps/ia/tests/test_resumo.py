"""O RESUMO CIDADÃO (Faixa A / A.8): as peças que vão ao núcleo (o texto da proposição como conteúdo de terceiro), o
rascunho do fake conferido de verdade, o trabalho `redigir_resumo` contra o core falso (uma vez por versão do texto),
o armazém (memória e Postgres — a mesma bateria) e a leitura do rascunho pelo core."""

from __future__ import annotations

import os
from collections.abc import Iterator
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.armazem.porta import Armazem, NovoResumo, NovoTrabalho
from oplenario_ia.busca.embeddings import EmbedderFake
from oplenario_ia.confianca.registro import RegistroMemoria
from oplenario_ia.config import Config
from oplenario_ia.fronteira.cliente import ClienteCore
from oplenario_ia.fronteira.contrato import TextoProposicao
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.resumo import fake
from oplenario_ia.resumo.redacao import OPERACAO, TETO_PARTE, identificacao, partes, pedido_de_resumo
from oplenario_ia.trabalhador import Trabalhador
from oplenario_ia.transcricao.fake import DiarizadorFake, TranscritorFake
from test_trabalhador import ENTE, FRASES, VOZES, CoreFalso, Relogio

URL = os.environ.get("OPLENARIO_IA_DATABASE_URL_TESTE")
OUTRA = "20000000-0000-0000-0000-00000000000f"
P1 = "a1000000-0000-0000-0000-000000000001"

TEXTO = (
    "Art. 1º Fica instituído o Programa Municipal de Hortas Comunitárias.\n"
    "Art. 2º O Executivo poderá ceder terrenos públicos sem uso para o cultivo pela comunidade.\n"
    "§ 1º A cessão será gratuita e por prazo determinado.\n\n"
    "Art. 3º Esta Lei entra em vigor na data de sua publicação."
)


def texto_proposicao(texto: str = TEXTO, sha: str = "sha256:aa") -> TextoProposicao:
    return TextoProposicao.model_validate(
        {
            "proposicao-id": P1,
            "tipo": "projeto_lei",
            "ano": 2026,
            "sequencial": 7,
            "ementa": "Institui o Programa Municipal de Hortas Comunitárias.",
            "autor-texto": "Ver. Ana Ribeiro",
            "texto": texto,
            "texto-sha256": sha,
        }
    )


# ---------- as peças ----------


def test_partes_separam_dispositivos_e_cortam_o_que_e_longo_demais() -> None:
    assert partes(TEXTO) == [
        "Art. 1º Fica instituído o Programa Municipal de Hortas Comunitárias.",
        "Art. 2º O Executivo poderá ceder terrenos públicos sem uso para o cultivo pela comunidade.",
        "§ 1º A cessão será gratuita e por prazo determinado.",
        "Art. 3º Esta Lei entra em vigor na data de sua publicação.",
    ]
    longo = " ".join(["Esta é uma frase de enchimento do texto."] * 80)
    ps = partes(longo)
    assert len(ps) > 1 and all(len(p) <= TETO_PARTE for p in ps)
    assert " ".join(ps) == longo, "cortar não perde texto"


def test_pedido_leva_ementa_e_dispositivos_como_conteudo_de_terceiro_citavel() -> None:
    p = pedido_de_resumo(texto_proposicao(), ENTE, "c-1")
    assert p.operacao == OPERACAO and p.ente_id == ENTE
    assert [x.fonte.id for x in p.pecas if x.fonte] == [
        f"proposicao:{P1}#ementa",
        f"proposicao:{P1}#p1",
        f"proposicao:{P1}#p2",
        f"proposicao:{P1}#p3",
        f"proposicao:{P1}#p4",
    ]
    assert all(x.proveniencia and x.proveniencia.terceiro for x in p.pecas), "texto do autor: dado, nunca instrução"
    assert "Projeto de Lei nº 7/2026, de autoria de Ver. Ana Ribeiro." in p.pecas[0].texto
    assert p.pecas[2].fonte and p.pecas[2].fonte.rotulo == "Projeto de Lei nº 7/2026 — Art. 2º"
    assert identificacao(texto_proposicao()) == "Projeto de Lei nº 7/2026"


def test_proposicao_so_com_ementa_ainda_vira_pedido() -> None:
    t = texto_proposicao(texto="")
    assert [x.fonte.id for x in pedido_de_resumo(t, ENTE, "c").pecas if x.fonte] == [f"proposicao:{P1}#ementa"]


def test_fake_cita_de_verdade_e_deixa_um_paragrafo_sem_fonte() -> None:
    n = Nucleo(PortaFake({OPERACAO: fake.redigir}), RegistroMemoria())
    r = n.executar(pedido_de_resumo(texto_proposicao(), ENTE, "c-1"), "por_paragrafo")
    assert not hasattr(r, "motivo"), r
    assert [c.status for c in r.citacoes] == ["conferida", "conferida"]  # type: ignore[union-attr]
    assert r.paragrafos_sem_fonte == [2]  # type: ignore[union-attr]
    assert r.incerteza.nivel == "revisar_com_atencao"  # type: ignore[union-attr]
    assert set(r.incerteza.motivos) == {"sem_fonte", "conteudo_de_terceiro"}  # type: ignore[union-attr]


# ---------- o armazém (mesma bateria em memória e em Postgres) ----------


@pytest.fixture(params=["memoria", "postgres"])
def armazem(request: pytest.FixtureRequest) -> Iterator[Armazem]:
    if request.param == "memoria":
        yield ArmazemMemoria()
        return
    if not URL:
        pytest.skip("OPLENARIO_IA_DATABASE_URL_TESTE não definida")
    import psycopg

    from oplenario_ia.armazem.postgres import ArmazemPostgres

    with psycopg.connect(URL, autocommit=True) as c:
        c.execute("DROP SCHEMA IF EXISTS ia CASCADE")
    yield ArmazemPostgres(URL)


def novo(sha: str) -> NovoResumo:
    return NovoResumo(
        ente_id=ENTE,
        proposicao_id=P1,
        texto_base_sha256=sha,
        execucao_id="e-1",
        texto="Resumo. [[proposicao:x#ementa | Resumo]]",
        citacoes=[{"fonte_id": "proposicao:x#ementa", "status": "conferida", "inicio": 8, "fim": 40}],
        paragrafos_sem_fonte=[],
        incerteza={"nivel": "normal", "motivos": []},
        vendor="fake",
        modelo="fake-1",
        prompt_versao="resumo-v1",
    )


def test_armazem_guarda_o_resumo_conclui_e_avisa_junto(armazem: Armazem) -> None:
    armazem.registrar_feed([NovoTrabalho("redigir_resumo", "k1", ENTE, {"proposicao-id": P1})], 1)
    t = armazem.proximo(Relogio()())
    assert t is not None
    assert armazem.ultimo_resumo(ENTE, P1) is None
    g = armazem.concluir_resumo(t.id, novo("sha256:aa"), lambda g: NovoTrabalho("notificar", f"n:{g.id}", ENTE, {}))
    assert armazem.resumo(g.id) == g and g.criado_em is not None
    assert armazem.resumo("nao-e-uuid") is None
    t2 = armazem.proximo(Relogio()())
    assert t2 is not None and t2.tipo == "notificar", "o aviso entrou na mesma operação"
    armazem.concluir(t2.id)
    armazem.registrar_feed([NovoTrabalho("redigir_resumo", "k2", ENTE, {"proposicao-id": P1})], 2)
    t3 = armazem.proximo(Relogio()())
    assert t3 is not None
    g2 = armazem.concluir_resumo(t3.id, novo("sha256:bb"), lambda g: NovoTrabalho("notificar", f"n:{g.id}", ENTE, {}))
    ultimo = armazem.ultimo_resumo(ENTE, P1)
    assert ultimo is not None and ultimo.id == g2.id and ultimo.texto_base_sha256 == "sha256:bb"
    assert armazem.ultimo_resumo(OUTRA, P1) is None, "só na Casa"


# ---------- o trabalhador ----------


class CoreComTexto(CoreFalso):
    def __init__(self) -> None:
        super().__init__()
        self.eventos = []
        self.texto: dict[str, Any] = texto_proposicao().model_dump(by_alias=True)
        self.status_texto: int = 200

    def __call__(self, req: httpx.Request) -> httpx.Response:
        if req.url.path == f"/integracao/ia/v1/entes/{ENTE}/proposicoes/{P1}/texto":
            if self.status_texto != 200:
                return httpx.Response(self.status_texto, json={"erro": "forçado"})
            return httpx.Response(200, json=self.texto)
        return super().__call__(req)

    def proposicao(self, seq: int, tipo: str = "ProposicaoProtocolada") -> None:
        self.eventos.append(
            {
                "seq": seq,
                "ente-id": ENTE,
                "tipo": tipo,
                "versao": 1,
                "chave": f"{tipo}:v1:{P1}:{seq}",
                "criado-em": "2026-09-27T01:00:00Z",
                "payload": {"proposicao-id": P1, "ementa": "Institui hortas.", "autor-texto": "Ver. Ana Ribeiro"},
            }
        )


def montar(core: CoreComTexto) -> tuple[Trabalhador, ArmazemMemoria]:
    arm = ArmazemMemoria()
    t = Trabalhador(
        ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(core))),
        arm,
        TranscritorFake(FRASES),
        DiarizadorFake(VOZES),
        nucleo=Nucleo(PortaFake({OPERACAO: fake.redigir}), RegistroMemoria()),
        embedder=EmbedderFake(),
        agora=Relogio(),
    )
    return t, arm


def test_proposicao_protocolada_vira_rascunho_e_aviso_ao_core() -> None:
    core = CoreComTexto()
    core.proposicao(1)
    t, arm = montar(core)
    t.ciclo()
    [aviso] = core.recebidos
    assert aviso["tipo"] == "ResumoCidadaoPronto" and aviso["ente-id"] == ENTE
    p = aviso["payload"]
    assert (p["proposicao-id"], p["texto-base-sha256"], p["prompt-versao"]) == (P1, "sha256:aa", "resumo-v1")
    assert (p["n-citacoes"], p["n-citacoes-conferidas"], p["n-paragrafos-sem-fonte"]) == (2, 2, 1)
    assert p["incerteza"] == "revisar_com_atencao" and p["modelo-llm-id"].startswith("fake:")
    g = arm.resumo(p["rascunho-id"])
    assert g is not None and g.proposicao_id == P1 and "[[proposicao:" in g.texto
    assert aviso["chave"] == f"ResumoCidadaoPronto:v1:{g.id}"


def test_mesma_versao_do_texto_nao_redige_de_novo_e_texto_novo_redige() -> None:
    core = CoreComTexto()
    core.proposicao(1)
    t, arm = montar(core)
    t.ciclo()
    core.proposicao(2, "ProposicaoAtualizada")  # edição só de metadado: o texto é o mesmo
    t.ciclo()
    assert len(core.recebidos) == 1, "a mesma versão do texto já tem rascunho"
    core.texto = texto_proposicao(TEXTO.replace("gratuita", "onerosa"), "sha256:bb").model_dump(by_alias=True)
    core.proposicao(3, "ProposicaoAtualizada")
    t.ciclo()
    assert [a["tipo"] for a in core.recebidos] == ["ResumoCidadaoPronto", "ResumoCidadaoPronto"]
    ultimo = arm.ultimo_resumo(ENTE, P1)
    assert ultimo is not None and ultimo.texto_base_sha256 == "sha256:bb"


def test_proposicao_que_o_core_nao_acha_vira_resumo_falhou_sem_insistir() -> None:
    core = CoreComTexto()
    core.status_texto = 404
    core.proposicao(1)
    t, arm = montar(core)
    t.ciclo()
    [aviso] = core.recebidos
    assert aviso["tipo"] == "ResumoFalhou"
    assert (aviso["payload"]["proposicao-id"], aviso["payload"]["categoria"]) == (P1, "entrada")
    assert aviso["payload"]["retentavel"] is False
    assert {x["tipo"]: x["estado"] for x in arm.trabalhos()}["redigir_resumo"] == "falhou"


# ---------- a leitura pelo core ----------


def test_api_le_o_rascunho_com_texto_limpo_e_so_na_casa() -> None:
    core = CoreComTexto()
    core.proposicao(1)
    t, arm = montar(core)
    t.ciclo()
    rid = core.recebidos[0]["payload"]["rascunho-id"]
    c = TestClient(criar_app(Config(segredo="s"), arm, EmbedderFake()))
    h = {"Authorization": "Bearer s"}
    r = c.get(f"/v1/entes/{ENTE}/resumos/rascunhos/{rid}", headers=h)
    assert r.status_code == 200
    b = r.json()
    assert b["proposicao-id"] == P1 and b["texto-base-sha256"] == "sha256:aa"
    # 8.4: o core leva este id à tela para o "Reportar erro" (POST .../execucoes/{id}/reportes)
    assert b["execucao-id"] and b["execucao-id"] == arm.resumo(rid).execucao_id
    assert "[[" not in b["texto-limpo"] and b["texto-limpo"].startswith("Esta proposição trata do seguinte:")
    assert [x["status"] for x in b["citacoes"]] == ["conferida", "conferida"]
    assert b["paragrafos-sem-fonte"] == [2] and b["incerteza"]["nivel"] == "revisar_com_atencao"
    assert c.get(f"/v1/entes/{OUTRA}/resumos/rascunhos/{rid}", headers=h).status_code == 404
    assert c.get(f"/v1/entes/{ENTE}/resumos/rascunhos/{rid}").status_code == 401


def test_fake_pula_titulo_solto_e_cita_o_primeiro_dispositivo_com_conteudo() -> None:
    n = Nucleo(PortaFake({OPERACAO: fake.redigir}), RegistroMemoria())
    r = n.executar(pedido_de_resumo(texto_proposicao(texto="Lei\n\n" + TEXTO), ENTE, "c-1"), "por_paragrafo")
    assert [c.status for c in r.citacoes] == ["conferida", "conferida"]  # type: ignore[union-attr]
    assert "Art. 1º Fica instituído" in r.texto  # type: ignore[union-attr]
