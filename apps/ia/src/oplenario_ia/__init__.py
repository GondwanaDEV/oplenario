"""Plataforma de IA do O Plenário — o satélite (§22.2, §22.3, §22.11; docs/adr/0006).

Camadas: `inferencia` (a porta vendor-agnóstica), `governanca` (o filtro fail-closed, único caminho até o LLM),
`confianca` (a Camada de Confiança mínima, §16.8), `avaliacao` (avaliação no CI e custo por execução) e `nucleo`
(o pipeline único que toda capacidade de IA compõe).
"""

__version__ = "0.1.0"
