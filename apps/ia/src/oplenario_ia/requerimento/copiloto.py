"""O copiloto do requerimento (Faixa B / B.7, docs/26): o vereador descreve em palavras o que quer pedir; a IA escolhe
o modelo da Casa, preenche a ementa e os campos e redige a justificativa citando a norma da Casa que a sustenta. É
RASCUNHO: volta ao formulário, o vereador revisa e assina pelo fluxo de sempre — quem assina é o autor (Eixo 4.6).

Dois passos pelo núcleo (filtro de governança, registro e custo valem igual):

- **preencher** (`requerimento.preencher`): o modelo vê a descrição e os modelos que a Casa oferece (com os campos de
  cada um) e devolve só um JSON. Modelo fora da lista, campo que o modelo não pede e texto grande demais caem aqui —
  o core confere tudo de novo.
- **justificar** (`requerimento.justificar`): só se o modelo tem um campo de justificativa. As fontes são os
  dispositivos da Casa que o índice acha pelo sentido (só a versão vigente está lá, B.4b); cada afirmação sobre a
  base legal cita o dispositivo, e a Camada de Confiança confere a citação contra o texto lido.
"""

from __future__ import annotations

import json
import re
from collections.abc import Callable
from typing import Any

from pydantic import BaseModel, Field

from oplenario_ia.armazem.porta import Resultado
from oplenario_ia.ata.redacao import texto_limpo
from oplenario_ia.confianca.artefato import Artefato
from oplenario_ia.confianca.indisponivel import Indisponivel
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Fonte, Peca, Proveniencia, Sigilo
from oplenario_ia.nucleo import Nucleo

OPERACAO_PREENCHER = "requerimento.preencher"
OPERACAO_JUSTIFICAR = "requerimento.justificar"
PROMPT_VERSAO = "requerimento-v1"
CAMPOS_DE_JUSTIFICATIVA = ("justificativa", "fundamentacao", "motivacao")
TETO_CAMPO = 2_000
TETO_EMENTA = 300
MAX_FONTES = 4

INSTRUCOES_PREENCHER = (
    "Você ajuda um vereador a preencher um requerimento da Câmara Municipal. Pelo pedido dele, escolha UM dos modelos "
    "da Casa listados abaixo e preencha a ementa (uma linha que resume o pedido) e os campos que o modelo pede, com o "
    "que o pedido diz — não invente destinatário, datas nem números. Deixe de fora o campo de justificativa: ele é "
    "redigido em outro passo. Responda APENAS um objeto JSON, sem texto em volta:\n"
    '{"modelo_id": "<id do modelo>", "ementa": "<uma linha>", "campos": {"<nome do campo>": "<valor>"}}\n'
    "Se nenhum modelo servir ao pedido, responda {}."
)

INSTRUCOES_JUSTIFICAR = (
    "Você redige a JUSTIFICATIVA de um requerimento de um vereador da Câmara Municipal: um a dois parágrafos "
    "curtos, em linguagem formal e simples, dizendo por que o pedido é necessário e em que se apoia. Quando afirmar "
    "a base legal (a competência da Câmara, o direito de pedir informação), cite o dispositivo das normas da Casa "
    "recebidas; sem dispositivo que sustente, não afirme base legal. Não invente fatos, números nem datas que o "
    "pedido não traga. Devolva só o texto da justificativa."
)


