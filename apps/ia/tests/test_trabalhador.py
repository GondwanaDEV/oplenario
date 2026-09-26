"""O trabalhador da Faixa A de ponta a ponta contra um core FALSO (HTTP de verdade via MockTransport): feed ->
fila -> contexto -> download -> ASR + diarização + Caminho C -> transcrição guardada -> aviso ao core."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx

from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.fronteira.cliente import ClienteCore
from oplenario_ia.trabalhador import MAX_TENTATIVAS, Trabalhador
from oplenario_ia.transcricao.fake import DiarizadorFake, TranscritorFake
from oplenario_ia.transcricao.modelo import Frase, Voz

ENTE = "10000000-0000-0000-0000-000000000001"
SID = "30000000-0000-0000-0000-000000000003"
SEG = "20000000-0000-0000-0000-000000000002"
CTX_URI = f"/integracao/ia/v1/entes/{ENTE}/sessoes/{SID}/contexto"
CONT_URI = f"/integracao/ia/v1/entes/{ENTE}/gravacoes/{SEG}/conteudo"
AUDIO = b"\x1a\x45\xdf\xa3" + b"a" * 5000


class CoreFalso:
    def __init__(self) -> None:
        self.eventos = [self.evento(1)]
        self.recebidos: list[dict[str, Any]] = []
        self.status: dict[str, list[int]] = {}  # rota -> fila de status forçados

    @staticmethod
    def evento(seq: int, seg: str = SEG) -> dict[str, Any]:
        return {
            "seq": seq,
            "ente-id": ENTE,
            "tipo": "GravacaoVinculada",
            "versao": 1,
            "chave": f"GravacaoVinculada:v1:{seg}",
            "criado-em": "2026-09-26T20:00:00Z",
            "payload": {
                "segmento-id": seg,
                "sessao-id": SID,
                "conteudo-uri": f"/integracao/ia/v1/entes/{ENTE}/gravacoes/{seg}/conteudo",
                "contexto-uri": CTX_URI,
            },
        }

    def _forcado(self, rota: str) -> int | None:
        fila = self.status.get(rota)
        return fila.pop(0) if fila else None

    def __call__(self, req: httpx.Request) -> httpx.Response:
        p = req.url.path
        if p == "/integracao/ia/v1/eventos" and req.method == "GET":
            depois = int(req.url.params["depois"])
            evs = [e for e in self.eventos if e["seq"] > depois]
            return httpx.Response(200, json={"eventos": evs, "proximo": evs[-1]["seq"] if evs else depois})
        if p == "/integracao/ia/v1/eventos" and req.method == "POST":
            if (s := self._forcado("POST")) is not None:
                return httpx.Response(s, json={"erro": "forçado"})
            self.recebidos.append(json.loads(req.content))
            return httpx.Response(201, json={"chave": self.recebidos[-1]["chave"], "aplicado": True})
        if p == CTX_URI:
            if (s := self._forcado("contexto")) is not None:
                return httpx.Response(s, json={"erro": "forçado"})
            return httpx.Response(
                200,
                json={
                    "sessao": {
                        "id": SID,
                        "tipo-sessao": "ordinaria",
                        "numero-sequencial": 12,
                        "estado": "encerrada",
                        "aberta-em": "2026-09-22T21:00:00Z",
                        "encerrada-em": "2026-09-23T00:00:00Z",
                    },
                    "segmentos": [
                        {"id": SEG, "iniciou-em": "2026-09-22T20:59:50Z", "encerrou-em": None, "conteudo-uri": CONT_URI}
                    ],
                    "falas": [
                        {
                            "id": "f1",
                            "orador-id": "v-ana",
                            "orador-nome": "Ana Ribeiro",
                            "tipo-fala": "principal",
                            "fase": "expediente",
                            "fala-pai-id": None,
                            "iniciou-em": "2026-09-22T21:00:00Z",
                            "encerrou-em": "2026-09-22T21:01:00Z",
                        },
                        {
                            "id": "f2",
                            "orador-id": "v-bruno",
                            "orador-nome": "Bruno Lima",
                            "tipo-fala": "principal",
                            "fase": "expediente",
                            "fala-pai-id": None,
                            "iniciou-em": "2026-09-22T21:01:10Z",
                            "encerrou-em": None,
                        },
                    ],
                },
            )
        if p.endswith("/conteudo"):
            if (s := self._forcado("conteudo")) is not None:
                return httpx.Response(s, json={"erro": "forçado"})
            return httpx.Response(200, content=AUDIO, headers={"X-Conteudo-Sha256": hashlib.sha256(AUDIO).hexdigest()})
        return httpx.Response(404, json={"erro": "rota desconhecida no core falso"})


class Relogio:
    def __init__(self) -> None:
        self.t = datetime(2026, 9, 26, 22, 0, tzinfo=UTC)

    def __call__(self) -> datetime:
        return self.t


FRASES = [Frase(12, 40, "Senhor presidente, colegas."), Frase(85, 100, "Peço a palavra pela ordem.")]
VOZES = [Voz(10, 70, "SPK_0"), Voz(80, 200, "SPK_1")]


def montar(core: CoreFalso, transcritor: TranscritorFake | None = None) -> tuple[Trabalhador, ArmazemMemoria, Relogio]:
    arm, rel = ArmazemMemoria(), Relogio()
    t = Trabalhador(
        ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(core))),
        arm,
        transcritor or TranscritorFake(FRASES),
        DiarizadorFake(VOZES),
        agora=rel,
    )
    return t, arm, rel


def test_gravacao_vinculada_vira_transcricao_atribuida_e_aviso_ao_core() -> None:
    core = CoreFalso()
    t, arm, _ = montar(core)
    assert t.ciclo() == 2, "transcrever + notificar"
    [aviso] = core.recebidos
    assert aviso["tipo"] == "TranscricaoConcluida" and aviso["ente-id"] == ENTE
    assert aviso["correlation-id"] == f"GravacaoVinculada:v1:{SEG}", "a correlação atravessa a fronteira"
    p = aviso["payload"]
    assert (p["sessao-id"], p["segmento-id"], p["versao-transcricao"], p["n-trechos"]) == (SID, SEG, 1, 2)
    assert p["cobertura-atribuida"] == 1.0 and p["modelo-asr"] == "fake-asr-1"
    g = arm.transcricao(p["transcricao-id"])
    assert g is not None
    assert [x.orador_nome for x in g.trechos] == ["Ana Ribeiro", "Bruno Lima"], (
        "o arquivo começa 10 s antes da abertura: a palavra da Ana (21:00:00) cai em 10–70 s do arquivo"
    )


def test_o_mesmo_evento_no_feed_nao_vira_dois_trabalhos() -> None:
    core = CoreFalso()
    t, arm, _ = montar(core)
    t.ciclo()
    core.eventos.append(CoreFalso.evento(2))  # a mesma gravação de novo (chave igual)
    assert t.ciclo() == 0
    assert arm.cursor() == 2 and len(core.recebidos) == 1


def test_feed_fora_do_ar_nao_impede_processar_a_fila() -> None:
    core = CoreFalso()
    t, _, _ = montar(core)
    t.puxar_feed()
    core.eventos = []
    assert t.ciclo() == 2


def test_falha_de_infra_espera_e_tenta_de_novo_depois_desiste_e_avisa() -> None:
    core = CoreFalso()
    core.status["conteudo"] = [500] * MAX_TENTATIVAS["transcrever"]
    t, arm, rel = montar(core)
    t.ciclo()
    assert arm.trabalhos()[0]["estado"] == "pendente" and arm.trabalhos()[0]["tentativas"] == 1
    assert t.ciclo() == 0, "antes da hora marcada, nada"
    for _ in range(MAX_TENTATIVAS["transcrever"] - 1):
        rel.t += timedelta(hours=1)
        t.ciclo()
    estados = {x["tipo"]: x["estado"] for x in arm.trabalhos()}
    assert estados == {"transcrever": "falhou", "notificar": "concluido"}
    [aviso] = core.recebidos
    assert aviso["tipo"] == "TranscricaoFalhou"
    assert (aviso["payload"]["categoria"], aviso["payload"]["retentavel"]) == ("infraestrutura", True)


def test_audio_ilegivel_falha_na_hora_sem_retry() -> None:
    core = CoreFalso()
    t, arm, _ = montar(core, TranscritorFake(erro=ErroIA(Categoria.ENTRADA, "áudio corrompido", retentavel=False)))
    t.ciclo()
    [aviso] = core.recebidos
    assert (aviso["tipo"], aviso["payload"]["categoria"]) == ("TranscricaoFalhou", "entrada")
    assert [x["tentativas"] for x in arm.trabalhos() if x["tipo"] == "transcrever"] == [1]


def test_sessao_sigilosa_descarta_sem_avisar_ninguem() -> None:
    core = CoreFalso()
    core.status["contexto"] = [403]
    t, arm, _ = montar(core)
    t.ciclo()
    assert core.recebidos == []
    assert arm.trabalhos()[0]["estado"] == "descartado"


def test_core_fora_ao_avisar_nao_transcreve_de_novo() -> None:
    core = CoreFalso()
    core.status["POST"] = [401, 500]  # credencial rotacionada, depois instabilidade
    transcritor = TranscritorFake(FRASES)
    t, _arm, rel = montar(core, transcritor)
    t.ciclo()
    rel.t += timedelta(hours=1)
    t.ciclo()
    rel.t += timedelta(hours=1)
    t.ciclo()
    assert len(transcritor.chamadas) == 1, "a transcrição cara foi feita uma vez só"
    assert [r["tipo"] for r in core.recebidos] == ["TranscricaoConcluida"]


def test_evento_desconhecido_no_feed_e_ignorado_e_o_cursor_anda() -> None:
    core = CoreFalso()
    core.eventos = [CoreFalso.evento(1) | {"tipo": "ResumoCidadaoSolicitado"}]
    t, arm, _ = montar(core)
    assert t.ciclo() == 0
    assert arm.cursor() == 1
