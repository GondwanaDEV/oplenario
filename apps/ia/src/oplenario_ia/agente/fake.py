"""O agente FAKE — roteiros determinísticos do fornecedor fake para `agente.planejar` e `agente.responder` (dev, CI,
demo). Planeja por padrões da pergunta ("PL 12/2026", "tramitação", "pauta", "art. 45 do Regimento", "quórum")
entre as ferramentas que o core ofereceu, e responde citando linhas LITERAIS do que a ferramenta devolveu (a
conferência roda de verdade) — dispositivo de norma é citado pelo artigo, com a data até quando o texto foi conferido.

Segue o prompt `agente-v2` (fatia 6 da Clara): pergunta sobre a trilha ou sobre as conversas da Casa não consulta nada
e aponta a tela Auditoria; pedido de ato sem proposta criada diz que a Clara não o faz e que a pessoa o faz pela tela;
sem fonte, diz que não encontrou com o acesso da pessoa e onde ver; um parágrafo ou inciso achado na busca leva à
leitura do artigo inteiro.
"""

from __future__ import annotations

import html
import json
import re
from typing import Any

from oplenario_ia.ata.fake import frase
from oplenario_ia.inferencia.modelo import PedidoInferencia

SIGLAS = {
    "PL": "projeto_lei",
    "PLC": "projeto_lei_complementar",
    "PR": "projeto_resolucao",
    "PDL": "projeto_decreto_legislativo",
    "PELOM": "proposta_emenda_lom",
    "REQ": "requerimento",
    "IND": "indicacao",
    "MOC": "mocao",
}
_MATERIA = re.compile(r"\b(PLC|PDL|PELOM|PL|PR|REQ|IND|MOC)\s*(?:n[ºo°.]*\s*)?(\d+)\s*/\s*(\d{4})\b", re.IGNORECASE)
_TRAMITACAO = re.compile(r"tramit|andamento|por onde|passou|pr[óo]ximo passo|falta", re.IGNORECASE)
_PAUTA = re.compile(r"pauta|votad|vota[çc][ãa]o|pr[óo]xima sess[ãa]o", re.IGNORECASE)
_ARTIGO = re.compile(r"\bart(?:igo|\.)?\s*(\d+)", re.IGNORECASE)
_REGIMENTO = re.compile(r"regimento", re.IGNORECASE)
_LOM = re.compile(r"lei org[âa]nica|\bLOM\b", re.IGNORECASE)
_NORMATIVA = re.compile(
    r"regimento|lei org[âa]nica|\bLOM\b|qu[óo]rum|prazo|\bveto\b|maioria|compet[êe]ncia|\brito\b|dispositivo",
    re.IGNORECASE,
)
_REQUERIMENTO = re.compile(
    r"\b(protocol\w*|fa[çc]a|fazer|crie|criar|redija|redigir|prepar\w*|abr\w*)\b.*\brequerimento", re.IGNORECASE
)
_DESTINATARIO = re.compile(
    r"\b(?:à|ao|a|para a|para o)\s+((?:Secretaria|Prefeitura|Prefeito|Companhia|Autarquia|Superintend[êe]ncia|"
    r"Procuradoria|Coordenadoria|Guarda)[^,.;?]*?)(?=\s+sobre\b|[,.;?]|$)",
    re.IGNORECASE,
)
_ASSUNTO = re.compile(r"\bsobre\s+(.+?)[.?!]*$", re.IGNORECASE)
_PARECER_JURIDICO = re.compile(
    r"\b(pe[çc]a|pedir|solicite|solicitar|abra|abrir|encaminhe|encaminhar)\b.*\bparecer\b.*\bjur[íi]dic", re.IGNORECASE
)
_TRILHA = re.compile(
    r"\bquem\s+(?:alterou|mudou|apagou|excluiu|editou|incluiu|retirou|mexeu|acessou)\b|\btrilha\b"
    r"|\bconversas?\b.*\bclara\b|\bperguntas?\b.*\bclara\b",
    re.IGNORECASE,
)
# o pedido de ato vem no imperativo ("Protocole…", "Peça…"); "como pedir vista?" é pergunta, não pedido
_ATO = re.compile(
    r"\b(?:protocole|pe[çc]a|solicite|encaminhe|designe|envie|mande|registre|publique|assine|vote)\b", re.IGNORECASE
)
_NORMA_LIDA = re.compile(r'<fonte id="norma:([^"#]+)#([^"]+)"')
SEM_TRILHA = (
    "Eu não leio a trilha de auditoria nem as conversas das pessoas da Casa com a Clara, então não sei dizer isso. "
    "Quem tem esse acesso, como o controle interno, vê na tela Auditoria."
)
NAO_FAZ = "Não posso fazer isso por você daqui. Você faz isso pela tela da plataforma, se o seu acesso permitir."
SEM_FONTE = (
    "Não encontrei isso nas consultas que o seu acesso permite, e não vou adivinhar. Veja na tela da plataforma em "
    "que isso aparece, ou pergunte à secretaria da Casa."
)
_ID_DA_MATERIA = re.compile(r"^id: (\S+)$", re.MULTILINE)
_ITEM = re.compile(r"^itens\.(\d+)\.(id|nome|campos\.\d+): (.+)$", re.MULTILINE)
_FONTE = re.compile(r'<fonte id="([^"]+)" rotulo="([^"]*)"(?: versao="([^"]*)")?>\n(.*?)\n</fonte>', re.DOTALL)
_PREFERIDAS = ("ementa:", "estado-atual:", "estado:", "itens.1.proposicao.ementa:", "itens.1.texto-descricao:")


