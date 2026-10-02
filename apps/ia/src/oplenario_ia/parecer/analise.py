"""O copiloto do relator (ADR-0019, Eixo 5 / fatia 2): no editor do parecer de comissão, o relator — ou a secretaria que
redige por ele — pede um RASCUNHO da seção de análise de constitucionalidade e juridicidade. A IA lê a matéria (ementa,
autoria e o texto vigente) e os dispositivos da Lei Orgânica e do Regimento da Casa que o índice acha pelo sentido (só a
versão vigente está lá, B.4b), e redige a análise citando cada dispositivo; onde não há fonte, deixa `[confirmar: …]`.

É rascunho, nunca parecer: não grava nada (só o registro da execução, B4), volta ao editor e o relator revisa, edita e
salva pelo fluxo de sempre — quem assina o parecer da comissão é o relator. Um passo só pelo núcleo (filtro de
governança, citação conferida por parágrafo, incerteza, registro, custo e cota da Casa valem igual).

A matéria é FONTE citável (`materia:<id>`) e é conteúdo de terceiro (o texto é do autor, não do relator): vai delimitada
ao modelo e marca a execução — o rascunho sai sempre "revisar com atenção", como o resumo cidadão (A.8). Sem normas
publicadas na Casa, o rascunho é feito só com a matéria e diz isso.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Literal

from pydantic import BaseModel, Field

from oplenario_ia.armazem.porta import Resultado
from oplenario_ia.conferencia.roteiro import NOMES, identificacao
from oplenario_ia.confianca.artefato import Artefato
from oplenario_ia.confianca.indisponivel import Indisponivel
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Fonte, Peca, Proveniencia, Sigilo
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.requerimento.copiloto import fontes_de_norma

OPERACAO = "relator.analisar"
PROMPT_VERSAO = "relator-analise-v1"
MAX_FONTES = 6
TETO_TEXTO_MATERIA = 30_000
# além da espécie e da ementa, o que toda análise de constitucionalidade e juridicidade confere na LOM e no Regimento
CONSULTAS_DE_ASPECTO = ("competência do Município para legislar", "iniciativa das leis", "quórum de aprovação")

# as linhas que dizem ao modelo (e ao roteiro fake) em que pé estão as normas da Casa
NORMAS_NAO_PUBLICADAS = "Normas da Casa: não publicadas na plataforma."
NORMAS_SEM_DISPOSITIVO = "Normas da Casa: nenhum dispositivo encontrado sobre o assunto."

Normas = Literal["sem-normas", "sem-dispositivo", "citadas"]

INSTRUCOES = (
    "Você ajuda o relator de uma comissão da Câmara Municipal a redigir a seção de ANÁLISE do parecer da comissão: a "
    "constitucionalidade e a juridicidade da proposição recebida, à luz das normas da Casa (Lei Orgânica do Município, "
    "Regimento Interno). Escreva em linguagem formal e simples, um parágrafo por aspecto: o objeto da proposição; a "
    "competência do Município e da Câmara; a iniciativa; a espécie normativa e a forma; o quórum e a tramitação. Cada "
    "afirmação sobre o que uma norma exige cita o dispositivo recebido. Onde não houver dispositivo recebido que "
    "sustente o ponto, não afirme: escreva [confirmar: o que o relator deve verificar]. Não dê o voto nem a conclusão "
    "(favorável, contrário) — são do relator. Não chame o texto de parecer. Não invente dispositivos, números nem "
    "datas. Devolva só o texto da análise, em parágrafos, sem título."
)


class PedidoAnalise(BaseModel):
    proposicao_id: str = Field(min_length=1, max_length=64)
    tipo: str = Field(min_length=1, max_length=64)
    ano: int
    sequencial: int
    ementa: str = Field(min_length=1, max_length=2_000)
    texto: str | None = Field(default=None, max_length=500_000)
    autor_texto: str | None = Field(default=None, max_length=500)
    comissao: str | None = Field(default=None, max_length=300)
    normas_publicadas: bool = True
    correlation_id: str = Field(min_length=1, max_length=64)


class RascunhoAnalise(BaseModel):
    analise: Artefato | None = None
    fontes: dict[str, str] = {}  # fonte-id -> rótulo legível ("Lei Orgânica do Município, art. 25 (…)")
    normas: Normas = "sem-dispositivo"
    indisponivel: Indisponivel | None = None


def _ident(p: PedidoAnalise) -> str:
    return identificacao({"tipo": p.tipo, "sequencial": p.sequencial, "ano": p.ano})


def peca_da_materia(p: PedidoAnalise) -> Peca:
    """A proposição como FONTE citável (`materia:<id>`). O texto é do autor: conteúdo de terceiro (delimitado)."""
    linhas = [f"Ementa: {p.ementa}"]
    if p.autor_texto:
        linhas.append(f"Autoria: {p.autor_texto}")
    if p.texto:
        linhas.append(f"Texto:\n{p.texto[:TETO_TEXTO_MATERIA]}")
    return Peca(
        texto="\n".join(linhas),
        proveniencia=Proveniencia(origem="core.proposicao", sigilo=Sigilo.PUBLICO, terceiro=True),
        fonte=Fonte(id=f"materia:{p.proposicao_id}", rotulo=_ident(p)),
    )


def _peca_do_pedido(p: PedidoAnalise, situacao_normas: str | None) -> Peca:
    # o pedido é do próprio relator a quem o copiloto serve: não é conteúdo de terceiro
    comissao = f" da {p.comissao}" if p.comissao else ""
    linhas = [
        f"Pedido do relator{comissao}: rascunhar a análise de constitucionalidade e juridicidade do parecer sobre "
        f"{_ident(p)}."
    ]
    if situacao_normas:
        linhas.append(situacao_normas)
    return Peca(texto="\n".join(linhas), proveniencia=Proveniencia(origem="pessoa.pedido", sigilo=Sigilo.PUBLICO))


def consultas(p: PedidoAnalise) -> list[str]:
    """O que buscar nas normas: a ESPÉCIE (o Regimento trata de 'projeto de lei'...), a ementa (o assunto) e os aspectos
    que toda análise de juridicidade confere. Cada consulta com o teto da busca."""
    especie = NOMES.get(p.tipo, "")
    return [c[:300] for c in (especie, p.ementa, *CONSULTAS_DE_ASPECTO) if len(c.strip()) >= 2]


def ler_normas(buscar: Callable[[str], list[Resultado]], p: PedidoAnalise) -> list[Peca]:
    """Os dispositivos que o índice acha para cada consulta, sem repetir, até `MAX_FONTES`. Consulta que falha (índice
    fora) só não contribui: a análise sai sem aquela base, nunca quebra."""
    achados: list[Resultado] = []
    for c in consultas(p):
        try:
            achados.extend(buscar(c))
        except Exception:
            continue
    return fontes_de_norma(achados)[:MAX_FONTES]


def _situacao(p: PedidoAnalise, fontes: list[Peca]) -> str | None:
    if not p.normas_publicadas:
        return NORMAS_NAO_PUBLICADAS
    if not fontes:
        return NORMAS_SEM_DISPOSITIVO
    return None


def rascunhar(
    nucleo: Nucleo,
    pedido: PedidoAnalise,
    ente_id: str,
    buscar: Callable[[str], list[Resultado]] | None = None,
) -> RascunhoAnalise:
    fontes = ler_normas(buscar, pedido) if (buscar is not None and pedido.normas_publicadas) else []
    r = nucleo.executar(
        PedidoGovernado(
            ente_id=ente_id,
            correlation_id=pedido.correlation_id,
            operacao=OPERACAO,
            instrucoes=INSTRUCOES,
            pecas=[_peca_do_pedido(pedido, _situacao(pedido, fontes)), peca_da_materia(pedido), *fontes],
            max_tokens=3_000,
        ),
        politica="por_paragrafo",
    )
    if not pedido.normas_publicadas:
        normas: Normas = "sem-normas"
    elif isinstance(r, Artefato) and any(
        c.fonte_id.startswith("norma:") and c.status == "conferida" for c in r.citacoes
    ):
        normas = "citadas"
    else:
        normas = "sem-dispositivo"
    if isinstance(r, Indisponivel):
        return RascunhoAnalise(normas=normas, indisponivel=r)
    rotulos = {
        p.fonte.id: (f"{p.fonte.rotulo} ({p.fonte.versao})" if p.fonte.versao else p.fonte.rotulo)
        for p in [peca_da_materia(pedido), *fontes]
        if p.fonte
    }
    return RascunhoAnalise(analise=r, fontes=rotulos, normas=normas)
