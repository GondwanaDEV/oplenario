"""O laço do agente (B.3): planejar → chamar ferramenta → … → responder, no máximo `MAX_PASSOS` ferramentas.

Os dois passos de modelo passam pelo núcleo (ADR-0006) — o filtro de governança, o registro e o custo valem igual:

- **planejar** (`agente.planejar`): o modelo vê a pergunta, a lista de ferramentas que o core ofereceu a ESTA pessoa
  e o que as ferramentas já devolveram; responde só um JSON — `{"acao": "ferramenta", "nome": …, "argumentos": {…}}`
  ou `{"acao": "responder"}`. Plano torto, ferramenta fora da lista ou passos esgotados encerram o laço (o agente
  nunca inventa ferramenta, e o core recusaria de todo modo).
- **responder** (`agente.responder`): cada resultado de ferramenta é uma FONTE citável; a resposta cita por parágrafo e
  a Camada de Confiança confere cada citação contra o que a ferramenta devolveu NESTA execução.

Resultado de ferramenta que o core não marcou como público nunca vira peça (fail-closed, B1); resultado de conteúdo de
terceiro entra delimitado e contamina a execução (Eixo 4.5). A ferramenta roda com a permissão da pessoa, no core —
o agente não tem permissão própria.
"""

from __future__ import annotations

import json
import re
from typing import Any, Protocol

from pydantic import BaseModel

from oplenario_ia.agente.mcp import Ferramenta, ResultadoFerramenta
from oplenario_ia.confianca.artefato import Artefato
from oplenario_ia.confianca.indisponivel import Indisponivel
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Fonte, Peca, Proveniencia, Sigilo
from oplenario_ia.nucleo import Nucleo

OPERACAO_PLANEJAR = "agente.planejar"
OPERACAO_RESPONDER = "agente.responder"
PROMPT_VERSAO = "agente-v1"
MAX_PASSOS = 4
TETO_FONTE = 6_000

INSTRUCOES_PLANEJAR = (
    "Você é o assistente da Câmara Municipal e decide o PRÓXIMO passo para responder à pergunta da pessoa. Você só "
    "sabe o que as ferramentas devolverem: nunca responda de memória. As ferramentas disponíveis, com o formato de "
    "entrada de cada uma, estão abaixo; use só estas.\n"
    "Responda APENAS um objeto JSON, sem nenhum texto em volta, em uma de duas formas:\n"
    '{"acao": "ferramenta", "nome": "<nome da ferramenta>", "argumentos": {<entrada>}}\n'
    '{"acao": "responder"}\n'
    "Escolha 'responder' quando as fontes recebidas já bastarem, ou quando nenhuma ferramenta servir. Ferramenta de "
    "classe 'ato' NÃO executa nada: cria uma PROPOSTA que a pessoa revisa e confirma na tela da plataforma; depois "
    "de criar a proposta, responda.\n\n"
    "Ferramentas:\n"
)

INSTRUCOES_RESPONDER = (
    "Você é o assistente da Câmara Municipal. Responda à pergunta da pessoa em português claro, em até três parágrafos "
    "curtos, usando SOMENTE o que as fontes dizem. Se as fontes não bastarem, diga o que não foi possível saber, sem "
    "inventar. Nunca diga que fez algo: você consulta e, no máximo, propõe. Toda afirmação sobre prazo, quórum, rito "
    "ou competência cita o dispositivo da norma (artigo, parágrafo, inciso) de onde saiu; sem dispositivo lido, diga "
    "que não encontrou a regra nas normas da Casa. Se uma proposta de ato foi criada, diga o que ela fará e que NADA "
    "foi feito ainda: a pessoa revisa e confirma (ou recusa) na tela Propostas."
)


class Porta(Protocol):
    """O que o laço precisa do MCP (o `ClienteMCP` real, ou um falso nos testes)."""

    def ferramentas(self) -> list[Ferramenta]: ...

    def chamar(self, nome: str, argumentos: dict[str, Any]) -> ResultadoFerramenta: ...


class Passo(BaseModel):
    ferramenta: str
    argumentos: dict[str, Any]
    ok: bool
    enviado_ao_modelo: bool


