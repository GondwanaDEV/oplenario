"""O pedido de ata ao núcleo: as peças (dados da sessão + falas transcritas, cada bloco uma FONTE citável) e as
instruções do produto. O rascunho é SEMPRE proposto — a secretaria revisa, edita e publica no core (§16.8, §22.3.4).

As falas transcritas entram como conteúdo de TERCEIRO (§22.11.4): quem falou na tribuna não é a Casa redigindo, e uma
fala pode conter um "ignore as instruções". Isso deixa a execução contaminada e o rascunho sai sempre marcado para
revisar com atenção — é a verdade sobre um texto que nasceu de áudio.

As votações ENCERRADAS da sessão entram como fonte própria cada uma (`votacao:<id>`), montada pelo core a partir do
registro do sistema: objeto, modalidade, quórum, resultado e placar. É um FATO, não uma fala: a Camada de Confiança
confere os números que a ata cita contra ele (`Fonte.estruturada`), e quando a transcrição diz outra coisa vale o
dado. O voto de cada vereador NÃO entra — nem na votação nominal: o contexto traz só os totais.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from oplenario_ia.armazem.porta import TranscricaoGuardada
from oplenario_ia.confianca.citacao import MARCA
from oplenario_ia.fronteira.contrato import ContextoSessao, VotacaoContexto
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Fonte, Peca, Proveniencia, Sigilo

OPERACAO = "ata.redigir"
PROMPT_VERSAO = "ata-v2"

# O beachhead (CE) não tem horário de verão: UTC-3 fixo e determinístico. Casa de outro fuso = config quando existir.
FUSO_DA_CASA = timezone(timedelta(hours=-3))

INSTRUCOES = (
    "Você redige o RASCUNHO da ata de uma sessão da Câmara Municipal, a partir dos dados da sessão e das falas "
    "transcritas da gravação. Escreva em português formal, no estilo de ata legislativa: terceira pessoa, pretérito, "
    "parágrafos corridos, na ordem em que as coisas aconteceram. Registre quem fez uso da palavra e o essencial do "
    "que disse, as matérias apreciadas e os resultados anunciados pela Presidência.\n"
    "Não invente nomes, números, votos, horários nem resultados. O que as fontes não disserem com clareza, escreva "
    "entre colchetes como ponto a confirmar, por exemplo: [confirmar: resultado da votação do Projeto de Lei nº 12]. "
    "Fala sem orador identificado é atribuída a 'um orador não identificado' — nunca adivinhe quem falou.\n"
    "As fontes 'votacao:' são o REGISTRO DO SISTEMA das votações encerradas, na ordem em que ocorreram. Cada votação "
    "ganha um parágrafo só dela (uma votação por parágrafo), com o objeto exatamente como a linha 'Matéria votada' "
    "traz e UMA das frases das linhas 'Frase do resultado', 'Frase da unanimidade' ou 'Frase do quórum', copiada "
    "literalmente, sem trocar nenhuma palavra; nesse parágrafo não escreva nenhum outro número, data, hora, ordinal "
    "nem quantidade. Depois cite a fonte da votação, com a frase copiada. Nunca diga como cada vereador votou: a fonte "
    "não traz isso e a lista nominal fica no anexo do sistema. Se a gravação disser outro resultado ou outro placar, "
    "vale o dado do sistema: escreva a frase do sistema e acrescente [confirmar: a gravação indica X; o sistema "
    "registra Y]. Votação que a gravação menciona e que não consta nas fontes do sistema fica como ponto a "
    "confirmar.\n"
    "Não escreva cabeçalho de assinaturas nem comentários sobre o seu trabalho: devolva só o texto da ata."
)

PONTO_A_CONFIRMAR = re.compile(r"\[\s*confirmar\s*:\s*([^\]]+?)\s*\]", re.IGNORECASE)
MODALIDADES = {"nominal": "nominal", "simbolica": "simbólica", "secreta": "secreta"}
TIPOS_SESSAO = {
    "ordinaria": "ordinária",
    "extraordinaria": "extraordinária",
    "solene": "solene",
    "especial": "especial",
}


def relogio(s: float) -> str:
    t = max(0, int(s))
    h, m, seg = t // 3600, (t % 3600) // 60, t % 60
    return f"{h}:{m:02d}:{seg:02d}" if h else f"{m}:{seg:02d}"


def _local(instante: datetime | None) -> str | None:
    if instante is None:
        return None
    return instante.astimezone(FUSO_DA_CASA).strftime("%d/%m/%Y às %H:%M")


@dataclass(frozen=True)
class Bloco:
    """Frases seguidas da mesma pessoa numa gravação — a unidade que a ata lê (e que o modelo cita)."""

    fonte_id: str
    orador: str | None
    inicio: float
    fim: float
    texto: str


def blocos(t: TranscricaoGuardada) -> list[Bloco]:
    saida: list[Bloco] = []
    for tr in t.trechos:
        orador = tr.orador_nome if tr.orador_id else None
        if saida and saida[-1].orador == orador:
            b = saida[-1]
            saida[-1] = Bloco(b.fonte_id, orador, b.inicio, tr.fim, f"{b.texto} {tr.texto}".strip())
        else:
            saida.append(Bloco(f"transcricao:{t.id}#{len(saida) + 1}", orador, tr.inicio, tr.fim, tr.texto.strip()))
    return [b for b in saida if b.texto]


def _dados_da_sessao(ctx: ContextoSessao) -> str:
    s = ctx.sessao
    linhas = [f"Sessão {TIPOS_SESSAO.get(s.tipo_sessao, s.tipo_sessao)} nº {s.numero_sequencial}."]
    if aberta := _local(s.aberta_em):
        linhas.append(f"Aberta em {aberta}.")
    if encerrada := _local(s.encerrada_em):
        linhas.append(f"Encerrada em {encerrada}.")
    oradores = [f"{f.orador_nome} ({f.fase})" for f in ctx.falas if f.orador_nome]
    if oradores:
        linhas.append("Palavra concedida pela Mesa, em ordem: " + "; ".join(oradores) + ".")
    return " ".join(linhas)


def _plural(n: int, singular: str, plural: str) -> str:
    return f"{n} {singular if n == 1 else plural}"


def _quorum(v: VotacaoContexto) -> str:
    base = f" de {v.base_membros} membros da Casa" if v.base_membros is not None else ""
    if v.quorum_tipo == "maioria_simples":
        return "maioria simples (mais votos sim do que não)"
    necessarios = f"{v.votos_necessarios} votos sim{base}" if v.votos_necessarios is not None else "votos sim"
    nome = {
        "maioria_absoluta": "maioria absoluta",
        "maioria_qualificada_2_3": "dois terços dos membros",
        "maioria_qualificada_3_5": "três quintos dos membros",
    }[v.quorum_tipo]
    return f"{nome} ({necessarios})"


def _placar(v: VotacaoContexto) -> str | None:
    if v.total_sim is None or v.total_nao is None or v.total_abstencao is None:
        return None
    return ", ".join(
        [
            _plural(v.total_sim, "voto sim", "votos sim"),
            _plural(v.total_nao, "voto não", "votos não"),
            _plural(v.total_abstencao, "abstenção", "abstenções"),
        ]
    )


def texto_da_votacao(v: VotacaoContexto) -> str:
    """O registro do sistema de uma votação, em linhas `Rótulo: valor` (o redator fake lê assim; o modelo, também). As
    linhas `Frase …` são as frases canônicas: as únicas que a ata pode usar para o placar e o quórum."""
    placar = _placar(v) or f"sem contagem individual (votação {MODALIDADES[v.modalidade]})"
    linhas = [
        f"Matéria votada: {v.objeto}",
        f"Modalidade: {MODALIDADES[v.modalidade]}",
        f"Quórum exigido: {_quorum(v)}",
        f"Placar: {placar}",
        f"Resultado: {v.resultado}",
        f"Frase do resultado: {frase_do_resultado(v)}",
    ]
    if (u := frase_da_unanimidade(v)) is not None:
        linhas.append(f"Frase da unanimidade: {u}")
    if (q := frase_do_quorum(v)) is not None:
        linhas.append(f"Frase do quórum: {q}")
    return "\n".join(linhas)


def frase_do_resultado(v: VotacaoContexto) -> str:
    """A frase canônica do resultado e do placar. Só aqui, a partir do dado; o redator fake a copia e o conferidor
    (`confianca/numeros.py`) a casa por substring exata."""
    r = v.resultado
    if v.total_sim is None or v.total_nao is None or v.total_abstencao is None:
        return f"{r} em votação simbólica" if v.modalidade == "simbolica" else f"{r}, sem contagem de votos"
    abstencoes = _plural(v.total_abstencao, "abstenção", "abstenções")
    return f"{r} por {_plural(v.total_sim, 'voto', 'votos')} a favor, {v.total_nao} contra e {abstencoes}"


def frase_da_unanimidade(v: VotacaoContexto) -> str | None:
    """Só existe se o dado é unânime: aprovada, ao menos um voto a favor, nenhum contra, nenhuma abstenção."""
    if v.resultado == "aprovada" and (v.total_sim or 0) > 0 and v.total_nao == 0 and v.total_abstencao == 0:
        return f"aprovada por unanimidade, com {_plural(v.total_sim or 0, 'voto', 'votos')} a favor"
    return None


def frase_do_quorum(v: VotacaoContexto) -> str | None:
    n = v.votos_necessarios
    if n is None:
        return None
    return "era necessário 1 voto" if n == 1 else f"eram necessários {n} votos"


def frase_da_modalidade(v: VotacaoContexto) -> str:
    """A modalidade como a ata a escreve ("votação nominal"). Peça canônica da fonte, não moldura: só a do DADO casa."""
    return f"votação {MODALIDADES[v.modalidade]}"


def duvidas_canonicas(v: VotacaoContexto) -> list[str]:
    """O que pode fechar um `[confirmar: a gravação indica N …]` desta votação: o rótulo e o valor do registro."""
    if v.total_sim is None or v.total_nao is None or v.total_abstencao is None:
        return []
    return [
        f"votos a favor; o sistema registra {v.total_sim}",
        f"votos contra; o sistema registra {v.total_nao}",
        f"abstenções; o sistema registra {v.total_abstencao}",
    ]


def frases_canonicas(v: VotacaoContexto) -> list[str]:
    """O conjunto FECHADO de frases que a ata pode usar para esta votação — a única fonte de verdade do que confere."""
    return [f for f in (frase_do_resultado(v), frase_da_unanimidade(v), frase_do_quorum(v)) if f is not None]


def peca_da_votacao(v: VotacaoContexto) -> Peca:
    return Peca(
        texto=texto_da_votacao(v),
        # o sistema já publica o resultado e os totais de toda votação encerrada (inclusive a secreta); o voto de cada
        # vereador não chega aqui — não está no contexto. Por isso é público e NÃO leva `voto_secreto`.
        proveniencia=Proveniencia(origem="core.votacao", sigilo=Sigilo.PUBLICO),
        fonte=Fonte(
            id=f"votacao:{v.id}",
            rotulo=f"Votação de {v.objeto}, registrada pelo sistema",
            estruturada=True,
            canonicas=frases_canonicas(v),
            identificadores=[v.objeto, frase_da_modalidade(v)],
            duvidas_canonicas=duvidas_canonicas(v),
        ),
    )


def ordenar(ctx: ContextoSessao, transcricoes: list[TranscricaoGuardada]) -> list[TranscricaoGuardada]:
    """Na ordem das gravações da sessão (início do segmento); a que o contexto não conhece vai para o fim."""
    ordem = {seg.id: i for i, seg in enumerate(sorted(ctx.segmentos, key=lambda s: s.iniciou_em))}
    return sorted(transcricoes, key=lambda t: (ordem.get(t.segmento_id, len(ordem)), t.criado_em or datetime.min))


def pedido_de_ata(
    ctx: ContextoSessao, transcricoes: list[TranscricaoGuardada], ente_id: str, correlation_id: str
) -> PedidoGovernado:
    pecas = [
        Peca(
            texto=_dados_da_sessao(ctx),
            proveniencia=Proveniencia(origem="core.sessao", sigilo=Sigilo.PUBLICO),
            fonte=Fonte(id=f"sessao:{ctx.sessao.id}", rotulo="Dados da sessão registrados pela Mesa"),
        )
    ]
    # as votações vêm logo depois dos dados da sessão, na ordem em que foram encerradas (o core já as manda ordenadas;
    # ordenar aqui de novo não custa e a ata nunca depende da ordem do fio)
    pecas += [peca_da_votacao(v) for v in sorted(ctx.votacoes, key=lambda v: (v.encerrada_em, v.id))]
    for t in ordenar(ctx, transcricoes):
        for b in blocos(t):
            quem = b.orador or "Orador não identificado"
            pecas.append(
                Peca(
                    texto=b.texto,
                    proveniencia=Proveniencia(origem="transcricao", sigilo=Sigilo.PUBLICO, terceiro=True),
                    fonte=Fonte(
                        id=b.fonte_id,
                        rotulo=f"{quem}, {relogio(b.inicio)}–{relogio(b.fim)}",
                        versao=f"transcrição v{t.versao}",
                    ),
                )
            )
    return PedidoGovernado(
        ente_id=ente_id, correlation_id=correlation_id, operacao=OPERACAO, instrucoes=INSTRUCOES, pecas=pecas
    )


def pontos_a_confirmar(texto: str) -> list[str]:
    return [m.group(1) for m in PONTO_A_CONFIRMAR.finditer(texto)]


def texto_limpo(texto: str) -> str:
    """O texto que vai para o editor da ata: sem as marcas de citação (elas ficam na tela de revisão, não na ata).
    Os pontos a confirmar FICAM — a pessoa precisa resolvê-los antes de publicar."""
    sem_marcas = MARCA.sub("", texto)
    linhas = [re.sub(r"[ \t]{2,}", " ", ln).rstrip() for ln in sem_marcas.split("\n")]
    return re.sub(r" +([.,;:])", r"\1", "\n".join(linhas)).strip()
