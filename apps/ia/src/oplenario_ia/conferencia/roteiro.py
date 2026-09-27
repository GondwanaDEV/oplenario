"""A conferência institucional (Faixa B / B.8, docs/25 Eixo 7.7, ADR-0013): a cada proposição protocolada numa Casa que
ligou o agente, o agente institucional — sem pessoa por trás — lê a matéria e os dispositivos da LOM e do Regimento que
tratam do assunto, e deixa um RASCUNHO de nota técnica com citações na fila da secretaria. Nunca uma decisão.

É um roteiro fixo, não um laço aberto: o que ler e em que ordem é código (a matéria, a busca pelo tipo e pela ementa, a
leitura de cada dispositivo achado); o modelo só REDIGE, pelo núcleo (filtro, citação conferida, incerteza, registro,
custo). Tudo passa pelo servidor MCP do core com a credencial da execução — o que o agente lê e escreve é o que a
concessão do `admin_ente` permite AGORA (leitura + rascunho, nunca ato).
"""

from __future__ import annotations

from typing import Any

from oplenario_ia.agente.laco import Porta, acrescentar, pecas_do_resultado
from oplenario_ia.agente.mcp import ResultadoFerramenta
from oplenario_ia.confianca.artefato import Artefato
from oplenario_ia.confianca.citacao import Citacao
from oplenario_ia.confianca.indisponivel import Indisponivel
from oplenario_ia.erros import Categoria, ErroIA
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Fonte, Peca, Proveniencia, Sigilo
from oplenario_ia.nucleo import Nucleo

AGENTE = "conferencia-normativa"
OPERACAO = "conferencia.redigir"
PROMPT_VERSAO = "conferencia-v1"
FERRAMENTAS = ("situacao_da_materia", "buscar_dispositivos", "ler_dispositivo", "registrar_nota_tecnica")
MAX_DISPOSITIVOS = 4
TETO_TEXTO = 20_000

INSTRUCOES = (
    "Você é a assessoria técnica da secretaria de uma Câmara Municipal. Confira a proposição recebida contra os "
    "dispositivos das normas da Casa (Lei Orgânica do Município, Regimento Interno) e redija uma NOTA TÉCNICA curta, "
    "em linguagem formal e simples: um parágrafo que identifica a proposição e o que ela pede; depois, um parágrafo "
    "por dispositivo que se aplica, dizendo o que ele exige (competência, iniciativa, forma, quórum, prazo) e o que a "
    "secretaria deve confirmar na proposição. Não decida nada: não diga que a proposição é válida, inválida, "
    "admissível ou inadmissível — aponte o que conferir. Não invente dispositivos, números nem datas; sem dispositivo "
    "que trate do assunto, diga que não o encontrou nas normas da Casa. Devolva só o texto da nota, em parágrafos, sem "
    "título nem marcação."
)

NOMES = {
    "projeto_lei": "Projeto de Lei",
    "projeto_lei_complementar": "Projeto de Lei Complementar",
    "projeto_resolucao": "Projeto de Resolução",
    "projeto_decreto_legislativo": "Projeto de Decreto Legislativo",
    "proposta_emenda_lom": "Proposta de Emenda à Lei Orgânica",
    "requerimento": "Requerimento",
    "indicacao": "Indicação",
    "mocao": "Moção",
}


def identificacao(m: dict[str, Any]) -> str:
    """'Requerimento nº 5/2026'."""
    return f"{NOMES.get(str(m.get('tipo')), 'Proposição')} nº {m.get('sequencial')}/{m.get('ano')}"


def peca_da_materia(proposicao_id: str, r: ResultadoFerramenta) -> Peca:
    """A proposição lida pelo catálogo, como FONTE citável (`materia:<id>`): a nota cita o que ela pede."""
    m = r.estruturado or {}
    linhas = [f"Ementa: {m.get('ementa', '')}"]
    if m.get("autor-texto"):
        linhas.append(f"Autoria: {m['autor-texto']}")
    if m.get("texto"):
        linhas.append(f"Texto:\n{m['texto']}")
    return Peca(
        texto="\n".join(linhas),
        proveniencia=Proveniencia(
            origem="core.catalogo.situacao_da_materia", sigilo=Sigilo.PUBLICO, terceiro=r.origem != "interno"
        ),
        fonte=Fonte(id=f"materia:{proposicao_id}", rotulo=identificacao(m)),
    )


