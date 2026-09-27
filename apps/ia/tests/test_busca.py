"""O índice único e a busca intra-câmara (A.4): embeddings fake determinísticos, corte em trechos, fusão RRF, o armazém
(memória e Postgres com pgvector — a MESMA bateria), a indexação pelo trabalhador e a API de busca."""

from __future__ import annotations

import os
from collections.abc import Iterator

import httpx
import pytest
from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.armazem.porta import Armazem, TranscricaoGuardada, TrechoIndice
from oplenario_ia.busca.embeddings import EmbedderFake, normalizar
from oplenario_ia.busca.indice import TETO_TRECHO, fundir, trechos_de_proposicao, trechos_de_transcricao
from oplenario_ia.config import Config
from oplenario_ia.fronteira.cliente import ClienteCore
from oplenario_ia.trabalhador import Trabalhador
from oplenario_ia.transcricao.fake import DiarizadorFake, TranscritorFake
from oplenario_ia.transcricao.modelo import Trecho
from test_trabalhador import ENTE, FRASES, SEG, VOZES, CoreFalso, Relogio

URL = os.environ.get("OPLENARIO_IA_DATABASE_URL_TESTE")
OUTRA = "20000000-0000-0000-0000-00000000000f"
P1 = "a1000000-0000-0000-0000-000000000001"
P2 = "a2000000-0000-0000-0000-000000000002"
P3 = "a3000000-0000-0000-0000-000000000003"
EMB = EmbedderFake()


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


def indexar(arm: Armazem, ente: str, tipo: str, ref: str, textos: list[str], emb: EmbedderFake = EMB) -> None:
    trechos = [TrechoIndice(i, t, {"n": i}) for i, t in enumerate(textos)]
    arm.indexar(ente, tipo, ref, trechos, emb.embed(textos, "documento"), emb.modelo)


def buscar(arm: Armazem, consulta: str, ente: str = ENTE, tipos: tuple[str, ...] = ("proposicao", "transcricao")):
    return arm.buscar(
        ente, consulta, EMB.embed([consulta], "consulta")[0], EMB.modelo, list(tipos), 10, EMB.distancia_maxima
    )


# ---------- peças puras ----------


def test_fake_normaliza_acento_e_radical_e_aproxima_o_que_e_parecido() -> None:
    assert normalizar("Vereadores") == normalizar("vereador") == ["veread"]
    a, b, c = EMB.embed(["merenda escolar nas escolas", "a merenda das escolas", "iluminação da praça"], "documento")
    dist = lambda x, y: 1 - sum(p * q for p, q in zip(x, y, strict=True))  # noqa: E731
    assert dist(a, b) < dist(a, c)
    assert EMB.embed(["x y z"], "consulta") == EMB.embed(["x y z"], "documento"), "determinístico"
    assert abs(sum(x * x for x in a) - 1) < 1e-9


def test_transcricao_vira_um_trecho_por_bloco_de_fala_com_quem_e_quando() -> None:
    longa = " ".join(["Peço a palavra para falar da merenda escolar."] * 40)
    t = TranscricaoGuardada(
        "t1",
        1,
        ENTE,
        "s1",
        "seg1",
        "pt",
        100.0,
        "fake",
        None,
        1.0,
        [
            Trecho(0, 5, "Declaro aberta a sessão.", "S0", "v-p", "Presidente Lúcia"),
            Trecho(6, 90, longa, "S1", "v-a", "Ana Ribeiro"),
        ],
    )
    trechos = trechos_de_transcricao(t)
    assert trechos[0].texto == "Declaro aberta a sessão." and trechos[0].meta["orador"] == "Presidente Lúcia"
    assert len(trechos) > 2 and all(len(x.texto) <= TETO_TRECHO for x in trechos), "fala longa em janelas"
    assert {x.meta["segmento-id"] for x in trechos} == {"seg1"} and [x.parte for x in trechos] == list(
        range(len(trechos))
    )
    assert trechos_de_proposicao("Denomina rua.", "Ver. Ana")[0].texto == "Denomina rua. Autoria: Ver. Ana."


def test_rrf_premia_quem_aparece_nas_duas_listas() -> None:
    s = fundir([["a", "b", "c"], ["c", "d"]])
    assert max(s, key=lambda k: s[k]) == "c"


# ---------- o armazém ----------


