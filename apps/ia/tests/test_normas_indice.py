"""B.4b — a versão vigente de uma norma de referência entra no índice como `dispositivo`, por norma."""

from __future__ import annotations

from typing import Any

import httpx
from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.armazem.porta import Armazem
from oplenario_ia.busca.indice import trechos_de_norma
from oplenario_ia.config import Config
from oplenario_ia.fronteira.contrato import DispositivosDaNorma
from test_busca import EMB, OUTRA, armazem, buscar, montar  # noqa: F401 (fixture)
from test_trabalhador import ENTE, CoreFalso

NORMA = "c1000000-0000-0000-0000-000000000001"
V1 = "d1000000-0000-0000-0000-000000000001"
V2 = "d2000000-0000-0000-0000-000000000002"


def _norma(versao: str, dispositivos: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "norma-id": NORMA,
        "versao-id": versao,
        "especie": "regimento_interno",
        "titulo": "Regimento Interno",
        "consolidada-ate": "2026-06-30",
        "dispositivos": dispositivos,
    }


V1_DISP = [
    {"endereco": "preambulo", "rotulo": "preâmbulo", "tipo": "preambulo", "texto": "", "agrupador": None},
    {
        "endereco": "art2",
        "rotulo": "art. 2º",
        "tipo": "artigo",
        "texto": "As sessões são públicas.",
        "agrupador": "CAPÍTULO I — DAS SESSÕES",
    },
    {
        "endereco": "art2_par1u",
        "rotulo": "art. 2º, parágrafo único",
        "tipo": "paragrafo",
        "texto": "A sessão secreta depende de deliberação do Plenário.",
        "agrupador": "CAPÍTULO I — DAS SESSÕES",
    },
]


def test_trechos_um_por_dispositivo_com_titulo_e_rotulo() -> None:
    ts = trechos_de_norma(DispositivosDaNorma.model_validate(_norma(V1, V1_DISP)))
    assert [t.texto for t in ts] == [
        "Regimento Interno, art. 2º: As sessões são públicas.",
        "Regimento Interno, art. 2º, parágrafo único: A sessão secreta depende de deliberação do Plenário.",
    ], "dispositivo sem texto (o preâmbulo vazio) não entra"
    assert ts[1].meta["endereco"] == "art2_par1u"
    assert ts[1].meta["versao-id"] == V1
    assert ts[0].meta["consolidada-ate"] == "2026-06-30"


class CoreComNorma(CoreFalso):
    def __init__(self) -> None:
        super().__init__()
        self.eventos = []
        self.normas: dict[str, dict[str, Any]] = {}

    def vigente(self, seq: int, versao: str) -> None:
        self.eventos.append(
            {
                "seq": seq,
                "ente-id": ENTE,
                "tipo": "NormaVigente",
                "versao": 1,
                "chave": f"NormaVigente:v1:{versao}",
                "criado-em": "2026-09-27T03:00:00Z",
                "payload": {"norma-id": NORMA, "versao-id": versao, "especie": "regimento_interno"},
            }
        )

    def __call__(self, req: httpx.Request) -> httpx.Response:
        p = req.url.path
        if p.startswith(f"/integracao/ia/v1/entes/{ENTE}/normas/versoes/"):
            vid = p.split("/")[-2]
            return httpx.Response(200, json=self.normas[vid]) if vid in self.normas else httpx.Response(404, json={})
        return super().__call__(req)


def test_a_vigente_entra_e_a_nova_substitui() -> None:
    core = CoreComNorma()
    core.normas[V1] = _norma(V1, V1_DISP)
    core.vigente(1, V1)
    t, arm = montar(core)
    t.ciclo()
    r = buscar(arm, "sessões públicas", tipos=("dispositivo",))
    assert r and r[0].ref_id == NORMA and r[0].meta["endereco"] == "art2"
    assert buscar(arm, "sessões públicas") == [], "a busca padrão (proposição, transcrição) não traz dispositivo"

    core.normas.pop(V1)  # substituída: o core não entrega mais
    core.normas[V2] = _norma(
        V2,
        [
            {
                "endereco": "art2",
                "rotulo": "art. 2º",
                "tipo": "artigo",
                "texto": "As sessões são transmitidas pela internet.",
                "agrupador": None,
            }
        ],
    )
    core.vigente(2, V2)
    t.ciclo()
    assert buscar(arm, "públicas deliberação", tipos=("dispositivo",)) == [], "o texto da versão anterior saiu"
    novo = buscar(arm, "transmitidas internet", tipos=("dispositivo",))
    assert novo and novo[0].meta["versao-id"] == V2


def test_versao_que_ja_nao_vale_conclui_sem_indexar() -> None:
    core = CoreComNorma()
    core.vigente(1, V1)  # o core responde 404: já foi substituída
    t, arm = montar(core)
    t.ciclo()
    assert {x["tipo"]: x["estado"] for x in arm.trabalhos()} == {"indexar_norma": "concluido"}
    assert buscar(arm, "sessões", tipos=("dispositivo",)) == []


def test_armazem_aceita_dispositivo(armazem: Armazem) -> None:  # noqa: F811 (fixture)
    ts = trechos_de_norma(DispositivosDaNorma.model_validate(_norma(V1, V1_DISP)))
    armazem.indexar(ENTE, "dispositivo", NORMA, ts, EMB.embed([x.texto for x in ts], "documento"), EMB.modelo)
    r = buscar(armazem, "sessão secreta", tipos=("dispositivo",))
    assert r and r[0].meta["endereco"] == "art2_par1u"
    assert buscar(armazem, "sessão secreta", tipos=("dispositivo",), ente=OUTRA) == [], "só na Casa"


def test_api_busca_so_traz_dispositivo_quando_pedido() -> None:
    arm = ArmazemMemoria()
    ts = trechos_de_norma(DispositivosDaNorma.model_validate(_norma(V1, V1_DISP)))
    arm.indexar(ENTE, "dispositivo", NORMA, ts, EMB.embed([x.texto for x in ts], "documento"), EMB.modelo)
    c = TestClient(criar_app(Config(segredo="s"), arm, EMB))
    h = {"Authorization": "Bearer s"}
    padrao = c.post(f"/v1/entes/{ENTE}/busca", json={"consulta": "sessões públicas"}, headers=h).json()
    assert padrao["resultados"] == []
    pedido = c.post(
        f"/v1/entes/{ENTE}/busca", json={"consulta": "sessões públicas", "tipos": ["dispositivo"]}, headers=h
    )
    assert [x["tipo"] for x in pedido.json()["resultados"]][:1] == ["dispositivo"]
