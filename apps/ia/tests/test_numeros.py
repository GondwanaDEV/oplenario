"""A conferência de números contra uma fonte estruturada (confianca/numeros.py): uma só leitura nos dois lados,
fail-closed no que não se sabe interpretar, conferência por PAPEL e por votação, casamento por número inteiro.
O texto da ata é o que a pessoa lê; o conferidor lê o mesmo."""

from __future__ import annotations

import pytest

from oplenario_ia.ata.fake import _votacao
from oplenario_ia.ata.redacao import peca_da_votacao
from oplenario_ia.confianca.citacao import FonteLida, conferir
from oplenario_ia.confianca.numeros import afirmacao_da_marca, divergencias, ler, normalizar, papel
from oplenario_ia.fronteira.contrato import VotacaoContexto
from oplenario_ia.governanca.proveniencia import Fonte, Peca


def votacao(
    sim: int | None = 9,
    nao: int | None = 2,
    abst: int | None = 1,
    *,
    id_: str = "v1",
    objeto: str = "PL 008/2026",
    quorum: str = "maioria_simples",
    necessarios: int | None = None,
    base: int | None = 13,
    modalidade: str = "nominal",
) -> Peca:
    return peca_da_votacao(
        VotacaoContexto.model_validate(
            {
                "id": id_,
                "objeto": objeto,
                "modalidade": modalidade,
                "quorum-tipo": quorum,
                "votos-necessarios": necessarios,
                "base-membros": base,
                "resultado": "aprovada",
                "total-sim": sim,
                "total-nao": nao,
                "total-abstencao": abst,
                "encerrada-em": "2026-09-22T22:10:00Z",
            }
        )
    )


def lida(p: Peca) -> FonteLida:
    assert p.fonte is not None
    return FonteLida(fonte=p.fonte, texto=p.texto)


def problemas(frase: str, peca: Peca | None = None) -> list[str]:
    p = peca or votacao()
    assert p.fonte is not None
    return divergencias(frase, p.fonte, p.texto)


def valores(texto: str) -> list[int]:
    return [n.valor for n in ler(texto).numeros]


def test_a_frase_certa_confere_e_a_do_registro_tambem() -> None:
    assert problemas("A matéria PL 008/2026 foi aprovada por 9 votos sim, 2 votos não e 1 abstenção") == []
    assert problemas("aprovada por nove votos favoráveis, dois contrários e uma abstenção") == []


# ---------- 1. uma só leitura: o que o olho lê é o que o conferidor lê ----------


@pytest.mark.parametrize(
    "escrito",
    ["９", "٩", "⁹", "⑨", "९"],
    ids=["largura-total", "arabe-indico", "sobrescrito", "circulado", "devanagari"],
)
def test_digito_unicode_vira_o_numero_que_o_olho_le(escrito: str) -> None:
    assert valores(f"{escrito} votos sim") == [9]
    assert problemas(f"aprovada por {escrito} votos sim, 2 votos não e 1 abstenção") == []


def test_unicode_errado_reprova_como_o_ascii_errado() -> None:
    erro = ["10 dito para 'sim', mas o registro do sistema é 9"]
    assert problemas("aprovada por １０ votos sim") == erro
    assert problemas("aprovada por ¹⁰ votos sim") == erro
    assert problemas("aprovada por ١٠ votos sim") == erro


def test_caractere_invisivel_no_meio_do_numero_nao_o_parte_em_dois() -> None:
    # 1 e 0 existem no registro (1 abstenção, 0...), mas o olho lê 10
    assert valores("1​0 votos sim") == [10]
    assert valores("1­0 votos sim") == [10]
    assert problemas("aprovada por 1​0 votos sim") == ["10 dito para 'sim', mas o registro do sistema é 9"]


