"""O pedido de resumo cidadão ao núcleo: a ementa e o texto da proposição, cada parte uma FONTE citável, e as
instruções do produto. O rascunho é SEMPRE proposto — a secretaria revisa, edita e publica no core, e só o publicado
vai ao portal (§16.8, §22.3.4).

O texto da proposição entra como conteúdo de TERCEIRO (§22.11.4): quem escreveu foi o autor da matéria, não a Casa
pedindo o resumo, e um texto pode carregar um "ignore as instruções". A execução fica contaminada e o rascunho sai
marcado para revisar com atenção — a verdade sobre um resumo de texto alheio.
"""

from __future__ import annotations

import re

from oplenario_ia.fronteira.contrato import TextoProposicao
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Fonte, Peca, Proveniencia, Sigilo

OPERACAO = "resumo.redigir"
PROMPT_VERSAO = "resumo-v1"

# Texto maior que isto (um código inteiro) é resumido pelo começo: o resumo cidadão fala do que a matéria propõe, e o
# essencial está nos primeiros artigos. O teto protege o custo por Casa (§22.11.8).
TETO_TEXTO = 30_000
TETO_PARTE = 1_500

INSTRUCOES = (
    "Você escreve o RASCUNHO de um resumo em linguagem simples de uma proposição da Câmara Municipal, para o cidadão "
    "que lê o portal da Casa. Explique o que a proposição propõe, a quem ela se aplica e o que muda na prática se for "
    "aprovada. Frases curtas, palavras do dia a dia, sem juridiquês: quando precisar de um termo técnico, explique-o. "
    "Dois a quatro parágrafos curtos, no máximo 180 palavras.\n"
    "Seja neutro: não opine, não diga se a proposição é boa ou ruim, não preveja se será aprovada. Não invente "
    "números, prazos, valores nem nomes que o texto não traga; se o texto não disser algo, não escreva sobre isso.\n"
    "Não escreva título nem comentários sobre o seu trabalho: devolva só o texto do resumo."
)

TIPOS = {
    "projeto_lei": "Projeto de Lei",
    "projeto_lei_complementar": "Projeto de Lei Complementar",
    "projeto_resolucao": "Projeto de Resolução",
    "projeto_decreto_legislativo": "Projeto de Decreto Legislativo",
    "proposta_emenda_lom": "Proposta de Emenda à Lei Orgânica",
    "requerimento": "Requerimento",
    "indicacao": "Indicação",
    "mocao": "Moção",
}

_ROTULO = re.compile(r"^\s*((Art\.?|Artigo)\s*\d+[ºo°]?|§\s*\d+[ºo°]?|Parágrafo único|[IVXLC]+\s*[-–—])", re.IGNORECASE)


def identificacao(t: TextoProposicao) -> str:
    return f"{TIPOS.get(t.tipo, t.tipo)} nº {t.sequencial}/{t.ano}"


def partes(texto: str) -> list[str]:
    """O texto em partes citáveis: parágrafos (linha em branco) e, dentro deles, cada dispositivo que começa linha
    (Art., §, inciso). Parte longa demais é cortada em frases — a citação precisa caber num trecho."""
    brutos: list[str] = []
    for bloco in re.split(r"\n\s*\n", texto.strip()):
        atual = ""
        for linha in bloco.split("\n"):
            linha = linha.strip()
            if not linha:
                continue
            if atual and _ROTULO.match(linha):
                brutos.append(atual)
                atual = linha
            else:
                atual = f"{atual} {linha}".strip()
        if atual:
            brutos.append(atual)
    saida: list[str] = []
    for p in brutos:
        while len(p) > TETO_PARTE:
            corte = max(p.rfind(". ", 0, TETO_PARTE), p.rfind("; ", 0, TETO_PARTE))
            corte = corte + 1 if corte > 0 else TETO_PARTE
            saida.append(p[:corte].strip())
            p = p[corte:].strip()
        if p:
            saida.append(p)
    return saida


def _rotulo(parte: str, n: int) -> str:
    m = _ROTULO.match(parte)
    return m.group(1).strip() if m else f"trecho {n}"


def pedido_de_resumo(t: TextoProposicao, ente_id: str, correlation_id: str) -> PedidoGovernado:
    def peca(texto: str, fonte_id: str, rotulo: str) -> Peca:
        return Peca(
            texto=texto,
            proveniencia=Proveniencia(origem="core.proposicao", sigilo=Sigilo.PUBLICO, terceiro=True),
            fonte=Fonte(id=fonte_id, rotulo=rotulo, versao=t.texto_sha256[:19]),
        )

    base = f"proposicao:{t.proposicao_id}"
    cabeca = identificacao(t) + (f", de autoria de {t.autor_texto}" if t.autor_texto else "") + "."
    pecas = [peca(f"{cabeca} Ementa: {t.ementa}", f"{base}#ementa", f"{identificacao(t)} — ementa")]
    usados = 0
    for n, p in enumerate(partes(t.texto or ""), start=1):
        if usados + len(p) > TETO_TEXTO:
            break
        usados += len(p)
        pecas.append(peca(p, f"{base}#p{n}", f"{identificacao(t)} — {_rotulo(p, n)}"))
    return PedidoGovernado(
        ente_id=ente_id, correlation_id=correlation_id, operacao=OPERACAO, instrucoes=INSTRUCOES, pecas=pecas
    )
