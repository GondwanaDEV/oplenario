"""A Camada de Confiança mínima (§16.8, estendida em §22.11) como infra reutilizável — ADR-0006.

As cinco peças que TODO output de IA carrega: citação (por dispositivo, conferida contra o que foi lido na mesma
execução), indicação de incerteza, registro auditável sem conteúdo, reportar erro e revisão humana obrigatória antes de
publicar — mais o piso R-IA-1 ("IA indisponível — siga pela tela"). Nenhuma capacidade reimplementa isto: compõe o
`nucleo`.
"""