def test_busca_hibrida_so_na_casa_filtra_tipo_e_reindexar_substitui(armazem: Armazem) -> None:
    indexar(armazem, ENTE, "proposicao", P1, ["Dispõe sobre a merenda escolar nas escolas municipais."])
    indexar(armazem, ENTE, "proposicao", P2, ["Denomina a Rua das Flores no bairro Centro."])
    indexar(armazem, ENTE, "transcricao", P3, ["A vereadora Ana cobrou a merenda das escolas.", "Nada mais."])
    indexar(armazem, OUTRA, "proposicao", "a4000000-0000-0000-0000-000000000004", ["Merenda escolar de outra Casa."])
    r = buscar(armazem, "merenda escolar")
    assert {x.ref_id for x in r} == {P1, P3}, "acha nas duas fontes, e nunca na outra Casa"
    assert r[0].ref_id == P1 and r[0].tipo == "proposicao"
    assert r[0].meta == {"n": 0}
    assert {x.ref_id for x in buscar(armazem, "merenda", tipos=("transcricao",))} == {P3}
    assert buscar(armazem, "xyzzy inexistente") == [], "nada parecido: nada volta (corte de distância)"
    indexar(armazem, ENTE, "proposicao", P1, ["Dispõe sobre o transporte escolar."])
    assert P1 not in {x.ref_id for x in buscar(armazem, "merenda")}, "reindexar substitui o texto velho"
    assert P1 in {x.ref_id for x in buscar(armazem, "transporte")}


def test_vetor_de_outro_modelo_nao_entra_na_comparacao(armazem: Armazem) -> None:
    class OutroModelo(EmbedderFake):
        modelo = "outro-modelo"

    indexar(armazem, ENTE, "proposicao", P1, ["merenda"], OutroModelo())
    vetor = EMB.embed(["merenda"], "consulta")[0]
    r = armazem.buscar(ENTE, "zzz", vetor, EMB.modelo, ["proposicao"], 10, EMB.distancia_maxima)
    assert r == [], "o termo não casa e o vetor é de outro modelo: nada"


# ---------- o trabalhador ----------


def evento_proposicao(
    seq: int, tipo: str = "ProposicaoProtocolada", ementa: str = "Institui o programa de hortas escolares."
):
    return {
        "seq": seq,
        "ente-id": ENTE,
        "tipo": tipo,
        "versao": 1,
        "chave": f"{tipo}:v1:{P1}:{seq}",
        "criado-em": "2026-09-26T21:00:00Z",
        "payload": {"proposicao-id": P1, "ementa": ementa, "autor-texto": "Ver. Ana Ribeiro"},
    }


def montar(core: CoreFalso) -> tuple[Trabalhador, ArmazemMemoria]:
    arm = ArmazemMemoria()
    t = Trabalhador(
        ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(core))),
        arm,
        TranscritorFake(FRASES),
        DiarizadorFake(VOZES),
        embedder=EMB,
        agora=Relogio(),
    )
    return t, arm


def test_transcricao_e_proposicao_entram_no_indice() -> None:
    core = CoreFalso()
    core.eventos.append(evento_proposicao(2))
    t, arm = montar(core)
    t.ciclo()
    assert {x.tipo for x in buscar(arm, "hortas escolares")} == {"proposicao"}
    fala = buscar(arm, "presidente colegas")
    assert fala and fala[0].tipo == "transcricao" and fala[0].ref_id == SEG, "indexada pelo segmento"
    assert fala[0].meta["orador"] == "Ana Ribeiro"
    core.eventos.append(evento_proposicao(3, "ProposicaoAtualizada", "Institui o programa de compostagem."))
    t.ciclo()
    assert buscar(arm, "hortas", tipos=("proposicao",)) == [] and buscar(arm, "compostagem", tipos=("proposicao",))
    assert t.reindexar_transcricoes() == 0, "a transcrição já está na fila (idempotente pela chave)"


def test_sem_embedder_a_indexacao_desiste_sem_derrubar_a_transcricao() -> None:
    core = CoreFalso()
    arm = ArmazemMemoria()
    t = Trabalhador(
        ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(core))),
        arm,
        TranscritorFake(FRASES),
        DiarizadorFake(VOZES),
        agora=Relogio(),
    )
    t.ciclo()
    estados = {x["tipo"]: x["estado"] for x in arm.trabalhos()}
    assert estados == {"transcrever": "concluido", "notificar": "concluido", "indexar_transcricao": "falhou"}


# ---------- a API ----------


def test_api_de_busca_com_segredo_e_tenant() -> None:
    arm = ArmazemMemoria()
    indexar(arm, ENTE, "proposicao", P1, ["Dispõe sobre a merenda escolar."])
    c = TestClient(criar_app(Config(segredo="s"), arm, EMB))
    h = {"Authorization": "Bearer s"}
    r = c.post(f"/v1/entes/{ENTE}/busca", json={"consulta": "merenda"}, headers=h)
    assert r.status_code == 200
    b = r.json()
    assert b["modelo"] == EMB.modelo
    [x] = b["resultados"]
    assert (x["tipo"], x["ref-id"], x["texto"]) == ("proposicao", P1, "Dispõe sobre a merenda escolar.")
    assert c.post(f"/v1/entes/{OUTRA}/busca", json={"consulta": "merenda"}, headers=h).json()["resultados"] == []
    assert c.post(f"/v1/entes/{ENTE}/busca", json={"consulta": "m"}, headers=h).status_code == 422
    assert c.post(f"/v1/entes/{ENTE}/busca", json={"consulta": "merenda"}).status_code == 401
