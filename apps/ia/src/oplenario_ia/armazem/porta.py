"""A porta de armazenamento e os seus tipos."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Protocol

from oplenario_ia.confianca.cota import Orcamento
from oplenario_ia.transcricao.modelo import Trecho

EstadoTrabalho = str  # "pendente" | "em_curso" | "concluido" | "falhou" | "descartado"


@dataclass(frozen=True)
class NovoTrabalho:
    tipo: str  # "transcrever" | "redigir_ata" | "notificar"
    chave: str  # idempotência: o mesmo evento nunca vira dois trabalhos
    ente_id: str
    payload: dict[str, Any]


@dataclass(frozen=True)
class Trabalho:
    id: int
    tipo: str
    chave: str
    ente_id: str
    payload: dict[str, Any]
    tentativas: int


@dataclass(frozen=True)
class NovaTranscricao:
    ente_id: str
    sessao_id: str
    segmento_id: str
    idioma: str
    duracao_s: float
    modelo_asr: str
    modelo_diarizacao: str | None
    cobertura: float
    trechos: list[Trecho]


@dataclass(frozen=True)
class TranscricaoGuardada:
    id: str
    versao: int
    ente_id: str
    sessao_id: str
    segmento_id: str
    idioma: str
    duracao_s: float
    modelo_asr: str
    modelo_diarizacao: str | None
    cobertura: float
    trechos: list[Trecho] = field(default_factory=list)
    criado_em: datetime | None = None


@dataclass(frozen=True)
class NovoRascunho:
    """O rascunho de ata que o núcleo produziu (Faixa A / A.6b). Citações e incerteza como dados simples: o
    armazenamento não depende da Camada de Confiança."""

    ente_id: str
    sessao_id: str
    solicitacao_id: str
    execucao_id: str
    texto: str
    citacoes: list[dict[str, Any]]
    paragrafos_sem_fonte: list[int]
    incerteza: dict[str, Any]
    vendor: str
    modelo: str
    prompt_versao: str
    transcricoes: list[str]


@dataclass(frozen=True)
class RascunhoGuardado:
    id: str
    ente_id: str
    sessao_id: str
    solicitacao_id: str
    execucao_id: str
    texto: str
    citacoes: list[dict[str, Any]]
    paragrafos_sem_fonte: list[int]
    incerteza: dict[str, Any]
    vendor: str
    modelo: str
    prompt_versao: str
    transcricoes: list[str]
    criado_em: datetime | None = None


@dataclass(frozen=True)
class NovoResumo:
    """O rascunho de resumo cidadão que o núcleo produziu (A.8), da versão `texto_base_sha256` da proposição."""

    ente_id: str
    proposicao_id: str
    texto_base_sha256: str
    execucao_id: str
    texto: str
    citacoes: list[dict[str, Any]]
    paragrafos_sem_fonte: list[int]
    incerteza: dict[str, Any]
    vendor: str
    modelo: str
    prompt_versao: str


@dataclass(frozen=True)
class ResumoGuardado:
    id: str
    ente_id: str
    proposicao_id: str
    texto_base_sha256: str
    execucao_id: str
    texto: str
    citacoes: list[dict[str, Any]]
    paragrafos_sem_fonte: list[int]
    incerteza: dict[str, Any]
    vendor: str
    modelo: str
    prompt_versao: str
    criado_em: datetime | None = None


@dataclass(frozen=True)
class RevisaoAta:
    """A revisão humana de um rascunho nosso, medida quando a ata foi publicada no core (A.6c). Só números e hashes."""

    ente_id: str
    rascunho_id: str
    versao_ata: int
    desfecho: str  # "aprovado" | "editado"
    proporcao_alterada: float
    conteudo_sha256: str
    publicada_por: str


@dataclass(frozen=True)
class TrechoIndice:
    """Um trecho do índice de busca (A.4): a unidade que a busca devolve."""

    parte: int
    texto: str
    meta: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Resultado:
    tipo: str  # "proposicao" | "transcricao"
    ref_id: str  # proposição: o id dela; transcrição: o SEGMENTO (reprocessar substitui, não duplica)
    parte: int
    texto: str
    meta: dict[str, Any]
    score: float


class Armazem(Protocol):
    def cursor(self) -> int: ...

    def registrar_feed(self, novos: list[NovoTrabalho], proximo: int) -> int:
        """Enfileira (idempotente) e avança o cursor, atomicamente. Devolve quantos trabalhos eram novos."""
        ...

    def proximo(self, agora: datetime) -> Trabalho | None:
        """Reserva o próximo trabalho pendente e vencido (em_curso). None = nada a fazer."""
        ...

    def concluir(
        self, trabalho_id: int, seguintes: list[NovoTrabalho] | None = None, *, estado: str = "concluido"
    ) -> None: ...

    def adiar(self, trabalho_id: int, erro: str, quando: datetime) -> None: ...

    def desistir(self, trabalho_id: int, erro: str, seguintes: list[NovoTrabalho] | None = None) -> None: ...

    def pausar(self, trabalho_id: int, motivo: str, ate: datetime) -> None:
        """Devolve o trabalho à fila para depois de `ate` SEM contar tentativa: não é falha (a cota da Casa, B.9)."""
        ...

    def definir_orcamento(self, ente_id: str, orcamento: Orcamento, definido_em: datetime, chave: str) -> None:
        """O orçamento de IA da Casa (B.9), vindo do core; o mais recente substitui o anterior."""
        ...

    def orcamento(self, ente_id: str) -> Orcamento | None: ...

    def concluir_transcricao(
        self, trabalho_id: int, nova: NovaTranscricao, notificar: Callable[[TranscricaoGuardada], NovoTrabalho]
    ) -> TranscricaoGuardada:
        """Guarda a transcrição (versão = próxima do segmento), conclui o trabalho e enfileira a notificação ao core,
        tudo de uma vez: reiniciar no meio não transcreve de novo nem perde o aviso."""
        ...

    def transcricao(self, transcricao_id: str) -> TranscricaoGuardada | None: ...

    def transcricoes_da_sessao(self, ente_id: str, sessao_id: str) -> list[TranscricaoGuardada]:
        """A versão mais recente de cada gravação da sessão (o insumo da ata), da mais antiga para a mais nova."""
        ...

    def concluir_rascunho(
        self, trabalho_id: int, novo: NovoRascunho, notificar: Callable[[RascunhoGuardado], NovoTrabalho]
    ) -> RascunhoGuardado:
        """Guarda o rascunho, conclui o trabalho e enfileira o aviso ao core, de uma vez (como a transcrição)."""
        ...

    def rascunho(self, rascunho_id: str) -> RascunhoGuardado | None: ...

    def concluir_resumo(
        self, trabalho_id: int, novo: NovoResumo, notificar: Callable[[ResumoGuardado], NovoTrabalho]
    ) -> ResumoGuardado:
        """Guarda o rascunho do resumo, conclui o trabalho e enfileira o aviso ao core, de uma vez."""
        ...

    def resumo(self, resumo_id: str) -> ResumoGuardado | None: ...

    def ultimo_resumo(self, ente_id: str, proposicao_id: str) -> ResumoGuardado | None:
        """O rascunho mais recente da proposição — para não redigir de novo a mesma versão do texto."""
        ...

    def enfileirar(self, novos: list[NovoTrabalho]) -> int:
        """Enfileira trabalhos (idempotente pela chave), sem mexer no cursor do feed. Devolve quantos eram novos."""
        ...

    def ultimas_transcricoes(self) -> list[TranscricaoGuardada]:
        """A versão mais recente de cada gravação, de todas as Casas (a reindexação)."""
        ...

    def indexar(
        self, ente_id: str, tipo: str, ref_id: str, trechos: list[TrechoIndice], vetores: list[list[float]], modelo: str
    ) -> None:
        """Substitui TODOS os trechos de (tipo, ref_id) por estes — reindexar não duplica."""
        ...

    def buscar(
        self,
        ente_id: str,
        consulta: str,
        vetor: list[float],
        modelo: str,
        tipos: list[str],
        limite: int,
        distancia_maxima: float,
    ) -> list[Resultado]:
        """Busca híbrida SÓ na Casa: termo exato (full-text português) + sentido (cosseno, só vetores do mesmo
        modelo e abaixo da distância máxima), fundidos por RRF."""
        ...

    def registrar_revisao(self, revisao: RevisaoAta) -> bool:
        """Guarda a revisão uma vez por (rascunho, versão da ata). True = nova; False = já estava (reentrega)."""
        ...

    def trabalhos(self) -> list[dict[str, Any]]:
        """Visão de operação (estado, tentativas, último erro) — sem conteúdo."""
        ...
