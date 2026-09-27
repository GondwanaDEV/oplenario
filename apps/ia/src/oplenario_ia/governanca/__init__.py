"""O filtro de governança fail-closed (B1–B4) — o ÚNICO caminho até o LLM externo (§22.9 Eixo 10.3; ADR-0006).

Porta de produção do protótipo `prototipos/governanca-ia/` (Clojure), com as seis limitações do README dele
corrigidas: dígito verificador de CPF/CNPJ e contexto (sem mutilar nº de ofício/processo), guarda de payload vazio,
hash SHA-256, contrato de erro do fornecedor auditado. A proveniência de sigilo nasce no CORE e viaja com o
conteúdo; aqui ela só é LIDA, nunca re-derivada.
"""
