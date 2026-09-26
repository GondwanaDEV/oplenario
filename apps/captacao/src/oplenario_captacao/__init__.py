"""Utilitário de captação do O Plenário (Faixa A / A.2 da Track IA; ADR-0007).

Leva o arquivo que o OBS grava no PC da transmissão até o core (`POST /gravacoes`), sem reencoding: é a fonte
primária da V1 (§22.3.4, "gravação local pós-sessão"). Só a biblioteca padrão do Python.
"""

__version__ = "0.1.0"
