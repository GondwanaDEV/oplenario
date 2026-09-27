"""Os contratos da fronteira core <-> IA (ADR-0008), espelho dos `wire/out` e `adapters/in` do módulo
`integracao_ia` do core. Chaves em kebab-case no fio (a convenção do core); snake_case no Python."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


def _kebab(nome: str) -> str:
    return nome.replace("_", "-")


class Fio(BaseModel):
    model_config = ConfigDict(alias_generator=_kebab, populate_by_name=True, extra="ignore")


# ---------- core -> IA ----------


class EventoFeed(Fio):
    seq: int
    ente_id: str
    tipo: str
    versao: int
    chave: str
    payload: dict[str, Any]
    criado_em: datetime


class Feed(Fio):
    eventos: list[EventoFeed]
    proximo: int


class GravacaoVinculadaV1(Fio):
    segmento_id: str
    sessao_id: str
    conteudo_uri: str
    contexto_uri: str


class AtaSolicitadaV1(Fio):
    """A secretaria pediu o rascunho da ata (Faixa A / A.6b). A IA usa as transcrições que ela mesma guarda."""

    solicitacao_id: str
    sessao_id: str
    contexto_uri: str


class AtaRevisadaEPublicadaV1(Fio):
    """A secretaria publicou uma ata que partiu de um rascunho nosso (A.6c): a revisão humana volta para medir."""

    sessao_id: str
    rascunho_id: str
    versao_ata: int = Field(ge=1)
    publicada_por: str
    conteudo_sha256: str
    conteudo_uri: str


class ProposicaoIndexavelV1(Fio):
    """`ProposicaoProtocolada`/`ProposicaoAtualizada` v1 (A.4): o snapshot público que entra no índice. Proposição é
    ato público; número, tipo e estado o core completa na hora de mostrar."""

    proposicao_id: str
    ementa: str
    autor_texto: str | None = None


class NormaVigenteV1(Fio):
    """`NormaVigente` v1 (B.4b, ADR-0011): uma versão de norma de referência da Casa passou a valer. Só identidade: os
    dispositivos a IA lê pela fronteira, e só os da versão vigente."""

    norma_id: str
    versao_id: str
    especie: str


class DispositivoNorma(Fio):
    endereco: str
    rotulo: str
    tipo: str
    texto: str
    agrupador: str | None = None


class DispositivosDaNorma(Fio):
    """Os dispositivos da versão vigente de uma norma, lidos no core para o índice (B.4b)."""

    norma_id: str
    versao_id: str
    especie: str
    titulo: str
    consolidada_ate: str | None = None
    dispositivos: list[DispositivoNorma]


class TextoProposicao(Fio):
    """O texto público de uma proposição, lido no core para o resumo cidadão (A.8). `texto-sha256` identifica a versão
    lida (ementa + texto): é o que decide se o resumo precisa ser refeito."""

    proposicao_id: str
    tipo: str
    ano: int
    sequencial: int
    ementa: str
    autor_texto: str | None = None
    texto: str | None = None
    texto_sha256: str


class AtaPublicada(Fio):
    versao: int
    texto: str
    conteudo_sha256: str
    origem_redacao: str


class SessaoContexto(Fio):
    id: str
    tipo_sessao: str
    numero_sequencial: int
    estado: str
    aberta_em: datetime | None = None
    encerrada_em: datetime | None = None


class SegmentoContexto(Fio):
    id: str
    iniciou_em: datetime
    encerrou_em: datetime | None = None
    conteudo_uri: str


class FalaContexto(Fio):
    """Uma fala registrada pela Mesa: a âncora do Caminho C (quem tinha a palavra, e quando)."""

    id: str
    orador_id: str
    orador_nome: str | None = None
    tipo_fala: str
    fase: str
    fala_pai_id: str | None = None
    iniciou_em: datetime
    encerrou_em: datetime | None = None


class ContextoSessao(Fio):
    sessao: SessaoContexto
    segmentos: list[SegmentoContexto]
    falas: list[FalaContexto]


# ---------- IA -> core ----------

CategoriaFalha = Literal["infraestrutura", "sobrecarga", "entrada", "modelo"]


class TranscricaoConcluidaV1(Fio):
    sessao_id: str
    segmento_id: str
    transcricao_id: str
    versao_transcricao: int = Field(ge=1)
    idioma: str
    duracao_s: float = Field(ge=0)
    n_trechos: int = Field(ge=0)
    cobertura_atribuida: float = Field(ge=0, le=1)
    modelo_asr: str
    modelo_diarizacao: str | None = None


class TranscricaoFalhouV1(Fio):
    sessao_id: str
    segmento_id: str
    categoria: CategoriaFalha
    detalhe: str = Field(max_length=2000)
    retentavel: bool


IncertezaNivel = Literal["normal", "revisar_com_atencao"]


class AtaRascunhoProntaV1(Fio):
    """O rascunho existe no satélite. O texto NÃO viaja: o core o lê sob demanda, como a transcrição."""

    solicitacao_id: str
    sessao_id: str
    rascunho_id: str
    modelo_llm_id: str
    prompt_versao: str
    incerteza: IncertezaNivel
    n_citacoes: int = Field(ge=0)
    n_citacoes_conferidas: int = Field(ge=0)
    n_paragrafos_sem_fonte: int = Field(ge=0)
    n_pontos_a_confirmar: int = Field(ge=0)


class AtaFalhouV1(Fio):
    solicitacao_id: str
    sessao_id: str
    categoria: CategoriaFalha
    detalhe: str = Field(max_length=2000)
    retentavel: bool


class ResumoCidadaoProntoV1(Fio):
    """O rascunho do resumo existe no satélite (A.8). O texto NÃO viaja: o core o lê sob demanda, como o da ata."""

    proposicao_id: str
    rascunho_id: str
    texto_base_sha256: str
    modelo_llm_id: str
    prompt_versao: str
    incerteza: IncertezaNivel
    n_citacoes: int = Field(ge=0)
    n_citacoes_conferidas: int = Field(ge=0)
    n_paragrafos_sem_fonte: int = Field(ge=0)


class ResumoFalhouV1(Fio):
    proposicao_id: str
    categoria: CategoriaFalha
    detalhe: str = Field(max_length=2000)
    retentavel: bool


class EventoParaCore(Fio):
    tipo: Literal[
        "TranscricaoConcluida",
        "TranscricaoFalhou",
        "AtaRascunhoPronta",
        "AtaFalhou",
        "ResumoCidadaoPronto",
        "ResumoFalhou",
    ]
    versao: int = 1
    chave: str
    ente_id: str
    correlation_id: str | None = None
    ocorrido_em: datetime
    payload: dict[str, Any]


class ReciboCore(Fio):
    chave: str
    aplicado: bool
