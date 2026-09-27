"""O redator FAKE do resumo cidadão — roteiro determinístico do fornecedor fake para `resumo.redigir` (dev, CI, demo).

Lê as fontes que o filtro montou e devolve um resumo curto que CITA a ementa e o primeiro dispositivo (a conferência
roda de verdade) e termina com um parágrafo sem fonte — o caminho completo da revisão aparece na tela sem nenhum
fornecedor real.
"""

from __future__ import annotations

import html
import re

from oplenario_ia.ata.fake import FONTE, frase
from oplenario_ia.inferencia.modelo import PedidoInferencia


def redigir(pedido: PedidoInferencia) -> str:
    fontes = [(fid, html.unescape(texto)) for bruto in pedido.conteudo for fid, _rotulo, texto in FONTE.findall(bruto)]
    paragrafos: list[str] = []
    for fid, texto in fontes:
        if fid.endswith("#ementa"):
            ementa = re.sub(r"^.*?Ementa:\s*", "", texto, flags=re.DOTALL)
            trecho = frase(ementa)
            paragrafos.append(f"Esta proposição trata do seguinte: {trecho} [[{fid} | {trecho}]]")
            break
    # o primeiro dispositivo COM CONTEÚDO: um título solto ("Lei", "PROJETO DE LEI") não sustenta frase nenhuma
    dispositivos = [(fid, texto) for fid, texto in fontes if not fid.endswith("#ementa") and len(texto.strip()) >= 40]
    if dispositivos:
        fid, texto = dispositivos[0]
        # "Art. 1º" não é fim de frase: o trecho do dispositivo é o começo dele, sem cortar na abreviação
        trecho = " ".join(texto.split())[:120].rstrip()
        paragrafos.append(f"Na prática, o texto diz: “{trecho}” [[{fid} | {trecho}]]")
    paragrafos.append("Se for aprovada, a regra passa a valer no município depois de publicada.")
    return "\n\n".join(paragrafos)
