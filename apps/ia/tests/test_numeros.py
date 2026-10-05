"""A conferência de números de uma fonte estruturada (confianca/numeros.py): casamento exato com as frases canônicas do
dado e sobra zero de sinal numérico, sem interpretar prosa. O que a revisão de segurança já pegou e a propriedade
"o que o roteiro do fake escreve confere; qualquer número ou sinal a mais, não"."""

from __future__ import annotations

import pytest

from oplenario_ia.ata.fake import _votacao
from oplenario_ia.ata.redacao import frases_canonicas, peca_da_votacao
from oplenario_ia.confianca.citacao import FonteLida, conferir
from oplenario_ia.confianca.numeros import divergencias, normalizar, sinais_numericos
from oplenario_ia.fronteira.contrato import VotacaoContexto
from oplenario_ia.governanca.proveniencia import Fonte, Peca


def contexto(
    sim: int | None = 9,
    nao: int | None = 2,
    abst: int | None = 1,
    *,
    id_: str = "v1",
    objeto: str = "PL 008/2026",
    quorum: str = "maioria_simples",
    necessarios: int | None = None,
    modalidade: str = "nominal",
    resultado: str = "aprovada",
) -> VotacaoContexto:
    return VotacaoContexto.model_validate(
        {
            "id": id_,
            "objeto": objeto,
            "modalidade": modalidade,
            "quorum-tipo": quorum,
            "votos-necessarios": necessarios,
            "base-membros": 13,
            "resultado": resultado,
            "total-sim": sim,
            "total-nao": nao,
            "total-abstencao": abst,
            "encerrada-em": "2026-09-22T22:10:00Z",
        }
    )


def votacao(*args: int | None, **kw: object) -> Peca:
    return peca_da_votacao(contexto(*args, **kw))  # type: ignore[arg-type]


def lida(p: Peca) -> FonteLida:
    assert p.fonte is not None
    return FonteLida(fonte=p.fonte, texto=p.texto)


def canonica(p: Peca) -> str:
    assert p.fonte is not None
    return p.fonte.canonicas[0]


def status(texto: str, *pecas: Peca) -> list[str]:
    return [c.status for c in conferir(texto, [lida(p) for p in pecas])]


def paragrafo(p: Peca, antes: str = "", depois: str = "") -> str:
    """O parágrafo que o fake escreveria, com `antes`/`depois` acrescentados à frase, e a marca de citação."""
    assert p.fonte is not None
    return f"{antes}Votação nominal: PL 008/2026, {canonica(p)}{depois}. [[{p.fonte.id} | {canonica(p)}]]"


# ---------- o conjunto fechado de frases canônicas ----------


def test_frases_canonicas_sao_um_conjunto_fechado_e_pequeno() -> None:
    assert frases_canonicas(contexto()) == ["aprovada por 9 votos a favor, 2 contra e 1 abstenção"]
    assert frases_canonicas(contexto(10, 0, 0, quorum="maioria_absoluta", necessarios=7)) == [
        "aprovada por 10 votos a favor, 0 contra e 0 abstenções",
        "aprovada por unanimidade, com 10 votos a favor",
        "eram necessários 7 votos",
    ]
    assert frases_canonicas(contexto(1, 0, 0, quorum="maioria_absoluta", necessarios=1)) == [
        "aprovada por 1 voto a favor, 0 contra e 0 abstenções",
        "aprovada por unanimidade, com 1 voto a favor",
        "era necessário 1 voto",
    ]
    assert frases_canonicas(contexto(None, None, None, modalidade="simbolica")) == ["aprovada em votação simbólica"]
    assert frases_canonicas(contexto(0, 10, 0, resultado="rejeitada")) == [
        "rejeitada por 0 votos a favor, 10 contra e 0 abstenções"
    ], "unanimidade só para aprovada: a frase não existe se o dado não a sustenta"
    assert len(frases_canonicas(contexto(9, 2, 1, quorum="maioria_absoluta", necessarios=7))) == 2


