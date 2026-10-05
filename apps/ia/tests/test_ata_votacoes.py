"""A ata com o RESULTADO das votações da sessão (A.6): o contexto do core traz as votações encerradas (objeto,
modalidade, quórum, resultado, totais — nunca o voto de cada vereador), cada uma vira uma fonte estruturada do
pedido, o redator fake escreve o trecho a partir do dado e a Camada de Confiança confere os números contra ele."""

from __future__ import annotations

import json
from typing import Any

import httpx

from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.ata import fake
from oplenario_ia.ata.redacao import OPERACAO, PONTO_A_CONFIRMAR, pedido_de_ata, pontos_a_confirmar, texto_limpo
from oplenario_ia.confianca.indisponivel import Indisponivel
from oplenario_ia.confianca.registro import RegistroMemoria
from oplenario_ia.fronteira.cliente import ClienteCore
from oplenario_ia.fronteira.contrato import ContextoSessao
from oplenario_ia.governanca.proveniencia import Sigilo
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.trabalhador import Trabalhador
from oplenario_ia.transcricao.fake import DiarizadorFake, TranscritorFake
from oplenario_ia.transcricao.modelo import Trecho
from test_ata import CTX, T1, T2, evento_ata, transcricao
from test_trabalhador import CTX_URI, ENTE, FRASES, VOZES, CoreFalso, Relogio

NOMINAL = {
    "id": "vot-1",
    "objeto": "PL 008/2026",
    "modalidade": "nominal",
    "quorum-tipo": "maioria_simples",
    "votos-necessarios": None,
    "base-membros": 13,
    "resultado": "aprovada",
    "total-sim": 9,
    "total-nao": 2,
    "total-abstencao": 1,
    "encerrada-em": "2026-09-22T22:10:00Z",
}
SIMBOLICA = {
    "id": "vot-2",
    "objeto": "requerimento",
    "modalidade": "simbolica",
    "quorum-tipo": "maioria_simples",
    "base-membros": 13,
    "resultado": "aprovada",
    "total-sim": None,
    "total-nao": None,
    "total-abstencao": None,
    "encerrada-em": "2026-09-22T22:30:00Z",
}
SECRETA_2_3 = {
    "id": "vot-3",
    "objeto": "redação final do PL 012/2026",
    "modalidade": "secreta",
    "quorum-tipo": "maioria_qualificada_2_3",
    "votos-necessarios": 9,
    "base-membros": 13,
    "resultado": "rejeitada",
    "total-sim": 7,
    "total-nao": 5,
    "total-abstencao": 0,
    "encerrada-em": "2026-09-22T22:50:00Z",
}


def ctx_com(*votacoes: dict[str, Any]) -> ContextoSessao:
    return ContextoSessao.model_validate({**CTX.model_dump(by_alias=True, mode="json"), "votacoes": list(votacoes)})


def textos(pedido: Any) -> str:
    return "\n".join(p.texto for p in pedido.pecas)


# ---------- o contexto e o pedido ----------


def test_core_sem_o_campo_votacoes_vira_sem_votacoes() -> None:
    antigo = CTX.model_dump(by_alias=True, mode="json")
    antigo.pop("votacoes")
    assert ContextoSessao.model_validate(antigo).votacoes == []


def test_sem_votacoes_o_pedido_e_o_de_antes() -> None:
    p = pedido_de_ata(CTX, [T1, T2], ENTE, "corr")
    assert not [x for x in p.pecas if x.fonte and x.fonte.id.startswith("votacao:")]
    assert [x.fonte.id for x in p.pecas if x.fonte][:2] == [f"sessao:{CTX.sessao.id}", "transcricao:t1#1"]