def test_a_mesma_normalizacao_e_leitura_nos_dois_lados() -> None:
    p = votacao()
    assert p.fonte is not None
    placar = next(ln for ln in p.texto.split("\n") if ln.startswith("Placar:"))
    # o texto do registro, lido pela mesma função, dá a cada número o papel e o valor que o DADO estruturado tem
    lidos = ler(placar).numeros
    assert [n.valor for n in lidos] == [9, 2, 1]
    for n in lidos:
        f = papel(n, p.fonte.fatos)
        assert f is not None and f.valor == n.valor
    assert normalizar("Três ⁹ Ｓim​") == "tres 9 sim"


@pytest.mark.parametrize(
    "frase",
    [
        "aprovada por 1.000 votos sim",
        "aprovada por 1,5 votos sim",
        "aprovada por 1.0 votos sim",
        "aprovada por 1 0 votos sim",
        "aprovada por 1 0 votos sim",
        "aprovada por 1 0 votos sim",
        "aprovada por 1 0 votos sim",
        "aprovada por 1\n0 votos sim",
    ],
)
def test_separador_de_milhar_decimal_ou_espaco_no_meio_nao_e_conferido(frase: str) -> None:
    assert problemas(frase), frase


def test_um_ponto_zero_nao_vira_dez_nem_nove() -> None:
    leitura = ler("1.0 votos sim")
    assert leitura.duvidas and leitura.numeros == []


@pytest.mark.parametrize("frase", ["-9 votos sim", "−9 votos sim", "+9 votos sim", "9-2 votos", "9–2"])
def test_sinal_ou_intervalo_nao_e_conferido(frase: str) -> None:
    assert problemas(f"aprovada por {frase}"), frase


def test_zero_a_esquerda_e_o_mesmo_numero() -> None:
    assert valores("09 votos sim, 002 votos não, 01 abstenção") == [9, 2, 1]
    assert problemas("aprovada por 09 votos sim, 002 votos não e 01 abstenção") == []
    assert problemas("aprovada por 010 votos sim") == ["10 dito para 'sim', mas o registro do sistema é 9"]


# ---------- extenso: confere de verdade ou marca ----------


@pytest.mark.parametrize(
    ("extenso", "valor"),
    [("sete", 7), ("dezenove", 19), ("vinte e um", 21), ("trinta e três", 33), ("cento e vinte e cinco", 125)],
)
def test_extenso_e_lido_por_inteiro(extenso: str, valor: int) -> None:
    assert valores(f"{extenso} votos") == [valor]


def test_extenso_errado_reprova_e_certo_confere() -> None:
    assert problemas("aprovada por dez votos favoráveis") == ["10 dito para 'sim', mas o registro do sistema é 9"]
    assert problemas("aprovada por nove votos favoráveis") == []
    assert problemas("aprovada por vinte e um votos sim") == ["21 dito para 'sim', mas o registro do sistema é 9"]


def test_dois_e_tres_nao_viram_cinco() -> None:
    assert valores("dois e três") == [2, 3]


@pytest.mark.parametrize(
    "frase", ["mil votos sim", "uma dúzia de votos", "duas dezenas de votos", "um milhão de votos"]
)
def test_extenso_que_nao_se_interpreta_vira_duvida(frase: str) -> None:
    assert ler(frase).duvidas, frase
    assert problemas(f"aprovada por {frase}"), frase


def test_nenhum_e_sem_valem_zero_e_so_conferem_se_o_registro_for_zero() -> None:
    assert problemas("aprovada, com nenhuma abstenção") == ["0 dito para 'abstencao', mas o registro do sistema é 1"]
    assert problemas("aprovada, sem abstenções") == ["0 dito para 'abstencao', mas o registro do sistema é 1"]
    assert problemas("aprovada, com nenhuma abstenção e sem votos contrários", votacao(10, 0, 0)) == []