class RespostaAgente(BaseModel):
    passos: list[Passo]
    artefato: Artefato | None = None
    indisponivel: Indisponivel | None = None
    fontes: dict[str, str] = {}  # fonte-id -> rótulo legível: o que cada citação da resposta aponta


def _linhas(valor: Any, prefixo: str = "") -> list[str]:
    """O resultado estruturado em linhas `caminho: valor` — legível para o modelo e citável literalmente."""
    if isinstance(valor, dict):
        return [linha for k, v in valor.items() for linha in _linhas(v, f"{prefixo}{k}.")]
    if isinstance(valor, list):
        return [linha for i, v in enumerate(valor, start=1) for linha in _linhas(v, f"{prefixo}{i}.")]
    if valor is None or valor == "":
        return []
    return [f"{prefixo.rstrip('.')}: {valor}"]


def texto_da_fonte(r: ResultadoFerramenta) -> str:
    corpo = "\n".join(_linhas(r.estruturado)) if r.estruturado is not None else r.texto
    return corpo[:TETO_FONTE]


def _peca_pergunta(pergunta: str) -> Peca:
    # a pergunta é da própria pessoa a quem o agente serve: não é conteúdo de terceiro
    return Peca(
        texto=f"Pergunta da pessoa: {pergunta}",
        proveniencia=Proveniencia(origem="pessoa.pergunta", sigilo=Sigilo.PUBLICO),
    )


def _data_br(iso: str | None) -> str | None:
    if not iso:
        return None
    a, m, d = iso[:10].split("-")
    return f"{d}/{m}/{a}"


def _vigencia(versao: dict[str, Any]) -> str:
    """'consolidada até 30/06/2026' ou 'conferida em 27/09/2026': até quando o texto lido foi conferido (Eixo 7.5)."""
    if c := _data_br(versao.get("consolidada-ate")):
        return f"consolidada até {c}"
    return f"conferida em {_data_br(versao.get('conferida-em'))}"


def dispositivos_do_resultado(r: ResultadoFerramenta) -> list[tuple[Fonte, str]]:
    """Os dispositivos de norma num resultado de `buscar_dispositivos` ou `ler_dispositivo`, como (fonte, texto). Cada
    dispositivo é uma FONTE própria: a citação da resposta aponta o artigo, não o resultado da ferramenta (Eixo 7.5:
    toda afirmação normativa cita um dispositivo lido nesta execução); a fonte diz até quando o texto foi conferido."""
    e = r.estruturado if isinstance(r.estruturado, dict) else {}
    saida: list[tuple[Fonte, str]] = []
    if r.nome == "ler_dispositivo" and isinstance(e.get("dispositivos"), list):
        titulo, nid, vig = e["norma"]["titulo"], e["norma"]["id"], _vigencia(e["versao"])
        for d in e["dispositivos"]:
            if str(d.get("texto", "")).strip():
                fonte = Fonte(id=f"norma:{nid}#{d['endereco']}", rotulo=f"{titulo}, {d['rotulo']}", versao=vig)
                saida.append((fonte, d["texto"]))
    elif r.nome == "buscar_dispositivos" and isinstance(e.get("resultados"), list):
        for d in e["resultados"]:
            if str(d.get("texto", "")).strip():
                fonte = Fonte(
                    id=f"norma:{d['norma']['id']}#{d['endereco']}", rotulo=d["citacao"], versao=_vigencia(d["versao"])
                )
                saida.append((fonte, d["texto"]))
    return saida


def pecas_do_resultado(r: ResultadoFerramenta, n: int) -> list[Peca]:
    prov = Proveniencia(origem=f"core.catalogo.{r.nome}", sigilo=Sigilo.PUBLICO, terceiro=r.origem != "interno")
    ds = dispositivos_do_resultado(r)
    if ds:
        return [Peca(texto=texto, proveniencia=prov, fonte=fonte) for fonte, texto in ds]
    args = ", ".join(f"{k}={v}" for k, v in sorted(r.argumentos.items()))
    fonte = Fonte(id=f"ferramenta:{r.nome}#{n}", rotulo=f"{r.nome}({args})")
    return [Peca(texto=texto_da_fonte(r), proveniencia=prov, fonte=fonte)]