def test_cada_votacao_encerrada_vira_uma_fonte_estruturada_publica_logo_depois_da_sessao() -> None:
    p = pedido_de_ata(ctx_com(SECRETA_2_3, NOMINAL), [T1], ENTE, "corr")  # fora de ordem de propósito
    ids = [x.fonte.id for x in p.pecas if x.fonte]
    assert ids[:3] == [f"sessao:{CTX.sessao.id}", "votacao:vot-1", "votacao:vot-3"], "na ordem em que encerraram"
    v = p.pecas[1]
    assert v.proveniencia is not None
    assert (v.proveniencia.sigilo, v.proveniencia.terceiro, v.proveniencia.voto_secreto) == (
        Sigilo.PUBLICO,
        False,
        False,
    )
    assert v.fonte is not None and v.fonte.estruturada
    assert [(f.valor, f.nomes[0]) for f in v.fonte.fatos] == [(9, "sim"), (2, "nao"), (1, "abstencao"), (13, "membro")]
    assert v.fonte.livres == [8, 2026] and v.fonte.pares == [(8, 2026)], "o número da matéria cita-se sem papel"
    assert v.texto == (
        "Matéria votada: PL 008/2026\nModalidade: nominal\n"
        "Quórum exigido: maioria simples (mais votos sim do que não)\n"
        "Placar: 9 votos sim, 2 votos não, 1 abstenção\nResultado: aprovada"
    )


def test_quorum_qualificado_diz_quantos_votos_e_de_quantos_membros() -> None:
    t = pedido_de_ata(ctx_com(SECRETA_2_3), [T1], ENTE, "c").pecas[1].texto
    assert "Quórum exigido: dois terços dos membros (9 votos sim de 13 membros da Casa)" in t
    assert "Modalidade: secreta" in t and "Resultado: rejeitada" in t


def test_simbolica_nao_inventa_placar() -> None:
    peca = pedido_de_ata(ctx_com(SIMBOLICA), [T1], ENTE, "c").pecas[1]
    assert "Placar: sem contagem individual (votação simbólica)" in peca.texto
    assert peca.fonte is not None
    assert [f.nomes[0] for f in peca.fonte.fatos] == ["membro"], (
        "só a composição: nenhum total de sim, não ou abstenção"
    )


def test_o_voto_de_cada_vereador_nao_entra_nem_se_o_fio_o_trouxesse() -> None:
    com_nomes = {
        **NOMINAL,
        "votos": [{"vereador-id": "v-ana", "nome": "Ana Ribeiro", "voto": "sim"}],
        "vereador": "Fulano de Tal",
    }
    p = pedido_de_ata(ctx_com(com_nomes), [T1], ENTE, "c")
    assert "Fulano" not in textos(p)
    v = next(x for x in p.pecas if x.fonte and x.fonte.id == "votacao:vot-1")
    assert "Ana" not in v.texto and "v-ana" not in v.texto


# ---------- a redação (fake) e a conferência ----------


def rascunho(ctx: ContextoSessao, transcricoes: list[Any] | None = None, porta: PortaFake | None = None) -> Any:
    reg = RegistroMemoria()
    n = Nucleo(porta or PortaFake({OPERACAO: fake.redigir}), reg)
    r = n.executar(pedido_de_ata(ctx, transcricoes or [T1, T2], ENTE, "corr"), "por_paragrafo")
    assert not isinstance(r, Indisponivel)
    return r


def test_fake_escreve_o_trecho_da_votacao_a_partir_do_dado_e_a_citacao_confere() -> None:
    r = rascunho(ctx_com(NOMINAL, SIMBOLICA, SECRETA_2_3))
    assert all(c.status == "conferida" for c in r.citacoes), [(c.fonte_id, c.status) for c in r.citacoes]
    limpo = texto_limpo(r.texto)
    assert "Votação nominal: PL 008/2026, aprovada, com 9 votos sim, 2 votos não e 1 abstenção." in limpo
    assert "Votação simbólica: requerimento, aprovada." in limpo
    assert (
        "Votação secreta: redação final do PL 012/2026, rejeitada, com 7 votos sim, 5 votos não e 0 abstenções."
        in limpo
    )
    assert [c.fonte_id for c in r.citacoes if c.fonte_id.startswith("votacao:")] == [
        "votacao:vot-1",
        "votacao:vot-2",
        "votacao:vot-3",
    ]
    assert pontos_a_confirmar(r.texto) == ["horário de encerramento"], "resultado já não sai como ponto a confirmar"
    paragrafo = next(p for p in r.texto.split("\n\n") if "PL 008/2026" in p)
    assert paragrafo.index("[[votacao:vot-1") > paragrafo.index("9 votos sim")