def test_unanimidade_so_existe_se_o_dado_for_unanime() -> None:
    assert not any("unanimidade" in f for f in frases_canonicas(contexto(9, 2, 1)))
    assert not any("unanimidade" in f for f in frases_canonicas(contexto(9, 0, 1)))
    assert not any("unanimidade" in f for f in frases_canonicas(contexto(0, 0, 0)))
    p = votacao(9, 2, 1)
    assert status(paragrafo(p).replace(canonica(p), "aprovada por unanimidade, com 9 votos a favor", 1), p) == [
        "trecho_nao_encontrado"
    ]


def test_o_paragrafo_com_a_frase_canonica_confere_e_a_unanimidade_tambem() -> None:
    p = votacao(10, 0, 0)
    assert status(paragrafo(p), p) == ["conferida"]
    unanime = paragrafo(p).replace(canonica(p), "aprovada por unanimidade, com 10 votos a favor")
    assert status(unanime, p) == ["conferida"]
    assert status(
        paragrafo(votacao(None, None, None, modalidade="simbolica")), votacao(None, None, None, modalidade="simbolica")
    ) == ["conferida"]


# ---------- o que a revisão de segurança já pegou ----------


@pytest.mark.parametrize(
    "intruso",
    ["¹⁰", "１０", "٩", "९", "⑩", "Ⅳ", "½", "10/2", "1.000", "1,5", "−7", "7º", "7ª", "22/09/2026", "18h30", "19:45"],
)
def test_qualquer_numero_em_qualquer_formato_a_mais_reprova(intruso: str) -> None:
    p = votacao()
    assert status(paragrafo(p, depois=f", com {intruso}"), p) == ["trecho_nao_encontrado"], intruso
    assert status(paragrafo(p, antes=f"{intruso} "), p) == ["trecho_nao_encontrado"], intruso


def test_data_e_hora_nao_sao_mascaradas_a_frase_vai_para_confirmar() -> None:
    p = votacao()
    assert status(paragrafo(p, antes="Em 22/09/2026, às 18h30, "), p) == ["trecho_nao_encontrado"]
    assert status(paragrafo(p, antes="Às 18:30, "), p) == ["trecho_nao_encontrado"]


def test_numero_trocado_no_placar_nao_e_a_frase_canonica() -> None:
    p = votacao(9, 2, 1)
    assert status(paragrafo(p).replace("9 votos a favor", "10 votos a favor", 1), p) == ["trecho_nao_encontrado"]
    assert status(paragrafo(p).replace("2 contra", "9 contra", 1), p) == ["trecho_nao_encontrado"]


def test_campos_trocados_nao_conferem_mesmo_com_os_dois_numeros_no_dado() -> None:
    p = votacao(2, 7, 0)  # no dado: 2 a favor, 7 contra
    trocado = paragrafo(p).replace("2 votos a favor, 7 contra", "7 votos a favor, 2 contra", 1)
    assert "7 votos a favor, 2 contra" in trocado
    assert status(trocado, p) == ["trecho_nao_encontrado"]
    assert status(paragrafo(p), p) == ["conferida"]


def test_o_numero_de_outra_votacao_da_mesma_sessao_nao_confere() -> None:
    a, b = votacao(7, 2, 0, id_="a"), votacao(5, 4, 0, id_="b")
    texto_de_b = paragrafo(b)
    assert status(texto_de_b, a, b) == ["conferida"]
    de_b_citando_a = f"Votação nominal: PL 008/2026, {canonica(b)}. [[votacao:a | {canonica(a)}]]"
    assert status(de_b_citando_a, a, b) == ["trecho_nao_encontrado"], "a frase de B não sustenta a citação de A"


def test_duas_votacoes_no_mesmo_paragrafo_nao_conferem() -> None:
    a, b = votacao(7, 2, 0, id_="a"), votacao(5, 4, 0, id_="b")
    junto = f"{paragrafo(a)} E {canonica(b)}. [[votacao:b | {canonica(b)}]]"
    assert status(junto, a, b) == ["trecho_nao_encontrado", "trecho_nao_encontrado"]
    # placar IGUAL nas duas: a frase serve às duas, e só a regra "uma votação por parágrafo" as barra
    c = votacao(7, 2, 0, id_="c")
    mesma_frase = f"{paragrafo(a)} [[votacao:c | {canonica(c)}]]"
    assert status(mesma_frase, a, c) == ["trecho_nao_encontrado", "trecho_nao_encontrado"]
    assert status(paragrafo(a), a, c) == ["conferida"]
    separado = f"{paragrafo(a)}\n\n{paragrafo(b)}"
    assert status(separado, a, b) == ["conferida", "conferida"]