def test_unanimidade_so_confere_se_o_registro_for_unanime() -> None:
    erro = ["unanimidade, mas o registro do sistema tem voto contrário, abstenção ou não tem placar"]
    assert problemas("aprovada por unanimidade") == erro
    assert problemas("aprovada por unanimidade", votacao(12, 0, 0)) == []
    assert problemas("aprovada por unanimidade", votacao(None, None, None, modalidade="simbolica")) == erro


# ---------- ordinais, número colado, referências ----------


@pytest.mark.parametrize("frase", ["o 7º voto", "a 7ª votação", "o 1o turno", "o 9° vereador", "o 7ºs"])
def test_ordinal_nao_e_contagem_e_nao_passa(frase: str) -> None:
    assert problemas(frase), frase


def test_numero_colado_na_palavra_e_lido_e_conferido() -> None:
    assert valores("9votos sim") == [9]
    assert problemas("aprovada por 10votos sim") == ["10 dito para 'sim', mas o registro do sistema é 9"]


def test_barra_so_passa_como_a_referencia_da_materia() -> None:
    assert problemas("o PL 008/2026 foi aprovado por 9 votos sim") == []
    assert problemas("o PL 012/2026 foi aprovado por 9 votos sim"), "outra matéria: 12/2026 não é a desta votação"
    assert problemas("aprovada por 9/2"), "9/2 não é data nem referência: não se sabe ler"
    assert problemas("aprovada por 10/2"), "10/2 (era engolido como data)"


def test_data_e_hora_completas_nao_sao_placar() -> None:
    assert problemas("Em 22/09/2026, às 18h30, o PL 008/2026 foi aprovado por 9 votos sim") == []


def test_numero_solto_que_o_registro_nao_tem_reprova() -> None:
    assert problemas("conforme o art. 7") == ["7 não consta no registro do sistema"]
    assert problemas("com 12 vereadores presentes") == [
        "12 'vereadores' não tem papel conhecido no registro do sistema"
    ]


def test_a_fracao_do_quorum_qualificado_passa() -> None:
    p = votacao(9, 4, 0, quorum="maioria_qualificada_2_3", necessarios=9)
    assert problemas("exigia dois terços dos membros, ou 9 votos necessários, e teve 9 votos sim", p) == []
    assert problemas("exigia 2/3 dos membros, ou eram necessários 9 votos", p) == []
    assert problemas("eram necessários 8 votos", p) == ["8 dito para 'necessario', mas o registro do sistema é 9"]


# ---------- 3. o número certo no campo certo, na votação certa ----------


def test_sim_e_nao_trocados_reprovam_mesmo_com_os_dois_numeros_no_registro() -> None:
    assert problemas("rejeitada por 9 votos contra e 2 votos favoráveis") == [
        "9 dito para 'nao', mas o registro do sistema é 2",
        "2 dito para 'sim', mas o registro do sistema é 9",
    ]


def test_7_a_favor_2_contra_nao_passa_com_os_campos_trocados() -> None:
    assert problemas("aprovada por 7 votos a favor e 2 contra", votacao(2, 7, 0)) == [
        "7 dito para 'sim', mas o registro do sistema é 2",
        "2 dito para 'nao', mas o registro do sistema é 7",
    ]
    assert problemas("aprovada por 7 votos a favor e 2 contra", votacao(7, 2, 0)) == []


def test_o_numero_de_outra_votacao_da_mesma_sessao_nao_confere() -> None:
    a, b = votacao(7, 2, 0, id_="a"), votacao(5, 4, 0, id_="b")
    assert problemas("aprovada por 7 votos sim", a) == []
    assert problemas("aprovada por 7 votos sim", b) == ["7 dito para 'sim', mas o registro do sistema é 5"]
    texto = "O PL 008/2026 teve 7 votos sim. [[votacao:b | Placar: 5 votos sim, 4 votos não, 0 abstenções]]"
    assert [c.status for c in conferir(texto, [lida(b)])] == ["trecho_nao_encontrado"]


