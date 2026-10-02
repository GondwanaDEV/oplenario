"""ADR-0019, Eixo 5 — o copiloto do relator: o rascunho da análise de constitucionalidade e juridicidade do parecer de
comissão, citando a matéria e os dispositivos da Casa; sem normas publicadas, só com a matéria e com incerteza. Rascunho
que volta ao editor: nada é guardado além do registro da execução."""

from __future__ import annotations

from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.armazem.porta import Resultado
from oplenario_ia.confianca.registro import RegistroExecucao, RegistroMemoria
from oplenario_ia.config import Config
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.parecer import analise
from oplenario_ia.parecer.analise import PedidoAnalise

ENTE = "10000000-0000-0000-0000-000000000001"
PID = "20000000-0000-0000-0000-000000000002"
EMENTA = "Institui o Programa Municipal de Hortas Comunitárias."
LOM_ART = Resultado(
    tipo="dispositivo",
    ref_id="n1",
    parte=0,
    texto=(
        "Lei Orgânica do Município, art. 11: Compete ao Município legislar sobre assuntos de interesse local, "
        "suplementando a legislação federal e a estadual no que couber."
    ),
    meta={
        "norma-id": "n1",
        "versao-id": "v1",
        "titulo": "Lei Orgânica do Município",
        "endereco": "art11",
        "rotulo": "art. 11",
        "consolidada-ate": "2026-06-30",
    },
    score=0.9,
)
RI_ART = Resultado(
    tipo="dispositivo",
    ref_id="n2",
    parte=0,
    texto="Regimento Interno, art. 40: A iniciativa das leis cabe a qualquer vereador, às comissões e ao Prefeito.",
    meta={"norma-id": "n2", "titulo": "Regimento Interno", "endereco": "art40", "rotulo": "art. 40"},
    score=0.8,
)


def _nucleo(registro: RegistroMemoria | None = None) -> tuple[Nucleo, PortaFake]:
    p = criar_porta(Config(vendor="fake"))
    assert isinstance(p, PortaFake)
    return Nucleo(p, registro or RegistroMemoria()), p


def _pedido(**kw: object) -> PedidoAnalise:
    base: dict[str, object] = {
        "proposicao_id": PID,
        "tipo": "projeto_lei",
        "ano": 2026,
        "sequencial": 12,
        "ementa": EMENTA,
        "texto": "Art. 1º Fica instituído o Programa Municipal de Hortas Comunitárias.",
        "autor_texto": "Vereadora Ana Prado",
        "comissao": "Comissão de Constituição e Justiça",
        "correlation_id": "c1",
    }
    base.update(kw)
    return PedidoAnalise.model_validate(base)


def test_rascunha_citando_a_materia_e_a_lom_pelo_artigo() -> None:
    nucleo, porta = _nucleo()
    consultas: list[str] = []

    def buscar(q: str) -> list[Resultado]:
        consultas.append(q)
        return {"Projeto de Lei": [LOM_ART], "iniciativa das leis": [RI_ART, LOM_ART]}.get(q, [])

    r = analise.rascunhar(nucleo, _pedido(), ENTE, buscar)
    assert consultas == [
        "Projeto de Lei",
        EMENTA,
        "competência do Município para legislar",
        "iniciativa das leis",
        "quórum de aprovação",
    ], "a base se acha pela espécie, pela ementa e pelos aspectos de toda análise de juridicidade"
    assert r.indisponivel is None and r.analise is not None
    a = r.analise
    assert [(c.fonte_id, c.status) for c in a.citacoes] == [
        (f"materia:{PID}", "conferida"),
        ("norma:n1#art11", "conferida"),
        ("norma:n2#art40", "conferida"),
    ], "a LOM repetida pela segunda consulta entra uma vez só"
    assert "Compete ao Município legislar sobre assuntos de interesse local" in a.texto
    assert "[confirmar:" in a.texto, "onde não há fonte, o rascunho aponta o que o relator deve verificar"
    assert r.normas == "citadas"
    assert r.fontes["norma:n1#art11"] == "Lei Orgânica do Município, art. 11 (consolidada até 30/06/2026)"
    assert r.fontes[f"materia:{PID}"] == "Projeto de Lei nº 12/2026"
    assert a.incerteza.nivel == "revisar_com_atencao"
    assert "conteudo_de_terceiro" in a.incerteza.motivos, "o texto da matéria é do autor: sempre revisar com atenção"
    assert [p.operacao for p in porta.recebidos] == ["relator.analisar"]
    enviado = "\n".join(porta.recebidos[0].conteudo)
    assert "Comissão de Constituição e Justiça" in enviado
    assert "parecer" not in a.texto.lower(), "texto de IA nunca se chama de parecer"