class ModeloRequerimento(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    nome: str = Field(min_length=1, max_length=200)
    campos: list[str] = Field(default_factory=list, max_length=50)


class PedidoCopiloto(BaseModel):
    descricao: str = Field(min_length=2, max_length=1_000)
    modelos: list[ModeloRequerimento] = Field(min_length=1, max_length=30)
    correlation_id: str = Field(min_length=1, max_length=64)


class Preenchimento(BaseModel):
    modelo_id: str
    ementa: str
    campos: dict[str, str]


class RascunhoRequerimento(BaseModel):
    preenchimento: Preenchimento | None = None
    campo_justificativa: str | None = None
    justificativa: Artefato | None = None
    fontes: dict[str, str] = {}  # fonte-id -> rótulo legível ("Lei Orgânica do Município, art. 25 (…)")
    indisponivel: Indisponivel | None = None


_JSON = re.compile(r"\{.*\}", re.DOTALL)


def _peca_da_pessoa(texto: str) -> Peca:
    # o pedido é do próprio vereador a quem o copiloto serve: não é conteúdo de terceiro
    return Peca(texto=texto, proveniencia=Proveniencia(origem="pessoa.pedido", sigilo=Sigilo.PUBLICO))


def _peca_modelos(modelos: list[ModeloRequerimento]) -> Peca:
    linhas = [json.dumps({"id": m.id, "nome": m.nome, "campos": m.campos}, ensure_ascii=False) for m in modelos]
    return Peca(
        texto="Modelos de requerimento da Casa:\n" + "\n".join(linhas),
        proveniencia=Proveniencia(origem="core.modelos_requerimento", sigilo=Sigilo.PUBLICO),
    )


def ler_preenchimento(texto: str, modelos: list[ModeloRequerimento]) -> Preenchimento | None:
    """O JSON do modelo, conferido contra os modelos da Casa: id da lista, só os campos que o modelo pede (menos o de
    justificativa), textos aparados e com teto. Qualquer coisa torta = None (o vereador preenche à mão)."""
    m = _JSON.search(texto)
    if not m:
        return None
    try:
        bruto = json.loads(m.group(0))
    except ValueError:
        return None
    if not isinstance(bruto, dict):
        return None
    modelo = next((x for x in modelos if x.id == bruto.get("modelo_id")), None)
    ementa = str(bruto.get("ementa") or "").strip()
    if modelo is None or not ementa:
        return None
    permitidos = [c for c in modelo.campos if c not in CAMPOS_DE_JUSTIFICATIVA]
    entrada = bruto.get("campos")
    campos_brutos: dict[str, Any] = entrada if isinstance(entrada, dict) else {}
    campos = {
        c: str(campos_brutos[c]).strip()[:TETO_CAMPO]
        for c in permitidos
        if isinstance(campos_brutos.get(c), str | int | float) and str(campos_brutos[c]).strip()
    }
    return Preenchimento(modelo_id=modelo.id, ementa=ementa[:TETO_EMENTA], campos=campos)


def _vigencia(meta: dict[str, Any]) -> str | None:
    c = meta.get("consolidada-ate")
    if not c:
        return None
    a, mes, d = str(c)[:10].split("-")
    return f"consolidada até {d}/{mes}/{a}"


def fontes_de_norma(resultados: list[Resultado]) -> list[Peca]:
    """Os dispositivos achados pelo índice, cada um uma FONTE citável (o mesmo endereço da B.5)."""
    pecas: list[Peca] = []
    vistos: set[str] = set()
    for r in resultados:
        meta = r.meta or {}
        if r.tipo != "dispositivo" or not meta.get("norma-id") or not meta.get("endereco"):
            continue
        fid = f"norma:{meta['norma-id']}#{meta['endereco']}"
        if fid in vistos:
            continue
        vistos.add(fid)
        rotulo = f"{meta.get('titulo', 'Norma da Casa')}, {meta.get('rotulo', meta['endereco'])}"
        # o trecho indexado leva "Título, rótulo: " na frente (B.4b); a fonte é o texto do dispositivo
        texto = r.texto.split(": ", 1)[1] if r.texto.startswith(f"{rotulo}: ") else r.texto
        pecas.append(
            Peca(
                texto=texto,
                proveniencia=Proveniencia(origem="indice.dispositivo", sigilo=Sigilo.PUBLICO),
                fonte=Fonte(id=fid, rotulo=rotulo, versao=_vigencia(meta)),
            )
        )
        if len(pecas) >= MAX_FONTES:
            break
    return pecas


def _buscar_base(buscar: Callable[[str], list[Resultado]], consultas: list[str]) -> list[Resultado]:
    """A base legal de um requerimento se acha primeiro pelo TIPO dele ("Requerimento de informação" casa com o artigo
    que fala em requerimento e informações), depois pela ementa e pelo pedido. Cada consulta que falha (índice fora) só
    não contribui: a justificativa sai sem base citada, nunca quebra."""
    achados: list[Resultado] = []
    for consulta in consultas:
        try:
            achados.extend(buscar(consulta))
        except Exception:
            continue
    return achados


def _campo_de_justificativa(modelo: ModeloRequerimento) -> str | None:
    return next((c for c in modelo.campos if c in CAMPOS_DE_JUSTIFICATIVA), None)


def rascunhar(
    nucleo: Nucleo,
    pedido: PedidoCopiloto,
    ente_id: str,
    buscar: Callable[[str], list[Resultado]] | None = None,
) -> RascunhoRequerimento:
    plano = nucleo.executar(
        PedidoGovernado(
            ente_id=ente_id,
            correlation_id=pedido.correlation_id,
            operacao=OPERACAO_PREENCHER,
            instrucoes=INSTRUCOES_PREENCHER,
            pecas=[_peca_da_pessoa(f"Pedido do vereador: {pedido.descricao}"), _peca_modelos(pedido.modelos)],
            max_tokens=1_200,
        )
    )
    if isinstance(plano, Indisponivel):
        return RascunhoRequerimento(indisponivel=plano)
    preenchido = ler_preenchimento(plano.texto, pedido.modelos)
    if preenchido is None:
        return RascunhoRequerimento()
    modelo = next(m for m in pedido.modelos if m.id == preenchido.modelo_id)
    campo = _campo_de_justificativa(modelo)
    if campo is None:
        return RascunhoRequerimento(preenchimento=preenchido)

    consultas = [modelo.nome, preenchido.ementa, pedido.descricao]
    fontes = fontes_de_norma(_buscar_base(buscar, consultas)) if buscar else []
    resumo = "; ".join(f"{k}: {v}" for k, v in preenchido.campos.items())
    artefato = nucleo.executar(
        PedidoGovernado(
            ente_id=ente_id,
            correlation_id=pedido.correlation_id,
            operacao=OPERACAO_JUSTIFICAR,
            instrucoes=INSTRUCOES_JUSTIFICAR,
            pecas=[
                _peca_da_pessoa(
                    f"Pedido do vereador: {pedido.descricao}\nRequerimento: {modelo.nome}. Ementa: {preenchido.ementa}."
                    + (f" {resumo}." if resumo else "")
                ),
                *fontes,
            ],
            max_tokens=1_500,
        ),
        politica="por_paragrafo",
    )
    if isinstance(artefato, Indisponivel):
        return RascunhoRequerimento(preenchimento=preenchido)
    preenchido.campos[campo] = texto_limpo(artefato.texto)[:TETO_CAMPO]
    rotulos = {
        p.fonte.id: (f"{p.fonte.rotulo} ({p.fonte.versao})" if p.fonte.versao else p.fonte.rotulo)
        for p in fontes
        if p.fonte
    }
    return RascunhoRequerimento(
        preenchimento=preenchido, campo_justificativa=campo, justificativa=artefato, fontes=rotulos
    )