def test_numero_sem_papel_ao_lado_de_voto_reprova_mesmo_que_exista_no_registro() -> None:
    # "7 votos a 2" (sim a não) com 7 e 2 existindo no registro em campos trocados passaria só por "existir"
    assert problemas("aprovada por 7 votos a 2", votacao(2, 7, 0)) == [
        "7 'votos' não tem papel conhecido no registro do sistema",
        "2 não consta no registro do sistema",
    ]


def test_papeis_do_quorum_e_da_composicao() -> None:
    p = votacao(9, 2, 1, quorum="maioria_absoluta", necessarios=7, base=13)
    assert problemas("eram necessários 7 votos sim, de 13 membros", p) == []
    assert problemas("eram necessários 8 votos sim, de 13 membros", p) == [
        "8 dito para 'necessario', mas o registro do sistema é 7"
    ]
    assert problemas("eram necessários 7 votos sim, de 14 membros", p) == [
        "14 dito para 'membro', mas o registro do sistema é 13"
    ]


# ---------- 4. token inteiro, não pedaço ----------


def test_17_nao_e_conferido_por_existir_7_ou_1() -> None:
    assert problemas("aprovada por 17 votos sim", votacao(7, 1, 0)) == [
        "17 dito para 'sim', mas o registro do sistema é 7"
    ]
    assert problemas("aprovada por 7 votos sim", votacao(17, 1, 0)) == [
        "7 dito para 'sim', mas o registro do sistema é 17"
    ]
    assert problemas("com 17 abstenções") == ["17 dito para 'abstencao', mas o registro do sistema é 1"]
    assert problemas("em 2026 a matéria") == []
    assert problemas("em 202 a matéria") == ["202 não consta no registro do sistema"]


# ---------- a frase que a marca sustenta ----------


def test_afirmacao_vai_do_fim_da_citacao_anterior_ao_paragrafo_e_inclui_o_resto_sem_citacao() -> None:
    t = "Houve 7 votos. [[a | xxxxxxxxxxxxxxx]] e depois 9 votos sim [[b | yyyyyyyyyyyyyyy]] e mais 5 votos."
    i = t.rindex("[[b")
    assert afirmacao_da_marca(t, i, t.index("]]", i) + 2).split() == [
        "e",
        "depois",
        "9",
        "votos",
        "sim",
        "e",
        "mais",
        "5",
        "votos.",
    ]
    a = t.index("[[a")
    assert "Houve 7 votos." in afirmacao_da_marca(t, a, t.index("]]") + 2), "a primeira sustenta o que veio antes"


def test_quebra_de_linha_no_meio_da_frase_nao_corta_a_afirmacao() -> None:
    t = "A matéria foi aprovada por 10\nvotos sim [[votacao:v1 | Placar: 9 votos sim, 2 votos não, 1 abstenção]]"
    assert "10" in afirmacao_da_marca(t, t.index("[["), len(t))
    assert [c.status for c in conferir(t, [lida(votacao())])] == ["trecho_nao_encontrado"]


def test_paragrafo_novo_corta_a_afirmacao() -> None:
    t = "Sessão com 12 presentes.\n\nA matéria foi aprovada [[votacao:v1 | Placar: 9 votos sim]]"
    assert "12" not in afirmacao_da_marca(t, t.index("[["), len(t))


def test_ponto_de_abreviatura_nao_esconde_numero() -> None:
    t = (
        "Aprovada por 10 votos sim, conforme o Sr. Presidente. "
        "[[votacao:v1 | Placar: 9 votos sim, 2 votos não, 1 abstenção]]"
    )
    assert "10" in afirmacao_da_marca(t, t.index("[["), t.index("]]") + 2)
    assert [c.status for c in conferir(t, [lida(votacao())])] == ["trecho_nao_encontrado"]


