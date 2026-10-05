# O Keycloak das Casas com o tema de login do O Plenario (ADR-0024).
#
# Mesma versao do docker-compose e do CI (26.0.0). O tema e' so' um diretorio (FreeMarker + CSS + fontes): nao ha'
# build de tema nem provider Java. Em producao, o servico Keycloak do Dokploy passa a construir esta imagem (contexto
# `apps/keycloak`) no lugar da imagem pura; os realms usam o tema pelo `loginTheme` que o provisionamento grava
# (KEYCLOAK_TEMA_LOGIN, padrao "oplenario"). Passo a passo em docs/27-runbook-ia-producao.md, secao 9.
FROM quay.io/keycloak/keycloak:26.0.0
COPY --chown=1000:0 temas/oplenario /opt/keycloak/themes/oplenario
