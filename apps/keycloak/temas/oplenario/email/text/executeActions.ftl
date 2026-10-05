<#ftl output_format="plainText">
<#-- O Plenário (ADR-0025): a versão em texto do convite (ver html/executeActions.ftl). -->
<#assign requiredActionsText><#if requiredActions??><#list requiredActions><#items as reqActionItem>${msg("requiredAction.${reqActionItem}")}<#if reqActionItem?has_next><#if reqActionItem?counter == requiredActions?size - 1> e <#else>, </#if></#if></#items></#list></#if></#assign>

${msg("executeActionsBody",link, linkExpiration, realmName, requiredActionsText, linkExpirationFormatter(linkExpiration))}