def test_numero_escrito_depois_da_marca_tambem_e_conferido() -> None:
    marca = "[[votacao:v1 | Placar: 9 votos sim, 2 votos não, 1 abstenção]]"
    assert [c.status for c in conferir(f"Foi aprovada. {marca} O placar foi de 10 votos sim.", [lida(votacao())])] == [
        "trecho_nao_encontrado"
    ]
    assert [c.status for c in conferir(f"Foi aprovada. {marca} O placar foi de 9 votos sim.", [lida(votacao())])] == [
        "conferida"
    ]


def test_confirmar_declarado_fica_fora_da_conferencia() -> None:
    texto = (
        "Foi aprovada por 9 votos sim. [[votacao:v1 | Placar: 9 votos sim, 2 votos não, 1 abstenção]] "
        "[confirmar: a gravação indica 10 votos sim; o sistema registra 9]"
    )
    assert [c.status for c in conferir(texto, [lida(votacao())])] == ["conferida"]


# ---------- o que isto muda na citação ----------


def status(texto: str, fonte: FonteLida) -> list[str]:
    return [c.status for c in conferir(texto, [fonte])]


def test_trecho_literal_certo_com_numero_errado_na_frase_nao_confere() -> None:
    marca = "[[votacao:v1 | Placar: 9 votos sim, 2 votos não, 1 abstenção]]"
    assert status(f"Foi aprovada por 9 votos sim, 2 votos não e 1 abstenção. {marca}", lida(votacao())) == ["conferida"]
    assert status(f"Foi aprovada por 10 votos sim, 2 votos não e 1 abstenção. {marca}", lida(votacao())) == [
        "trecho_nao_encontrado"
    ]


def test_o_trecho_errado_segue_reprovando_como_antes() -> None:
    marca = "[[votacao:v1 | Placar: 10 votos sim, 2 votos não, 1 abstenção]]"
    assert status(f"Foi aprovada por 9 votos sim. {marca}", lida(votacao())) == ["trecho_nao_encontrado"]


def test_fonte_comum_nao_ganha_a_regra_nova() -> None:
    comum = FonteLida(
        fonte=Fonte(id="transcricao:t1#1", rotulo="Presidente"), texto="Declaro aprovado o projeto por nove votos."
    )
    assert status(
        "A Presidência declarou 12 votos. [[transcricao:t1#1 | aprovado o projeto por nove votos]]", comum
    ) == ["conferida"]


# ---------- 5. propriedade: o roteiro do fake confere; qualquer um dos três números trocado, não ----------


def _sim(n: int) -> str:
    return f"{n} {'voto' if n == 1 else 'votos'} sim"


def _nao(n: int) -> str:
    return f"{n} {'voto' if n == 1 else 'votos'} não"


def _abs(n: int) -> str:
    return f"{n} {'abstenção' if n == 1 else 'abstenções'}"


def test_propriedade_o_placar_do_roteiro_confere_e_trocar_qualquer_numero_nao() -> None:
    certos = trocas = 0
    for sim in range(31):
        for nao in range(31):
            for abst in {0, (sim * 3 + nao) % 31}:
                p = votacao(sim, nao, abst)
                assert p.fonte is not None
                corpo, _, _ = _votacao(p.fonte.id, p.texto)  # a frase que o roteiro do fake escreve
                fonte = lida(p)
                assert [c.status for c in conferir(corpo, [fonte])] == ["conferida"], corpo
                certos += 1
                frase, marca = corpo.split(" [[", 1)  # só a frase: o trecho literal da marca não é tocado
                for antigo, formato, atual in (
                    (_sim(sim), _sim, sim),
                    (_nao(nao), _nao, nao),
                    (_abs(abst), _abs, abst),
                ):
                    assert antigo in frase, (antigo, frase)
                    for outro in {0, 1, 17, 30, atual + 1}:
                        if outro == atual:
                            continue
                        errado = f"{frase.replace(antigo, formato(outro), 1)} [[{marca}"
                        assert [c.status for c in conferir(errado, [fonte])] == ["trecho_nao_encontrado"], errado
                        trocas += 1
    assert certos > 1800 and trocas > 25000