def test_sem_normas_publicadas_rascunha_so_com_a_materia_e_diz_isso() -> None:
    nucleo, porta = _nucleo()
    chamadas: list[str] = []

    def buscar(q: str) -> list[Resultado]:
        chamadas.append(q)
        return [LOM_ART]

    r = analise.rascunhar(nucleo, _pedido(normas_publicadas=False), ENTE, buscar)
    assert chamadas == [], "sem normas publicadas, nem consulta o índice"
    assert r.normas == "sem-normas"
    assert r.analise is not None
    assert [c.fonte_id for c in r.analise.citacoes] == [f"materia:{PID}"]
    assert "ainda não publicou a Lei Orgânica nem o Regimento Interno" in r.analise.texto
    assert r.analise.paragrafos_sem_fonte, "os pontos sem norma saem sem fonte"
    assert r.analise.incerteza.nivel == "revisar_com_atencao"
    assert "sem_fonte" in r.analise.incerteza.motivos
    assert analise.NORMAS_NAO_PUBLICADAS in "\n".join(porta.recebidos[0].conteudo), "o modelo sabe por que não há norma"


def test_normas_publicadas_sem_dispositivo_achado() -> None:
    nucleo, _ = _nucleo()
    r = analise.rascunhar(nucleo, _pedido(), ENTE, lambda _q: [])
    assert r.normas == "sem-dispositivo"
    assert r.analise is not None
    assert "não foram encontrados, nas normas da Casa" in r.analise.texto


def test_indice_fora_nao_derruba_o_copiloto() -> None:
    def fora(_q: str) -> list[Resultado]:
        raise RuntimeError("índice fora")

    nucleo, _ = _nucleo()
    r = analise.rascunhar(nucleo, _pedido(), ENTE, fora)
    assert r.analise is not None and r.normas == "sem-dispositivo"


def test_ia_fora_devolve_o_piso_r_ia_1_e_registra_sem_conteudo() -> None:
    registro = RegistroMemoria()
    porta = PortaFake({analise.OPERACAO: ErroIA(Categoria.INFRAESTRUTURA, "fora", retentavel=True)})
    r = analise.rascunhar(Nucleo(porta, registro), _pedido(), ENTE, lambda _q: [LOM_ART])
    assert r.analise is None
    assert r.indisponivel is not None and r.indisponivel.motivo == "fornecedor_fora"
    assert "Siga pela tela" in r.indisponivel.mensagem
    [ev] = [e for e in registro.eventos() if isinstance(e, RegistroExecucao)]
    assert (ev.operacao, ev.ente_id, ev.resultado) == ("relator.analisar", ENTE, "indisponivel")
    assert "Hortas" not in ev.model_dump_json(), "o registro nunca guarda o texto (B4)"


def test_custo_da_casa_registrado_pela_operacao() -> None:
    registro = RegistroMemoria()
    nucleo, _ = _nucleo(registro)
    analise.rascunhar(nucleo, _pedido(), ENTE, lambda _q: [LOM_ART])
    [ev] = [e for e in registro.eventos() if isinstance(e, RegistroExecucao)]
    assert (ev.operacao, ev.ente_id, ev.resultado) == ("relator.analisar", ENTE, "artefato")
    assert ev.uso is not None and ev.uso.entrada > 0 and ev.custo is not None
    assert ev.n_citacoes_conferidas == 2


def test_rota_devolve_o_rascunho_com_pontos_a_confirmar() -> None:
    nucleo, _ = _nucleo()
    app = criar_app(Config(segredo="s" * 32), nucleo=nucleo)
    cab = {"Authorization": "Bearer " + "s" * 32}
    corpo = _pedido(normas_publicadas=False).model_dump()
    r = TestClient(app).post(f"/v1/entes/{ENTE}/pareceres/analises", json=corpo, headers=cab)
    assert r.status_code == 200
    b = r.json()
    assert b["normas"] == "sem-normas"
    assert b["indisponivel"] is None
    assert b["analise"]["citacoes"][0] == {
        "fonte-id": f"materia:{PID}",
        "rotulo": "Projeto de Lei nº 12/2026",
        "trecho": EMENTA,
        "status": "conferida",
    }
    assert b["analise"]["incerteza"] == "revisar_com_atencao"
    assert any("Lei Orgânica" in p for p in b["analise"]["pontos-a-confirmar"])
    assert TestClient(app).post(f"/v1/entes/{ENTE}/pareceres/analises", json=corpo).status_code == 401
    assert (
        TestClient(app).post(f"/v1/entes/{ENTE}/pareceres/analises", json={"tipo": "x"}, headers=cab).status_code == 422
    )
