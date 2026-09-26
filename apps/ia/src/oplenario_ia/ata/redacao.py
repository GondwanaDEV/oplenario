"""O pedido de ata ao núcleo: as peças (dados da sessão + falas transcritas, cada bloco uma FONTE citável) e as
instruções do produto. O rascunho é SEMPRE proposto — a secretaria revisa, edita e publica no core (§16.8, §22.3.4).

As falas transcritas entram como conteúdo de TERCEIRO (§22.11.4): quem falou na tribuna não é a Casa redigindo, e uma
fala pode conter um "ignore as instruções". Isso deixa a execução contaminada e o rascunho sai sempre marcado para
revisar com atenção — é a verdade sobre um texto que nasceu de áudio.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from oplenario_ia.armazem.porta import TranscricaoGuardada
from oplenario_ia.confianca.citacao import MARCA
from oplenario_ia.fronteira.contrato import ContextoSessao
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Fonte, Peca, Proveniencia, Sigilo

OPERACAO = "ata.redigir"
PROMPT_VERSAO = "ata-v1"

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
    "Não escreva cabeçalho de assinaturas nem comentários sobre o seu trabalho: devolva só o texto da ata."
)

PONTO_A_CONFIRMAR = re.compile(r"\[\s*confirmar\s*:\s*([^\]]+?)\s*\]", re.IGNORECASE)
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