def consultas(m: dict[str, Any]) -> list[str]:
    """O que buscar nas normas: primeiro pela ESPÉCIE (o Regimento trata de 'requerimento', 'indicação'...), depois
    pela ementa (o assunto). Cada consulta com o teto da ferramenta."""
    especie = NOMES.get(str(m.get("tipo")), "")
    if m.get("tipo-requerimento"):
        especie = f"{especie} de {str(m['tipo-requerimento']).replace('_', ' ')}"
    return [c[:300] for c in (especie, str(m.get("ementa") or "")) if len(c.strip()) >= 2]


def _achados(r: ResultadoFerramenta) -> list[tuple[str, str]]:
    """(norma-id, endereço) de cada dispositivo que a busca achou, na ordem."""
    e = r.estruturado if isinstance(r.estruturado, dict) else {}
    saida: list[tuple[str, str]] = []
    for d in e.get("resultados", []) if r.ok else []:
        nid, end = d.get("norma", {}).get("id"), d.get("endereco")
        if nid and end:
            saida.append((str(nid), str(end)))
    return saida


def ler_normas(mcp: Porta, m: dict[str, Any]) -> list[Peca]:
    """Busca e LÊ os dispositivos aplicáveis (no máximo `MAX_DISPOSITIVOS`): cada um lido vira fonte citável."""
    alvos: list[tuple[str, str]] = []
    for c in consultas(m):
        for par in _achados(mcp.chamar("buscar_dispositivos", {"consulta": c, "limite": 5})):
            if par not in alvos:
                alvos.append(par)
    fontes: list[Peca] = []
    for n, (nid, end) in enumerate(alvos[:MAX_DISPOSITIVOS], start=1):
        r = mcp.chamar("ler_dispositivo", {"norma-id": nid, "endereco": end})
        if r.ok and r.publico:
            acrescentar(fontes, pecas_do_resultado(r, n))
    return fontes


def _rotulo(c: Citacao) -> str | None:
    if not c.rotulo:
        return None
    return (f"{c.rotulo} ({c.versao})" if c.versao else c.rotulo)[:500]


def pedido_de_registro(proposicao_id: str, a: Artefato) -> dict[str, Any]:
    """O que vai ao core pela ferramenta `registrar_nota_tecnica` (classe `rascunho`)."""
    return {
        "proposicao-id": proposicao_id,
        "texto": a.texto[:TETO_TEXTO],
        "citacoes": [
            {"fonte-id": c.fonte_id, "trecho": c.trecho, "status": c.status, "rotulo": _rotulo(c)}
            for c in a.citacoes[:100]
        ],
        "paragrafos-sem-fonte": a.paragrafos_sem_fonte[:200],
        "incerteza": a.incerteza.nivel,
        "motivos-incerteza": list(a.incerteza.motivos),
        "modelo": f"{a.vendor}:{a.modelo}"[:200],
    }


def conferir(nucleo: Nucleo, mcp: Porta, ente_id: str, proposicao_id: str, correlation_id: str) -> str | None:
    """Confere uma proposição e registra a nota. Devolve o id da nota no core, ou None quando não há o que conferir
    (a matéria não é pública para o agente). Falha de fornecedor ou do core sobe como `ErroIA` — a fila decide se tenta
    de novo."""
    nomes = {f.nome for f in mcp.ferramentas()}
    if not set(FERRAMENTAS) <= nomes:
        raise ErroIA(Categoria.ENTRADA, "o agente não tem as ferramentas da conferência nesta Casa", retentavel=False)
    materia = mcp.chamar("situacao_da_materia", {"proposicao-id": proposicao_id})
    if not materia.ok or not materia.estruturado:
        raise ErroIA(Categoria.ENTRADA, "a proposição não foi encontrada no core", retentavel=False)
    if not materia.publico:
        return None  # fail-closed (B1): conteúdo que o core não marcou como público nunca vai ao modelo
    fontes = ler_normas(mcp, materia.estruturado)
    r = nucleo.executar(
        PedidoGovernado(
            ente_id=ente_id,
            correlation_id=correlation_id,
            operacao=OPERACAO,
            instrucoes=INSTRUCOES,
            pecas=[peca_da_materia(proposicao_id, materia), *fontes],
            max_tokens=2_500,
        ),
        politica="por_paragrafo",
    )
    if isinstance(r, Indisponivel):
        raise ErroIA(r.categoria or Categoria.ENTRADA, r.mensagem, retentavel=r.retentavel)
    registro = mcp.chamar("registrar_nota_tecnica", pedido_de_registro(proposicao_id, r))
    if not registro.ok:
        raise ErroIA(Categoria.ENTRADA, f"o core recusou a nota: {registro.texto[:300]}", retentavel=False)
    return str((registro.estruturado or {}).get("nota-id") or "") or None