def _pergunta(pedido: PedidoInferencia) -> str:
    for c in pedido.conteudo:
        if c.startswith("Pergunta da pessoa:"):
            return c.removeprefix("Pergunta da pessoa:").strip()
    return ""


def _disponiveis(pedido: PedidoInferencia) -> set[str]:
    nomes: set[str] = set()
    for linha in pedido.instrucoes.split("\n"):
        if linha.startswith("{"):
            try:
                nomes.add(json.loads(linha)["nome"])
            except (ValueError, KeyError):
                continue
    return nomes


def _fonte(pedido: PedidoInferencia, ferramenta: str) -> tuple[str, str] | None:
    """(fonte-id, texto) do resultado da `ferramenta` já recebido nesta execução, ou None."""
    for bruto in pedido.conteudo:
        for fid, _rotulo, _versao, texto in _FONTE.findall(bruto):
            if fid.startswith(f"ferramenta:{ferramenta}#"):
                return fid, html.unescape(texto)
    return None


def _modelos(texto: str) -> list[dict[str, Any]]:
    por_item: dict[str, dict[str, Any]] = {}
    for n, chave, valor in _ITEM.findall(texto):
        item = por_item.setdefault(n, {"campos": []})
        if chave.startswith("campos."):
            item["campos"].append(valor.strip())
        else:
            item[chave] = valor.strip()
    return [m for _, m in sorted(por_item.items(), key=lambda kv: int(kv[0])) if "id" in m]


