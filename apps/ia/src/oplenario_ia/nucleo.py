"""O pipeline único de toda capacidade de IA (ADR-0006): filtro → porta → registro/custo → artefato com incerteza,
ou o piso R-IA-1.

Capacidade nova (ata, resumo, busca, copiloto, conferência) compõe o núcleo — escolhe instruções, peças e política de
citação — e nunca chama a porta direto. Tudo o que a Camada de Confiança exige sai daqui de graça, e não dá para pular.
"""

from __future__ import annotations

import hashlib
import uuid
from collections.abc import Callable
from datetime import UTC, datetime

from oplenario_ia.avaliacao.custo import TabelaPrecos, calcular, tabela_padrao
from oplenario_ia.confianca.artefato import Artefato, revisar
from oplenario_ia.confianca.citacao import FonteLida, PoliticaCitacao, conferir, paragrafos_sem_fonte
from oplenario_ia.confianca.incerteza import avaliar
from oplenario_ia.confianca.indisponivel import POR_CATEGORIA, Indisponivel, MotivoIndisponivel, indisponivel
from oplenario_ia.confianca.registro import (
    CategoriaReporte,
    Desfecho,
    RegistroConfianca,
    RegistroExecucao,
    ReporteErro,
    RevisaoHumana,
)
from oplenario_ia.governanca.filtro import Chamada, PedidoGovernado, chamar_com_governanca
from oplenario_ia.governanca.redator import redigir
from oplenario_ia.inferencia.porta import PortaInferencia

INSTRUCAO_CITACAO = (
    "Cite cada afirmação com a fonte de onde ela vem, logo depois dela, no formato "
    "[[id-da-fonte | trecho copiado literalmente da fonte]]. Use apenas os ids das tags <fonte> recebidas. "
    "Não afirme nada que as fontes não digam; se precisar afirmar, deixe sem marca — sairá como 'sem fonte'."
)


def _sha256(texto: str) -> str:
    return hashlib.sha256(texto.encode("utf-8")).hexdigest()


class Nucleo:
    def __init__(
        self,
        porta: PortaInferencia,
        registro: RegistroConfianca,
        *,
        agora: Callable[[], datetime] = lambda: datetime.now(UTC),
        novo_id: Callable[[], str] = lambda: str(uuid.uuid4()),
        precos: TabelaPrecos | None = None,
    ) -> None:
        self._porta = porta
        self._registro = registro
        self._agora = agora
        self._novo_id = novo_id
        self._precos = precos or tabela_padrao()

    def executar(self, pedido: PedidoGovernado, politica: PoliticaCitacao = "nenhuma") -> Artefato | Indisponivel:
        execucao_id = self._novo_id()
        if politica != "nenhuma":
            pedido = pedido.model_copy(update={"instrucoes": f"{pedido.instrucoes}\n\n{INSTRUCAO_CITACAO}"})
        chamada = chamar_com_governanca(pedido, self._porta, self._agora)
        aud = chamada.auditoria
        base = RegistroExecucao(
            execucao_id=execucao_id,
            instante=aud.instante,
            ente_id=pedido.ente_id,
            correlation_id=pedido.correlation_id,
            operacao=pedido.operacao,
            decisao_governanca=aud.decisao,
            motivos_bloqueio=aud.motivos_bloqueio,
            redacoes=aud.redacoes,
            terceiros=aud.terceiros,
            hash_entrada=aud.hash_entrada,
            vendor=aud.vendor,
            resultado="indisponivel",
        )

        falha = self._falha(execucao_id, chamada)
        if falha is not None:
            self._registro.anexar(
                base.model_copy(update={"motivo_indisponivel": falha.motivo, "categoria_erro": falha.categoria})
            )
            return falha

        r = chamada.resposta
        assert r is not None  # _falha cobre degradação e erro
        registro = base.model_copy(
            update={
                "vendor": r.vendor,
                "modelo": r.modelo,
                "uso": r.uso,
                "custo": calcular(r.uso, r.vendor, r.modelo, self._precos),
                "latencia_ms": r.latencia_ms,
                "parada": r.parada,
            }
        )
        if r.parada == "recusa":
            recusa = indisponivel(execucao_id, "recusa")
            self._registro.anexar(registro.model_copy(update={"motivo_indisponivel": "recusa"}))
            return recusa

        lidas = [FonteLida(fonte=p.fonte, texto=redigir(p.texto).texto) for p in pedido.pecas if p.fonte is not None]
        citacoes = conferir(r.texto, lidas)
        sem_fonte = paragrafos_sem_fonte(r.texto, citacoes) if politica == "por_paragrafo" else []
        incerteza = avaliar(r.parada, citacoes, sem_fonte, aud.terceiros)
        artefato = Artefato(
            execucao_id=execucao_id,
            ente_id=pedido.ente_id,
            operacao=pedido.operacao,
            texto=r.texto,
            citacoes=citacoes,
            paragrafos_sem_fonte=sem_fonte,
            incerteza=incerteza,
            vendor=r.vendor,
            modelo=r.modelo,
            contaminado=aud.terceiros > 0,
        )
        self._registro.anexar(
            registro.model_copy(
                update={
                    "resultado": "artefato",
                    "n_citacoes": len(citacoes),
                    "n_citacoes_conferidas": sum(c.status == "conferida" for c in citacoes),
                    "n_paragrafos_sem_fonte": len(sem_fonte),
                    "incerteza": incerteza.nivel,
                    "hash_saida": _sha256(r.texto),
                }
            )
        )
        return artefato

    def _falha(self, execucao_id: str, chamada: Chamada) -> Indisponivel | None:
        motivo: MotivoIndisponivel
        if chamada.auditoria.decisao == "bloqueado":
            motivo = "sigilo"
        elif chamada.auditoria.decisao == "vazio":
            motivo = "nada_a_enviar"
        elif chamada.erro is not None:
            e = chamada.erro
            return indisponivel(execucao_id, POR_CATEGORIA[e.categoria], retentavel=e.retentavel, categoria=e.categoria)
        else:
            return None
        return indisponivel(execucao_id, motivo)

    def revisar(self, artefato: Artefato, desfecho: Desfecho, revisor: str, texto_final: str | None = None) -> Artefato:
        revisado, proporcao = revisar(artefato, desfecho, revisor, texto_final)
        self._registro.anexar(
            RevisaoHumana(
                execucao_id=artefato.execucao_id,
                instante=self._agora(),
                ente_id=artefato.ente_id,
                operacao=artefato.operacao,
                revisor=revisor,
                desfecho=desfecho,
                proporcao_alterada=proporcao,
            )
        )
        return revisado

    def registrar_revisao(
        self, execucao_id: str, ente_id: str, operacao: str, revisor: str, desfecho: Desfecho, proporcao: float
    ) -> None:
        """A revisão que aconteceu FORA do satélite (a ata publicada no core, A.6c): o mesmo evento do registro, para a
        taxa de aceitação por Casa e operação (§22.11.8) contar a ata como conta o resto."""
        self._registro.anexar(
            RevisaoHumana(
                execucao_id=execucao_id,
                instante=self._agora(),
                ente_id=ente_id,
                operacao=operacao,
                revisor=revisor,
                desfecho=desfecho,
                proporcao_alterada=proporcao,
            )
        )

    def reportar_erro(self, artefato: Artefato, quem: str, categoria: CategoriaReporte) -> None:
        self._registro.anexar(
            ReporteErro(
                execucao_id=artefato.execucao_id,
                instante=self._agora(),
                ente_id=artefato.ente_id,
                operacao=artefato.operacao,
                quem=quem,
                categoria=categoria,
            )
        )
