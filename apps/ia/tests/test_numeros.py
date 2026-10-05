"""A conferência de números contra uma fonte estruturada (confianca/numeros.py) e o que ela muda na citação: o trecho
literal certo ao lado de um número errado deixa de passar, e a fonte comum segue conferida como sempre."""

from __future__ import annotations

from oplenario_ia.confianca.citacao import FonteLida, conferir
from oplenario_ia.confianca.numeros import afirmacao_antes_da_marca, divergencias, numeros
from oplenario_ia.governanca.proveniencia import Fato, Fonte

SIM = ("sim", "favor", "favoravel", "favoraveis")
NAO = ("nao", "contra", "contrario", "contrarios")
ABST = ("abstencao", "abstencoes")
FATOS = [Fato(valor=9, nomes=SIM), Fato(valor=2, nomes=NAO), Fato(valor=1, nomes=ABST)]
REGISTRO = (
    "Matéria votada: PL 008/2026\nModalidade: nominal\n"
    "Quórum exigido: maioria simples (mais votos sim do que não)\n"
    "Placar: 9 votos sim, 2 votos não, 1 abstenção\nResultado: aprovada"
)


def valores(texto: str) -> list[int]:
    return [n.valor for n in numeros(texto)]


def test_numeros_em_algarismo_e_por_extenso() -> None:
    assert valores("aprovado por 9 votos, 2 contra") == [9, 2]
    assert valores("nove votos sim e duas abstenções") == [9, 2]
    assert valores("vinte e três votos e cento e cinco") == [23, 105]
    assert valores("cento e vinte e três") == [123]
    assert valores("dois e três") == [2, 3], "só junta o que a língua junta"


def test_um_e_uma_so_contam_quando_colados_ao_que_se_conta() -> None:
    assert valores("um orador falou") == []
    assert valores("com um voto contra e uma abstenção") == [1, 1]


def test_data_e_hora_nao_sao_placar() -> None:
    assert valores("em 22/09/2026, às 18h30 e 19:45, aprovado por 9 votos") == [9]
    assert valores("PL 008/2026") == [8, 2026], "o número da matéria não é data"


def test_o_numero_leva_as_palavras_que_vem_depois_ate_a_pontuacao() -> None:
    [n] = numeros("9 votos favoráveis, não havendo abstenção")
    assert n.seguintes == ("votos", "favoraveis")


def test_afirmacao_vai_do_comeco_da_frase_ate_a_marca() -> None:
    t = "Abriu-se a sessão. A matéria foi aprovada por 9 votos sim. [[votacao:v1 | Placar: 9 votos sim]] Depois, 3."
    assert afirmacao_antes_da_marca(t, t.index("[[")) == "A matéria foi aprovada por 9 votos sim"


def test_afirmacao_nao_atravessa_a_citacao_anterior_nem_a_linha() -> None:
    t = "Houve 7 votos. [[a | xxxxxxxxxxxxxxx]] e depois 9 votos sim [[b | yyyyyyyyyyyyyyy]]"
    assert afirmacao_antes_da_marca(t, t.rindex("[[")).strip() == "e depois 9 votos sim"
    t2 = "Linha de cima com 5.\nLinha com 9 votos sim [[b | yyyyyyyyyyyyyyy]]"
    assert afirmacao_antes_da_marca(t2, t2.index("[[")) == "Linha com 9 votos sim"


def test_afirmacao_que_confere_nao_tem_divergencia() -> None:
    f = "A matéria PL 008/2026 foi aprovada por 9 votos sim, 2 votos não e 1 abstenção"
    assert divergencias(f, REGISTRO, FATOS) == []
    assert divergencias("aprovada por nove votos favoráveis e dois contrários", REGISTRO, FATOS) == []


def test_numero_que_o_registro_nao_tem_e_divergencia_e_diz_qual() -> None:
    assert divergencias("aprovada por 10 votos sim", REGISTRO, FATOS) == [
        "10 dito para 'sim', mas o registro do sistema é 9"
    ]
    assert divergencias("com 12 vereadores presentes", REGISTRO, FATOS) == ["12 não consta no registro do sistema"]


def test_sim_e_nao_trocados_sao_divergencia_mesmo_com_os_dois_numeros_no_registro() -> None:
    erros = divergencias("rejeitada por 9 votos contra e 2 votos favoráveis", REGISTRO, FATOS)
    assert erros == [
        "9 dito para 'nao', mas o registro do sistema é 2",
        "2 dito para 'sim', mas o registro do sistema é 9",
    ]


def test_numero_sem_nome_de_fato_so_precisa_estar_no_registro() -> None:
    assert divergencias("aprovada por 9 votos, não havendo mais nada", REGISTRO, FATOS) == []


# ---------- o que isto muda na citação ----------


def fonte_votacao() -> FonteLida:
    return FonteLida(
        fonte=Fonte(id="votacao:v1", rotulo="Votação do PL 008/2026", estruturada=True, fatos=FATOS), texto=REGISTRO
    )


def status(texto: str, lida: FonteLida) -> list[str]:
    return [c.status for c in conferir(texto, [lida])]


def test_trecho_literal_certo_com_numero_errado_na_frase_nao_confere() -> None:
    marca = "[[votacao:v1 | Placar: 9 votos sim, 2 votos não, 1 abstenção]]"
    assert status(f"Foi aprovada por 9 votos sim, 2 votos não e 1 abstenção. {marca}", fonte_votacao()) == ["conferida"]
    assert status(f"Foi aprovada por 10 votos sim, 2 votos não e 1 abstenção. {marca}", fonte_votacao()) == [
        "trecho_nao_encontrado"
    ]


def test_o_trecho_errado_segue_reprovando_como_antes() -> None:
    marca = "[[votacao:v1 | Placar: 10 votos sim, 2 votos não, 1 abstenção]]"
    assert status(f"Foi aprovada por 9 votos sim. {marca}", fonte_votacao()) == ["trecho_nao_encontrado"]


def test_fonte_comum_nao_ganha_a_regra_nova() -> None:
    comum = FonteLida(
        fonte=Fonte(id="transcricao:t1#1", rotulo="Presidente"), texto="Declaro aprovado o projeto por nove votos."
    )
    assert status(
        "A Presidência declarou 12 votos. [[transcricao:t1#1 | aprovado o projeto por nove votos]]", comum
    ) == ["conferida"]
