# O Keycloak das Casas com o tema de login do O Plenario (ADR-0025).
#
# A versao vem de fora (`KEYCLOAK_VERSION`): o build-keycloak-prd passa a que o compose de producao roda hoje, porque o
# Keycloak NAO sobe contra um banco ja' migrado para uma versao mais nova (a 1a troca, de 26.0.0 sobre a 26.3.5 de
# producao, derrubou o login). O padrao e' a de producao em 05/10/2026; dev e CI ainda rodam a 26.0.0 (docker-compose e
# ci.yml), e o tema foi conferido nas duas. O tema e' so' um diretorio (FreeMarker + CSS + fontes): nao ha' build de
# tema nem provider Java. Os realms usam o tema pelo `loginTheme` que o provisionamento grava (KEYCLOAK_TEMA_LOGIN,
# padrao "oplenario"). Passo a passo em docs/27-runbook-ia-producao.md, secao 9.
ARG KEYCLOAK_VERSION=26.3.5
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --chown=1000:0 temas/oplenario /opt/keycloak/themes/oplenario
