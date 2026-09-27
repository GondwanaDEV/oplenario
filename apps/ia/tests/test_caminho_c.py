"""Caminho C (§22.6 eixo D): o nome vem de quem tinha a palavra na tribuna, nunca de reconhecimento de voz."""

from oplenario_ia.transcricao.caminho_c import achatar, atribuir, cobertura, nomear_grupos
from oplenario_ia.transcricao.modelo import Frase, Palavra, Voz

ANA = Palavra(10, 70, "v-ana", "Ana Ribeiro")
BRUNO = Palavra(80, 140, "v-bruno", "Bruno Lima")


def test_grupo_recebe_o_nome_de_quem_tinha_a_palavra_na_maior_parte_do_tempo() -> None:
    vozes = [Voz(12, 68, "SPK_0"), Voz(82, 138, "SPK_1"), Voz(69, 81, "SPK_2"), Voz(140, 145, "SPK_2")]
    nomes = nomear_grupos(vozes, [ANA, BRUNO])
    assert nomes["SPK_0"] == ANA and nomes["SPK_1"] == BRUNO
    assert nomes["SPK_2"] is None, "o presidente conduz ENTRE as falas: sem maioria sobre ninguém, sem nome"


def test_grupo_dividido_sem_maioria_fica_sem_nome() -> None:
    vozes = [Voz(60, 70, "SPK_0"), Voz(80, 90, "SPK_0"), Voz(150, 170, "SPK_0")]
    assert nomear_grupos(vozes, [ANA, BRUNO])["SPK_0"] is None


def test_frase_vai_para_o_grupo_que_mais_falou_durante_ela() -> None:
    vozes = [Voz(10, 70, "SPK_0"), Voz(80, 140, "SPK_1")]
    frases = [Frase(20, 30, "Senhor presidente,"), Frase(85, 95, "Peço a palavra."), Frase(200, 210, "Aplausos.")]
    t = atribuir(frases, vozes, [ANA, BRUNO])
    assert [(x.orador_nome, x.grupo) for x in t] == [("Ana Ribeiro", "SPK_0"), ("Bruno Lima", "SPK_1"), (None, None)]
    assert cobertura(t) == round(20 / 30, 4)


def test_sem_diarizacao_a_frase_vai_para_quem_tinha_a_palavra() -> None:
    t = atribuir([Frase(20, 30, "a"), Frase(75, 85, "b")], [], [ANA, BRUNO])
    assert [x.orador_id for x in t] == ["v-ana", "v-bruno"]
    assert all(x.grupo is None for x in t)


def test_sem_palavra_registrada_ninguem_e_nomeado_e_cobertura_zero() -> None:
    t = atribuir([Frase(0, 10, "a")], [Voz(0, 10, "SPK_0")], [])
    assert t[0].orador_id is None and t[0].grupo == "SPK_0"
    assert cobertura(t) == 0.0 and cobertura([]) == 0.0


def test_aparte_vence_a_fala_mae_e_fala_nova_vence_a_que_ficou_aberta() -> None:
    mae = Palavra(0, 100, "v-ana", "Ana")
    aparte = Palavra(40, 50, "v-bruno", "Bruno")
    aberta = Palavra(0, 10_000, "v-carla", "Carla")  # esqueceram de encerrar
    nova = Palavra(200, 260, "v-davi", "Davi")
    assert [(p.inicio, p.fim, p.orador_id) for p in achatar([mae, aparte])] == [
        (0, 40, "v-ana"),
        (40, 50, "v-bruno"),
        (50, 100, "v-ana"),
    ]
    plana = achatar([aberta, nova])
    assert (200, 260, "v-davi") in [(p.inicio, p.fim, p.orador_id) for p in plana]
    t = atribuir([Frase(42, 48, "Um aparte, nobre colega."), Frase(210, 220, "b")], [], [mae, aparte, aberta, nova])
    assert [x.orador_id for x in t] == ["v-bruno", "v-davi"]


def test_grupo_nomeado_mesmo_com_uma_fala_esquecida_aberta() -> None:
    aberta = Palavra(0, 10_000, "v-velho", "Velho")
    vozes = [Voz(10, 20, "SPK_0"), Voz(30, 40, "SPK_1")]
    nomes = nomear_grupos(vozes, [aberta, Palavra(10, 20, "v-ana", "Ana"), Palavra(30, 40, "v-bia", "Bia")])
    assert nomes["SPK_0"] is not None and nomes["SPK_0"].orador_id == "v-ana"
    assert nomes["SPK_1"] is not None and nomes["SPK_1"].orador_id == "v-bia"