def acrescentar(fontes: list[Peca], novas: list[Peca]) -> None:
    """O mesmo dispositivo lido duas vezes (achado na busca, depois lido inteiro) é UMA fonte."""
    vistas = {p.fonte.id for p in fontes if p.fonte}
    fontes.extend(p for p in novas if not (p.fonte and p.fonte.id in vistas))


def _rotulo(f: Fonte) -> str:
    return f"{f.rotulo} ({f.versao})" if f.versao else f.rotulo


def _peca_aviso(r: ResultadoFerramenta) -> Peca:
    # o que a ferramenta respondeu quando NÃO trouxe dado (negado, entrada inválida, nada encontrado): texto do core,
    # sem conteúdo da Casa — orienta o próximo passo e evita repetir a mesma chamada
    args = ", ".join(f"{k}={v}" for k, v in sorted(r.argumentos.items()))
    return Peca(
        texto=f"A ferramenta {r.nome}({args}) não trouxe resultado: {r.texto[:300]}",
        proveniencia=Proveniencia(origem="core.catalogo.aviso", sigilo=Sigilo.PUBLICO),
    )


def _catalogo(ferramentas: list[Ferramenta]) -> str:
    return "\n".join(
        json.dumps({"nome": f.nome, "descricao": f.descricao, "entrada": f.entrada}, ensure_ascii=False)
        for f in ferramentas
    )


_JSON = re.compile(r"\{.*\}", re.DOTALL)


def ler_plano(texto: str, nomes: set[str]) -> tuple[str, dict[str, Any]] | None:
    """O plano do modelo, ou None (responder). Plano torto ou ferramenta fora da lista = responder."""
    m = _JSON.search(texto)
    if not m:
        return None
    try:
        plano = json.loads(m.group(0))
    except ValueError:
        return None
    if not isinstance(plano, dict) or plano.get("acao") != "ferramenta" or plano.get("nome") not in nomes:
        return None
    argumentos = plano.get("argumentos")
    return str(plano["nome"]), argumentos if isinstance(argumentos, dict) else {}


def executar(nucleo: Nucleo, mcp: Porta, pergunta: str, ente_id: str, correlation_id: str) -> RespostaAgente:
    ferramentas = mcp.ferramentas()
    nomes = {f.nome for f in ferramentas}
    passos: list[Passo] = []
    fontes: list[Peca] = []

    while len(passos) < MAX_PASSOS and ferramentas:
        plano = nucleo.executar(
            PedidoGovernado(
                ente_id=ente_id,
                correlation_id=correlation_id,
                operacao=OPERACAO_PLANEJAR,
                instrucoes=INSTRUCOES_PLANEJAR + _catalogo(ferramentas),
                pecas=[_peca_pergunta(pergunta), *fontes],
                max_tokens=1_000,
            )
        )
        if isinstance(plano, Indisponivel):
            return RespostaAgente(passos=passos, indisponivel=plano)
        escolha = ler_plano(plano.texto, nomes)
        if escolha is None:
            break
        nome, argumentos = escolha
        r = mcp.chamar(nome, argumentos)
        vai = r.ok and r.publico
        passos.append(Passo(ferramenta=nome, argumentos=argumentos, ok=r.ok, enviado_ao_modelo=vai))
        if vai:
            acrescentar(fontes, pecas_do_resultado(r, len(passos)))
        elif not r.ok:
            fontes.append(_peca_aviso(r))

    resposta = nucleo.executar(
        PedidoGovernado(
            ente_id=ente_id,
            correlation_id=correlation_id,
            operacao=OPERACAO_RESPONDER,
            instrucoes=INSTRUCOES_RESPONDER,
            pecas=[_peca_pergunta(pergunta), *fontes],
            max_tokens=2_000,
        ),
        politica="por_paragrafo",
    )
    if isinstance(resposta, Indisponivel):
        return RespostaAgente(passos=passos, indisponivel=resposta)
    rotulos = {p.fonte.id: _rotulo(p.fonte) for p in fontes if p.fonte}
    return RespostaAgente(passos=passos, artefato=resposta, fontes=rotulos)
