"""Juntar transcrição (frases com início/fim) e falantes (diarização nomeada): cada frase vai para quem mais falou
durante ela. É o rascunho de ata "quem disse o quê" que a ata-IA recebe."""
from poc.juntar import atribuir_falantes


def test_frase_vai_para_quem_mais_falou_nela():
    frases = [(0, 5, "Bom dia a todos."), (5, 12, "Peço a palavra."), (30, 31, "...")]
    falantes = [(0, 6, "Presidente"), (6, 20, "Ana")]
    assert atribuir_falantes(frases, falantes) == [
        (0, 5, "Presidente", "Bom dia a todos."),
        (5, 12, "Ana", "Peço a palavra."),   # 1 s do Presidente, 6 s da Ana
        (30, 31, None, "..."),                # ninguém identificado: fica sem nome, para a revisão humana
    ]