def test_a_marca_de_outra_fonte_comum_no_paragrafo_nao_atrapalha() -> None:
    p = votacao()
    comum = FonteLida(fonte=Fonte(id="transcricao:t1#1", rotulo="Presidente"), texto="Declaro aprovado o projeto.")
    texto = f"{paragrafo(p)} [[transcricao:t1#1 | Declaro aprovado o projeto.]]"
    assert [c.status for c in conferir(texto, [lida(p), comum])] == ["conferida", "conferida"]


def test_ponto_de_abreviatura_nao_esconde_numero() -> None:
    p = votacao()
    assert status(paragrafo(p, antes="Aprovada por 10 votos, conforme o Sr. Presidente. "), p) == [
        "trecho_nao_encontrado"
    ]
    # sem número, o texto a mais ainda não é moldura: reprova (lista de permitidos), e a moldura sozinha confere
    assert status(paragrafo(p, antes="Conforme o Sr. Presidente, "), p) == ["trecho_nao_encontrado"]
    assert status(paragrafo(p), p) == ["conferida"]


def test_numero_depois_da_marca_no_mesmo_paragrafo_tambem_conta() -> None:
    p = votacao()
    assert status(f"{paragrafo(p)} O placar foi de 10 votos.", p) == ["trecho_nao_encontrado"]
    assert status(f"{paragrafo(p)} Nada mais.", p) == ["trecho_nao_encontrado"]  # fora da moldura
    assert status(f"{paragrafo(p)} Resultado registrado pelo sistema.", p) == ["conferida"]  # só moldura


def test_quebra_de_linha_simples_nao_separa_paragrafo() -> None:
    p = votacao()
    assert status(paragrafo(p, antes="foram 10\n"), p) == ["trecho_nao_encontrado"]


def test_o_numero_do_paragrafo_vizinho_nao_conta() -> None:
    p = votacao()
    assert status(f"Sessão com 12 presentes.\n\n{paragrafo(p)}", p) == ["conferida"]


# ---------- [confirmar]: exclusão exata, sem lavar o resto ----------


def test_confirmar_declarado_fica_fora_e_o_resto_ainda_passa() -> None:
    p = votacao()
    com = paragrafo(p) + " [confirmar: a gravação indica 10 votos a favor; o sistema registra 9]"
    assert status(com, p) == ["conferida"]


def test_confirmar_nao_lava_o_resto_da_frase() -> None:
    p = votacao()
    assert status(paragrafo(p, depois=", com 10 votos") + " [confirmar: x]", p) == ["trecho_nao_encontrado"]
    assert status("Foram 10 votos [confirmar: ok] " + paragrafo(p), p) == ["trecho_nao_encontrado"]
    # sem a frase canônica, o [confirmar] sozinho não faz a votação conferir
    assert status(f"Aprovada. [confirmar: 9 votos] [[votacao:v1 | {canonica(p)}]]", p) == ["trecho_nao_encontrado"]


def test_confirmar_fora_do_formato_exato_nao_e_excluido() -> None:
    p = votacao()
    for forma in (
        "[ confirmar: 10 votos]",
        "[confirmar:10 votos]",
        "[confirmar 10 votos]",
        "[confirmar: a [b] 10 votos]",
    ):
        assert status(paragrafo(p) + " " + forma, p) == ["trecho_nao_encontrado"], forma


# ---------- o que escapa de um interpretador de prosa não escapa daqui ----------


@pytest.mark.parametrize(
    "intruso",
    ["três", "TRÊS", "mil", "dúzia", "metade", "dois terços", "meio", "unanimidade", "unânime", "nenhuma", "todos",
     "ambos", "empate", "maioria", "primeiro", "segundo", "décimo", "IV", "iv", "X", "xii", "uma"],
)  # fmt: skip
def test_palavra_numero_da_lista_e_romano_isolado_reprovam(intruso: str) -> None:
    p = votacao()
    assert status(paragrafo(p, antes=f"{intruso} "), p) == ["trecho_nao_encontrado"], intruso