def _planejar_requerimento(pedido: PedidoInferencia, pergunta: str, disponiveis: set[str]) -> str | None:
    """B.6: "protocole um requerimento à Secretaria X sobre Y" — lê os modelos, depois PROPÕE (o ato não executa)."""
    if not (_REQUERIMENTO.search(pergunta) and {"modelos_de_requerimento", "protocolar_requerimento"} <= disponiveis):
        return None
    responder = json.dumps({"acao": "responder"})
    if _fonte(pedido, "protocolar_requerimento") or any(c.startswith("A ferramenta ") for c in pedido.conteudo):
        return responder
    lidos = _fonte(pedido, "modelos_de_requerimento")
    if lidos is None:
        return json.dumps({"acao": "ferramenta", "nome": "modelos_de_requerimento", "argumentos": {}})
    modelos = _modelos(lidos[1])
    if not modelos:
        return responder
    informacao = [m for m in modelos if "informa" in m.get("nome", "").lower()]
    modelo = informacao[0] if informacao and "informa" in pergunta.lower() else modelos[0]
    d = _DESTINATARIO.search(pergunta)
    a = _ASSUNTO.search(pergunta)
    destinatario = d.group(1).strip() if d else ""
    assunto = a.group(1).strip() if a else ""
    campos = {c: (destinatario if c == "destinatario" else assunto) for c in modelo["campos"]}
    ementa = f"Informações sobre {assunto}" if assunto else pergunta[:200]
    argumentos = {"modelo-id": modelo["id"], "ementa": ementa, "campos": {k: v for k, v in campos.items() if v}}
    return json.dumps({"acao": "ferramenta", "nome": "protocolar_requerimento", "argumentos": argumentos})


def _planejar_parecer_juridico(pedido: PedidoInferencia, pergunta: str, disponiveis: set[str]) -> str | None:
    """ADR-0019: "peça o parecer jurídico do PL 12/2026" — lê a matéria (para o id), depois PROPÕE o pedido (o ato não
    executa: a secretaria confirma na tela). Sem a matéria na pergunta, não planeja (consulta avulsa é da pessoa)."""
    materia = _MATERIA.search(pergunta)
    if not (materia and _PARECER_JURIDICO.search(pergunta)):
        return None
    if not {"situacao_da_materia", "pedir_parecer_juridico"} <= disponiveis:
        return None
    if _fonte(pedido, "pedir_parecer_juridico") or any(c.startswith("A ferramenta ") for c in pedido.conteudo):
        return json.dumps({"acao": "responder"})
    lida = _fonte(pedido, "situacao_da_materia")
    if lida is None:
        argumentos = {
            "tipo": SIGLAS[materia.group(1).upper()],
            "sequencial": int(materia.group(2)),
            "ano": int(materia.group(3)),
        }
        return json.dumps({"acao": "ferramenta", "nome": "situacao_da_materia", "argumentos": argumentos})
    achado = _ID_DA_MATERIA.search(lida[1])
    if achado is None:
        return json.dumps({"acao": "responder"})
    return json.dumps(
        {"acao": "ferramenta", "nome": "pedir_parecer_juridico", "argumentos": {"proposicao-id": achado.group(1)}}
    )


def _ler_o_artigo(pedido: PedidoInferencia, disponiveis: set[str]) -> str | None:
    """A busca achou um parágrafo ou inciso (`art45_par1`): lê o artigo inteiro (`art45`) antes de responder — o trecho
    sozinho não diz a regra toda. Uma leitura só: artigo já lido, ou leitura que não trouxe nada, encerra."""
    if "ler_dispositivo" not in disponiveis:
        return None
    lidos = [m for bruto in pedido.conteudo for m in _NORMA_LIDA.findall(bruto)]
    if not lidos or any(c.startswith("A ferramenta ler_dispositivo") for c in pedido.conteudo):
        return None
    norma_id, endereco = lidos[0]
    artigo = endereco.split("_", 1)[0]
    if (norma_id, artigo) in lidos:
        return None
    argumentos = {"norma-id": norma_id, "endereco": artigo}
    return json.dumps({"acao": "ferramenta", "nome": "ler_dispositivo", "argumentos": argumentos})


