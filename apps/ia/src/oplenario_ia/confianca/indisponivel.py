"""O piso R-IA-1: "IA indisponível — siga pela tela" (§22.9 Eixo 13 E1).

A IA é produtividade, não caminho crítico: todo ato fecha sem ela (§16.4). Quando a IA não pode ou não consegue
ajudar, o resultado é este — explícito, com a razão em linguagem da pessoa, nunca um erro técnico cru nem um silêncio.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel

from oplenario_ia.erros import Categoria

MotivoIndisponivel = Literal[
    "sigilo", "nada_a_enviar", "fornecedor_fora", "sobrecarga", "entrada_invalida", "saida_invalida", "recusa"
]

MENSAGENS: dict[MotivoIndisponivel, str] = {
    "sigilo": "Este conteúdo é sigiloso e não vai para a IA. Siga pela tela.",
    "nada_a_enviar": "Não há conteúdo público para a IA trabalhar. Siga pela tela.",
    "fornecedor_fora": "A IA está indisponível agora. Siga pela tela — nada do seu trabalho depende dela.",
    "sobrecarga": "A IA está sobrecarregada agora. Siga pela tela ou tente de novo em instantes.",
    "entrada_invalida": "A IA não aceitou este pedido. Siga pela tela.",
    "saida_invalida": "A IA devolveu uma resposta inválida. Siga pela tela.",
    "recusa": "A IA não produziu resposta para este conteúdo. Siga pela tela.",
}

POR_CATEGORIA: dict[Categoria, MotivoIndisponivel] = {
    Categoria.INFRAESTRUTURA: "fornecedor_fora",
    Categoria.SOBRECARGA: "sobrecarga",
    Categoria.ENTRADA: "entrada_invalida",
    Categoria.MODELO: "saida_invalida",
}


class Indisponivel(BaseModel):
    execucao_id: str
    motivo: MotivoIndisponivel
    mensagem: str
    retentavel: bool
    categoria: Categoria | None = None


def indisponivel(
    execucao_id: str, motivo: MotivoIndisponivel, *, retentavel: bool = False, categoria: Categoria | None = None
) -> Indisponivel:
    return Indisponivel(
        execucao_id=execucao_id, motivo=motivo, mensagem=MENSAGENS[motivo], retentavel=retentavel, categoria=categoria
    )
