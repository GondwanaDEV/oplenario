<#-- O Plenário (ADR-0025): o convite do primeiro acesso. Igual ao do tema `base`, mas a lista de etapas sai em
     português corrido ("criar a sua senha e cadastrar o código"), não separada só por vírgula. -->
<#outputformat "plainText">
<#assign requiredActionsText><#if requiredActions??><#list requiredActions><#items as reqActionItem>${msg("requiredAction.${reqActionItem}")}<#if reqActionItem?has_next><#if reqActionItem?counter == requiredActions?size - 1> e <#else>, </#if></#if></#items></#list></#if></#assign>
</#outputformat>

<#import "template.ftl" as layout>
<@layout.emailLayout>
${kcSanitize(msg("executeActionsBodyHtml",link, linkExpiration, realmName, requiredActionsText, linkExpirationFormatter(linkExpiration)))?no_esc}
</@layout.emailLayout>