def planejar(pedido: PedidoInferencia) -> str:
    if _TRILHA.search(_pergunta(pedido)):
        return json.dumps({"acao": "responder"})  # nenhuma ferramenta lê a trilha nem as conversas da Casa
    parecer = _planejar_parecer_juridico(pedido, _pergunta(pedido), _disponiveis(pedido))
    if parecer is not None:
        return parecer
    requerimento = _planejar_requerimento(pedido, _pergunta(pedido), _disponiveis(pedido))
    if requerimento is not None:
        return requerimento
    artigo_inteiro = _ler_o_artigo(pedido, _disponiveis(pedido))
    if artigo_inteiro is not None:
        return artigo_inteiro
    ja_consultou = any(c.startswith(("<fonte id=", "A ferramenta ")) for c in pedido.conteudo)
    if ja_consultou:
        return json.dumps({"acao": "responder"})
    pergunta = _pergunta(pedido)
    disponiveis = _disponiveis(pedido)
    m = _MATERIA.search(pergunta)
    if m:
        nome = "tramitacao_da_materia" if _TRAMITACAO.search(pergunta) else "situacao_da_materia"
        argumentos = {"tipo": SIGLAS[m.group(1).upper()], "sequencial": int(m.group(2)), "ano": int(m.group(3))}
        if nome in disponiveis:
            return json.dumps({"acao": "ferramenta", "nome": nome, "argumentos": argumentos})
    artigo = _ARTIGO.search(pergunta)
    especie = "regimento_interno" if _REGIMENTO.search(pergunta) else "lei_organica" if _LOM.search(pergunta) else None
    if artigo and especie and "ler_dispositivo" in disponiveis:
        argumentos = {"especie": especie, "endereco": f"art{int(artigo.group(1))}"}
        return json.dumps({"acao": "ferramenta", "nome": "ler_dispositivo", "argumentos": argumentos})
    if _NORMATIVA.search(pergunta) and "buscar_dispositivos" in disponiveis:
        argumentos = {"consulta": pergunta[:300]}
        return json.dumps({"acao": "ferramenta", "nome": "buscar_dispositivos", "argumentos": argumentos})
    if _PAUTA.search(pergunta) and "pauta_da_sessao" in disponiveis:
        return json.dumps({"acao": "ferramenta", "nome": "pauta_da_sessao", "argumentos": {}})
    return json.dumps({"acao": "responder"})


def _linha_informativa(texto: str) -> str:
    linhas = [linha.strip() for linha in texto.split("\n") if linha.strip()]
    for prefixo in _PREFERIDAS:
        for linha in linhas:
            if linha.startswith(prefixo):
                return linha
    return linhas[0] if linhas else ""


def responder(pedido: PedidoInferencia) -> str:
    pergunta = _pergunta(pedido)
    if _TRILHA.search(pergunta):
        return SEM_TRILHA
    fontes = [
        (fid, html.unescape(rotulo), html.unescape(versao), html.unescape(texto))
        for bruto in pedido.conteudo
        for fid, rotulo, versao, texto in _FONTE.findall(bruto)
    ]
    for fid, _r, _v, texto in fontes:
        titulo = next((linha for linha in texto.split("\n") if linha.startswith("titulo: ")), None)
        if titulo and any(linha.startswith("proposta-id: ") for linha in texto.split("\n")):
            return (
                f"Preparei a proposta: {titulo.removeprefix('titulo: ')}. Nada foi protocolado ainda: revise o texto, "
                f"assine e protocole na tela Propostas — ou recuse. [[{fid} | {titulo}]]"
            )
    # pedido de ato sem proposta criada: a Clara não fez nem vai fazer (agente-v2)
    paragrafos = [NAO_FAZ] if _ATO.search(pergunta) else []
    for fid, rotulo, versao, texto in fontes[:2]:
        if fid.startswith("norma:"):
            trecho = frase(texto).rstrip(" .;:")
            quando = f" ({versao})" if versao else ""
            paragrafos.append(f"{rotulo}{quando}: “{trecho}”. [[{fid} | {trecho}]]")
            continue
        linha = _linha_informativa(texto)[:200].rstrip(" .")
        if linha:
            valor = linha.split(": ", 1)[-1]
            paragrafos.append(f"Segundo o sistema da Casa: {valor}. [[{fid} | {linha}]]")
    return "\n\n".join(paragrafos) or SEM_FONTE