def test_o_rotulo_da_citacao_diz_que_veio_do_registro_do_sistema() -> None:
    r = rascunho(ctx_com(NOMINAL))
    [c] = [c for c in r.citacoes if c.fonte_id == "votacao:vot-1"]
    assert c.rotulo == "Votação de PL 008/2026, registrada pelo sistema"


def test_transcricao_que_contradiz_o_dado_vale_o_dado_e_sai_confirmar_com_os_dois_valores() -> None:
    contradiz = transcricao(
        "t9",
        "seg-1",
        [Trecho(0, 9, "Está aprovado o projeto por dez votos favoráveis.", "SPK_0", "v-pres", "Presidente Lúcia")],
    )
    r = rascunho(ctx_com(NOMINAL), [contradiz])
    limpo = texto_limpo(r.texto)
    assert "aprovada, com 9 votos sim, 2 votos não e 1 abstenção." in limpo, "vale o dado do sistema"
    assert "[confirmar: a gravação indica 10 votos sim; o sistema registra 9]" in limpo
    votacao = [c for c in r.citacoes if c.fonte_id == "votacao:vot-1"]
    assert [c.status for c in votacao] == ["conferida"], "o dado que ficou na ata confere com o registro"


def test_transcricao_que_concorda_nao_gera_ponto_a_confirmar() -> None:
    concorda = transcricao(
        "t9", "seg-1", [Trecho(0, 9, "Aprovado por nove votos favoráveis.", "SPK_0", "v-pres", "Presidente Lúcia")]
    )
    assert PONTO_A_CONFIRMAR.findall(texto_limpo(rascunho(ctx_com(NOMINAL), [concorda]).texto)) == [
        "horário de encerramento"
    ]


def redator_que_erra(frase: str) -> Any:
    def roteiro(_: Any) -> str:
        return f"{frase} [[votacao:vot-1 | Placar: 9 votos sim, 2 votos não, 1 abstenção]]"

    return roteiro


def test_numero_errado_na_frase_nao_confere_mesmo_com_o_trecho_certo() -> None:
    porta = PortaFake(
        {OPERACAO: redator_que_erra("O PL 008/2026 foi aprovado por 10 votos sim, 2 votos não e 1 abstenção.")}
    )
    r = rascunho(ctx_com(NOMINAL), porta=porta)
    assert [c.status for c in r.citacoes] == ["trecho_nao_encontrado"]
    assert "citacao_nao_conferida" in r.incerteza.motivos and r.incerteza.nivel == "revisar_com_atencao"


def test_sim_e_nao_trocados_nao_conferem() -> None:
    porta = PortaFake(
        {OPERACAO: redator_que_erra("O PL 008/2026 foi rejeitado por 9 votos contra e 2 votos favoráveis.")}
    )
    r = rascunho(ctx_com(NOMINAL), porta=porta)
    assert [c.status for c in r.citacoes] == ["trecho_nao_encontrado"]


def test_frase_correta_com_o_modelo_dizendo_por_extenso_confere() -> None:
    porta = PortaFake(
        {
            OPERACAO: redator_que_erra(
                "O PL 008/2026 foi aprovado por nove votos favoráveis, dois contrários e uma abstenção."
            )
        }
    )
    assert [c.status for c in rascunho(ctx_com(NOMINAL), porta=porta).citacoes] == ["conferida"]


# ---------- governança: o texto novo passa pelo filtro como todo o resto ----------


