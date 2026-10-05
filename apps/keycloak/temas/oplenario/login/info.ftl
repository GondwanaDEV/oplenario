<#--
  O Plenário · a página de informação do Keycloak (ADR-0024). É a primeira tela de quem abre o link do convite
  ("vamos configurar seu acesso", com os passos) e a última ("pronto", com o botão de volta à entrada pelo CPF).
  Mesma lógica do info.ftl do tema `base` do Keycloak 26.0.0; mudam o título, a lista de passos e os links, que viram
  botões.
-->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        <#if requiredActions??>
            ${msg("opConfigurarAcesso")}
        <#elseif messageHeader??>
            ${kcSanitize(msg("${messageHeader}"))?no_esc}
        <#else>
            ${message.summary}
        </#if>
    <#elseif section = "form">
        <div id="kc-info-message">
            <#if requiredActions??>
                <p class="instruction">${msg("opPassosDoAcesso")}</p>
                <ol class="op-passos">
                    <#list requiredActions as reqActionItem>
                        <li>${kcSanitize(msg("requiredAction.${reqActionItem}"))?no_esc}</li>
                    </#list>
                </ol>
            <#elseif messageHeader??>
                <#-- sem cabeçalho próprio, o título já é a mensagem: não repete -->
                <p class="instruction">${message.summary}</p>
            </#if>
            <#if skipLink??>
            <#else>
                <#if pageRedirectUri?has_content>
                    <a class="op-btn op-btn-primaria op-btn-bloco op-btn-grande" href="${pageRedirectUri}">${kcSanitize(msg("backToApplication"))?no_esc}</a>
                <#elseif actionUri?has_content>
                    <a class="op-btn op-btn-primaria op-btn-bloco op-btn-grande" href="${actionUri}">${kcSanitize(msg("opComecar"))?no_esc}</a>
                <#elseif (client.baseUrl)?has_content>
                    <a class="op-btn op-btn-primaria op-btn-bloco op-btn-grande" href="${client.baseUrl}">${kcSanitize(msg("backToApplication"))?no_esc}</a>
                </#if>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
