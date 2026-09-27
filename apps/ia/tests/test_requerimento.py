"""B.7 — o copiloto do requerimento: pedido em palavras → modelo, ementa, campos e justificativa citada. Rascunho que
volta ao formulário; o core confere de novo e o vereador revisa e assina."""

from __future__ import annotations

from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.porta import Resultado
from oplenario_ia.confianca.registro import RegistroMemoria
from oplenario_ia.config import Config
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.requerimento import copiloto
from oplenario_ia.requerimento.copiloto import ModeloRequerimento, PedidoCopiloto

ENTE = "10000000-0000-0000-0000-000000000001"
PEDIDO = "Quero pedir à Secretaria de Obras informações sobre a reforma da praça do Centro."
MODELOS = [
    ModeloRequerimento(id="m-pesar", nome="Requerimento de voto de pesar", campos=["homenageado"]),
    ModeloRequerimento(
        id="m-info", nome="Requerimento de informação", campos=["destinatario", "assunto", "justificativa"]
    ),
    ModeloRequerimento(id="m-livre", nome="Requerimento (texto livre)", campos=["texto"]),
]
LOM_ART = Resultado(
    tipo="dispositivo",
    ref_id="n1",
    parte=0,
    texto=(
        "Lei Orgânica do Município, art. 25: Compete à Câmara pedir informações ao Prefeito sobre fatos da "
        "administração."
    ),
    meta={
        "norma-id": "n1",
        "versao-id": "v1",
        "titulo": "Lei Orgânica do Município",
        "endereco": "art25",
        "rotulo": "art. 25",
        "consolidada-ate": "2026-06-30",
    },
    score=0.9,
)


def _nucleo() -> tuple[Nucleo, PortaFake]:
    p = criar_porta(Config(vendor="fake"))
    assert isinstance(p, PortaFake)
    return Nucleo(p, RegistroMemoria()), p


def _pedido(descricao: str = PEDIDO) -> PedidoCopiloto:
    return PedidoCopiloto(descricao=descricao, modelos=MODELOS, correlation_id="c1")


def test_preenche_o_modelo_certo_e_justifica_citando_a_lom() -> None:
    nucleo, porta = _nucleo()
    consultas: list[str] = []

    def buscar(q: str) -> list[Resultado]:
        consultas.append(q)
        return [LOM_ART] if q == "Requerimento de informação" else []

    r = copiloto.rascunhar(nucleo, _pedido(), ENTE, buscar)
    assert consultas == [
        "Requerimento de informação",
        "Informações sobre a reforma da praça do Centro",
        PEDIDO,
    ], "a base legal se acha primeiro pelo tipo do requerimento, depois pela ementa e pelo pedido"
    assert r.preenchimento is not None
    assert r.preenchimento.modelo_id == "m-info"
    assert r.preenchimento.ementa == "Informações sobre a reforma da praça do Centro"
    assert r.preenchimento.campos["destinatario"] == "Secretaria de Obras"
    assert r.preenchimento.campos["assunto"] == "a reforma da praça do Centro"
    j = r.preenchimento.campos["justificativa"]
    assert "Compete à Câmara pedir informações ao Prefeito" in j
    assert "[[" not in j, "o campo recebe o texto limpo; as marcas de citação ficam na tela"
    assert r.campo_justificativa == "justificativa"
    assert r.justificativa is not None
    assert [(c.fonte_id, c.status) for c in r.justificativa.citacoes] == [("norma:n1#art25", "conferida")]
    assert r.fontes["norma:n1#art25"] == "Lei Orgânica do Município, art. 25 (consolidada até 30/06/2026)"
    assert [p.operacao for p in porta.recebidos] == ["requerimento.preencher", "requerimento.justificar"]


def test_sem_norma_no_indice_a_justificativa_sai_sem_fonte() -> None:
    nucleo, _ = _nucleo()
    r = copiloto.rascunhar(nucleo, _pedido(), ENTE, lambda _q: [])
    assert r.justificativa is not None
    assert r.justificativa.citacoes == []
    assert r.justificativa.paragrafos_sem_fonte == [0]
    assert r.justificativa.incerteza.nivel != "normal"


def test_indice_fora_nao_derruba_o_copiloto() -> None:
    def fora(_q: str) -> list[Resultado]:
        raise RuntimeError("índice fora")

    nucleo, _ = _nucleo()
    r = copiloto.rascunhar(nucleo, _pedido(), ENTE, fora)
    assert r.preenchimento is not None and "justificativa" in r.preenchimento.campos


def test_modelo_sem_campo_de_justificativa_so_preenche() -> None:
    nucleo, porta = _nucleo()
    r = copiloto.rascunhar(nucleo, _pedido("Voto de pesar pelo falecimento do professor Antônio."), ENTE)
    assert r.preenchimento is not None and r.preenchimento.modelo_id == "m-pesar"
    assert r.justificativa is None
    assert [p.operacao for p in porta.recebidos] == ["requerimento.preencher"]


def test_ler_preenchimento_confere_contra_os_modelos() -> None:
    ler = copiloto.ler_preenchimento
    assert ler('{"modelo_id": "inventado", "ementa": "x", "campos": {}}', MODELOS) is None
    assert ler('{"modelo_id": "m-info", "ementa": "  ", "campos": {}}', MODELOS) is None
    assert ler("nada de json", MODELOS) is None
    p = ler(
        '{"modelo_id": "m-info", "ementa": "Obras", "campos": {"destinatario": "SEINF", "vereador": "Forjado",'
        ' "justificativa": "pronta", "assunto": ["lista"]}}',
        MODELOS,
    )
    assert p is not None
    assert p.campos == {"destinatario": "SEINF"}, "so' os campos do modelo, sem o de justificativa, so' texto"


def test_rota_devolve_o_rascunho() -> None:
    nucleo, _ = _nucleo()
    app = criar_app(Config(segredo="s" * 32), nucleo=nucleo)
    cab = {"Authorization": "Bearer " + "s" * 32}
    corpo = {
        "descricao": PEDIDO,
        "modelos": [m.model_dump() for m in MODELOS],
        "correlation_id": "x1",
    }
    r = TestClient(app).post(f"/v1/entes/{ENTE}/requerimentos/rascunhos", json=corpo, headers=cab)
    assert r.status_code == 200
    b = r.json()
    assert b["preenchimento"]["modelo-id"] == "m-info"
    assert b["justificativa"]["campo"] == "justificativa"
    assert b["indisponivel"] is None
    assert TestClient(app).post(f"/v1/entes/{ENTE}/requerimentos/rascunhos", json=corpo).status_code == 401