@pytest.mark.parametrize(
    "disfarce",
    ["1\u200b0", "d\u200bez", "d\u0301ez", "d\u0435z", "t\u0440\u0435s", "1\u00ad0", "1\u2060 0", "\ufeff7"],
    ids=[
        "zwsp-no-numero",
        "zwsp-na-palavra",
        "combinante-sem-composicao",
        "homoglifo-e",
        "homoglifo-tres",
        "hifen-suave",
        "word-joiner",
        "bom",
    ],
)
def test_caractere_invisivel_combinante_ou_homoglifo_reprova(disfarce: str) -> None:
    p = votacao()
    assert status(paragrafo(p, antes=f"{disfarce} "), p) == ["trecho_nao_encontrado"], repr(disfarce)


def test_moldura_que_apresenta_a_votacao_confere_e_texto_comum_fora_dela_nao() -> None:
    p = votacao()
    assert status(paragrafo(p, antes="Colocada em votação a matéria, resultado: "), p) == ["conferida"]
    # antes passava: o parágrafo da votação agora só aceita a moldura conhecida
    assert status(paragrafo(p, antes="Colocada em pauta pela Mesa, a matéria seguiu: "), p) == ["trecho_nao_encontrado"]


def test_expressao_numerica_fora_da_lista_de_palavras_tambem_reprova() -> None:
    # era o limite declarado da lista de proibidos ("larga margem" não é palavra-número); a lista de permitidos fecha
    p = votacao()
    assert status(paragrafo(p, antes="Por larga margem, "), p) == ["trecho_nao_encontrado"]


# ---------- normalização: um só ponto de entrada, igual nos dois lados ----------


def test_normalizar_e_so_nfkc_minusculas_e_espacos() -> None:
    assert normalizar("  \uff21\uff22\uff23\u00a0 Três\n\t\u2079  ") == "abc três 9"
    assert normalizar("Aprovada  Por") == "aprovada por"


def test_a_frase_canonica_escrita_com_outros_espacos_caixa_ou_largura_confere() -> None:
    p = votacao()
    estranha = canonica(p).replace(" ", "\u00a0\u00a0").upper().replace("9", "\uff19")
    assert status(f"Votação nominal: pl 008/2026, {estranha}. [[votacao:v1 | {canonica(p)}]]", p) == ["conferida"]


def test_os_dois_lados_usam_a_mesma_normalizacao() -> None:
    # frases e identificadores do dado passam por normalizar() como o parágrafo: dado escrito "estranho" também casa
    assert divergencias("PL 008/2026, ＡPROVADA por 9 votos", ["aprovada  por 9 votos"], ["Ｐl 008/2026"]) == []
    assert divergencias("aprovada por 9 votos", ["Aprovada Por 9 Votos"], []) == []


def test_sinais_numericos_por_negacao_ampla() -> None:
    assert sinais_numericos(normalizar("aprovada, sem nada")) == []
    assert sinais_numericos(normalizar("¹⁰ Ⅳ ½ ٩")) != []
    assert "iv" in sinais_numericos("iv")
    assert sinais_numericos("civil") == [], "palavra comum que só contém letras romanas não é romano"


# ---------- as outras capacidades não mudam ----------


def test_citacao_de_norma_com_ordinal_confere_como_antes() -> None:
    norma = FonteLida(
        fonte=Fonte(id="norma:lom#art7", rotulo="LOM, art. 7º"),
        texto="Art. 7º Compete privativamente à Câmara Municipal dispor sobre seu Regimento Interno.",
    )
    texto = "Conforme o art. 7º, 10 vereadores votam. [[norma:lom#art7 | Compete privativamente à Câmara Municipal]]"
    assert [c.status for c in conferir(texto, [norma])] == ["conferida"]


def test_o_trecho_errado_segue_reprovando_como_antes() -> None:
    p = votacao()
    assert p.fonte is not None
    assert status(f"{canonica(p)}. [[votacao:v1 | aprovada por 10 votos a favor, 2 contra e 1 abstenção]]", p) == [
        "trecho_nao_encontrado"
    ]


