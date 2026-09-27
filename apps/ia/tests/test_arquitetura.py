"""Enforcement do ADR-0006: um único caminho até o LLM.

- o SDK de fornecedor só é importado pelo seu adaptador em `inferencia/`;
- o adaptador real só é importado pela fábrica da porta;
- `.gerar(` (chamar a porta) só acontece no filtro de governança e dentro de `inferencia/` (portas que envolvem
  portas). Capacidade nova compõe o `nucleo`; ninguém fala com o fornecedor por fora.
"""

import ast
from pathlib import Path

PACOTE = Path(__file__).parent.parent / "src" / "oplenario_ia"
SDKS = {"anthropic", "openai", "google", "mistralai", "cohere"}


def _modulos() -> list[tuple[str, ast.Module]]:
    return [(p.relative_to(PACOTE).as_posix(), ast.parse(p.read_text())) for p in sorted(PACOTE.rglob("*.py"))]


def _importa(arvore: ast.Module) -> set[str]:
    nomes: set[str] = set()
    for no in ast.walk(arvore):
        if isinstance(no, ast.Import):
            nomes |= {a.name for a in no.names}
        elif isinstance(no, ast.ImportFrom) and no.module:
            nomes.add(no.module)
    return nomes


def test_sdk_de_fornecedor_so_no_adaptador() -> None:
    violacoes = [
        (rel, n)
        for rel, arv in _modulos()
        for n in _importa(arv)
        if n.split(".")[0] in SDKS and not (rel.startswith("inferencia/") and rel.endswith("_adapter.py"))
    ]
    assert violacoes == []


def test_adaptador_real_so_pela_fabrica() -> None:
    violacoes = [
        rel
        for rel, arv in _modulos()
        if any(n.endswith("_adapter") for n in _importa(arv)) and rel != "inferencia/fabrica.py"
    ]
    assert violacoes == []


def test_so_o_filtro_chama_a_porta() -> None:
    violacoes = [
        rel
        for rel, arv in _modulos()
        for no in ast.walk(arv)
        if isinstance(no, ast.Call)
        and isinstance(no.func, ast.Attribute)
        and no.func.attr == "gerar"
        and rel != "governanca/filtro.py"
        and not rel.startswith("inferencia/")
    ]
    assert violacoes == []


def test_o_proprio_lint_enxerga_uma_violacao() -> None:
    arv = ast.parse("import anthropic\nporta.gerar(p)\n")
    assert "anthropic" in _importa(arv)
    assert any(isinstance(n, ast.Call) and getattr(n.func, "attr", None) == "gerar" for n in ast.walk(arv))