def test_a_votacao_chega_ao_fornecedor_pelo_filtro_com_a_redacao_de_identificadores() -> None:
    vazado = {**NOMINAL, "objeto": "PL 008/2026 (autor: fulano@exemplo.com.br)"}
    porta = PortaFake({OPERACAO: fake.redigir})
    reg = RegistroMemoria()
    Nucleo(porta, reg).executar(pedido_de_ata(ctx_com(vazado), [T1], ENTE, "c"), "por_paragrafo")
    [recebido] = porta.recebidos
    fonte = next(c for c in recebido.conteudo if 'id="votacao:vot-1"' in c)
    corpo = fonte.split("\n", 1)[1]
    assert "fulano@exemplo.com.br" not in corpo and "[REDIGIDO:EMAIL]" in corpo
    assert not fonte.startswith("<conteudo_de_terceiro"), "fato do sistema, não conteúdo de terceiro"
    assert "9 votos sim, 2 votos não, 1 abstenção" in corpo
    [ex] = reg.eventos()
    assert ex.redacoes == {"email": 1}


def test_voto_secreto_individual_continua_bloqueando_a_chamada_inteira() -> None:
    p = pedido_de_ata(ctx_com(NOMINAL), [T1], ENTE, "c")
    proveniencia = p.pecas[1].proveniencia
    assert proveniencia is not None
    proveniencia.voto_secreto = True
    porta = PortaFake({OPERACAO: fake.redigir})
    r = Nucleo(porta, RegistroMemoria()).executar(p, "por_paragrafo")
    assert isinstance(r, Indisponivel) and r.motivo == "sigilo" and porta.recebidos == []


# ---------- o trabalho de ponta a ponta contra o core falso ----------


class CoreComVotacoes(CoreFalso):
    """O core falso cujo contexto da sessão traz votações (o que o core real manda desde esta fatia)."""

    def __init__(self, votacoes: list[dict[str, Any]]) -> None:
        super().__init__()
        self.votacoes = votacoes

    def __call__(self, req: httpx.Request) -> httpx.Response:
        r = super().__call__(req)
        if req.url.path == CTX_URI:
            return httpx.Response(200, json={**json.loads(r.content), "votacoes": self.votacoes})
        return r


def test_ata_solicitada_com_votacoes_vira_rascunho_com_o_resultado_conferido() -> None:
    core = CoreComVotacoes([NOMINAL, SIMBOLICA])
    arm, rel = ArmazemMemoria(), Relogio()
    t = Trabalhador(
        ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(core))),
        arm,
        TranscritorFake(FRASES),
        DiarizadorFake(VOZES),
        nucleo=Nucleo(PortaFake({OPERACAO: fake.redigir}), RegistroMemoria(), agora=rel),
        agora=rel,
    )
    t.ciclo()
    core.eventos.append(evento_ata(2))
    t.ciclo()
    aviso = core.recebidos[-1]
    assert aviso["tipo"] == "AtaRascunhoPronta"
    p = aviso["payload"]
    assert p["prompt-versao"] == "ata-v2"
    assert p["n-citacoes"] == p["n-citacoes-conferidas"], "toda citação, inclusive as das votações, conferida"
    g = arm.rascunho(p["rascunho-id"])
    assert g is not None
    assert "Votação nominal: PL 008/2026, aprovada, com 9 votos sim, 2 votos não e 1 abstenção." in g.texto
    assert sum(c["fonte_id"].startswith("votacao:") for c in g.citacoes) == 2


def test_contexto_de_core_antigo_sem_votacoes_segue_redigindo() -> None:
    core = CoreFalso()
    arm, rel = ArmazemMemoria(), Relogio()
    t = Trabalhador(
        ClienteCore("http://core", "seg", cliente=httpx.Client(transport=httpx.MockTransport(core))),
        arm,
        TranscritorFake(FRASES),
        DiarizadorFake(VOZES),
        nucleo=Nucleo(PortaFake({OPERACAO: fake.redigir}), RegistroMemoria(), agora=rel),
        agora=rel,
    )
    t.ciclo()
    core.eventos.append(evento_ata(2))
    t.ciclo()
    assert core.recebidos[-1]["tipo"] == "AtaRascunhoPronta"