# ---------- propriedade: o roteiro do fake confere; qualquer número ou sinal a mais, não ----------

SINAIS = ["٩", "⁹", "Ⅳ", "três", "iv", "mil", "unanimidade", "metade"]


def _favor(n: int) -> str:
    return f"{n} {'voto' if n == 1 else 'votos'} a favor"


def _abstencoes(n: int) -> str:
    return f"{n} {'abstenção' if n == 1 else 'abstenções'}"


def test_propriedade_o_placar_do_roteiro_confere_e_qualquer_sinal_ou_numero_a_mais_nao() -> None:
    certos = trocas = insercoes = 0
    for sim in range(31):
        for nao in range(31):
            for abst in range(31):
                p = votacao(sim, nao, abst)
                assert p.fonte is not None
                fonte = lida(p)
                corpo, _ = _votacao(p.fonte.id, p.texto)  # o parágrafo que o roteiro do fake escreve
                assert [c.status for c in conferir(corpo, [fonte])] == ["conferida"], corpo
                certos += 1
                frase, marca = corpo.split(" [[", 1)  # só a frase: a marca (o trecho citado) fica como está
                for antigo, novo in (
                    (_favor(sim), _favor((sim + 1) % 31)),
                    (f"{nao} contra", f"{(nao + 7) % 31} contra"),
                    (_abstencoes(abst), _abstencoes((abst + 13) % 31)),
                ):
                    errado = f"{frase.replace(antigo, novo, 1)} [[{marca}"
                    assert [c.status for c in conferir(errado, [fonte])] == ["trecho_nao_encontrado"], errado
                    trocas += 1
                if (sim + nao + abst) % 5:
                    continue  # as inserções em todos os placares seriam 6x mais lentas sem provar mais
                for sinal in SINAIS:
                    for posicao in (0, frase.index(":") + 1, len(frase)):
                        errado = f"{frase[:posicao]} {sinal} {frase[posicao:]} [[{marca}"
                        assert [c.status for c in conferir(errado, [fonte])] == ["trecho_nao_encontrado"], errado
                        insercoes += 1
    assert certos == 31**3 and trocas == 3 * 31**3 and insercoes > 100_000


# ---- a sobra é lista de PERMITIDOS: o que inverte o sentido sem usar número também reprova ----

_CANONICA = "aprovada por 9 votos a favor, 2 contra e 1 abstenção"
_IDENT = "PL 008/2026"


def _diverge(paragrafo: str) -> bool:
    return bool(divergencias(paragrafo, [_CANONICA], [_IDENT]))


def test_a_moldura_do_roteiro_confere() -> None:
    assert not _diverge(f"Votação nominal: {_IDENT}, {_CANONICA}.")


def test_negacao_antes_da_frase_canonica_reprova() -> None:
    assert _diverge(f"Votação nominal: {_IDENT} não foi {_CANONICA}.")
    assert _diverge(f"Votação nominal: {_IDENT}, jamais {_CANONICA}.")


def test_prefixo_colado_na_frase_canonica_reprova() -> None:
    # "desaprovada por 9 votos…" contém a canônica por substring: o prefixo que sobra tem de reprovar
    assert _diverge(f"Votação nominal: {_IDENT}, des{_CANONICA}.")


def test_resultado_contrario_ao_lado_reprova() -> None:
    assert _diverge(f"Votação nominal: {_IDENT}, {_CANONICA}, e rejeitada.")
    assert _diverge(f"Votação nominal: {_IDENT}, {_CANONICA}. A matéria foi derrotada.")


def test_numero_colado_em_palavra_reprova() -> None:
    assert _diverge(f"Votação nominal: {_IDENT}, {_CANONICA}, com dezvotos de diferença.")


def test_simbolo_fora_da_pontuacao_comum_reprova() -> None:
    assert _diverge(f"Votação nominal: {_IDENT}, {_CANONICA} (+ outros).")
    assert _diverge(f"Votação nominal: {_IDENT}, {_CANONICA} %.")
