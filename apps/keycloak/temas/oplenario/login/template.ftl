<#--
  O Plenário · a moldura de toda página de login do Keycloak (ADR-0025).

  Mesmo cartão do arquétipo produto/design-system/o-plenario/telas/login.html: faixa de azulejo, a Câmara à frente
  (nome do realm = nome oficial da Casa, gravado pelo provisionamento), o título da página, o formulário, e
  "Plataforma O Plenário" no rodapé. Toda página herdada do tema `base` (código do autenticador, criar senha, erro,
  página expirada) entra aqui.

  Diferenças deliberadas do template do `base`:
  - o nome de usuário tentado NÃO aparece: na Casa ele é o identidade-id (um UUID), que não diz nada à pessoa;
  - provedores externos (o gov.br) não aparecem: a tela é a do servidor e do vereador — o cidadão entra pelo portal,
    que leva direto ao gov.br (kc_idp_hint);
  - um só idioma (pt-BR), sem seletor.
-->
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="pt-BR">
<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="robots" content="noindex, nofollow">
    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${msg("loginTitle",(realm.displayName!''))}</title>
    <link rel="icon" type="image/svg+xml" href="${url.resourcesPath}/img/selo.svg" />
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module">
        import { checkCookiesAndSetTimer } from "${url.resourcesPath}/js/authChecker.js";

        checkCookiesAndSetTimer(
          "${url.ssoLoginInOtherTabsUrl?no_esc}"
        );
    </script>
</head>

<body class="${properties.kcBodyClass!} ${bodyClass}">
<main class="op-cartao ${properties.kcLoginClass!}">
    <div class="op-faixa" aria-hidden="true"><span class="a"></span><span class="b"></span><span class="c"></span><span class="d"></span></div>
    <div class="op-corpo">
        <div class="op-casa">
            <img class="op-brasao" src="${url.resourcesPath}/img/selo.svg" alt="" width="42" height="42" />
            <span><b>${realm.displayName!'O Plenário'}</b><span>${msg("opAcessoAoSistema")}</span></span>
        </div>

        <div class="op-titulo">
            <h1 id="kc-page-title"><#nested "header"></h1>
            <#nested "subtitulo">
        </div>

        <#-- Ação iniciada pelo app não mostra o aviso de "complete a ação" durante o login. -->
        <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
            <div class="${properties.kcAlertClass!} op-aviso-${message.type}" role="<#if message.type = 'error'>alert<#else>status</#if>">
                <span class="${properties.kcAlertTitleClass!}">${kcSanitize(message.summary)?no_esc}</span>
            </div>
        </#if>

        <div id="kc-content">
            <#nested "form">

            <#if auth?has_content && auth.showTryAnotherWayLink()>
                <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post" class="op-outra-forma">
                    <input type="hidden" name="tryAnotherWay" value="on"/>
                    <button type="submit" id="try-another-way" class="op-link">${msg("doTryAnotherWay")}</button>
                </form>
            </#if>

            <#if displayInfo>
                <div id="kc-info" class="${properties.kcSignUpClass!}">
                    <div id="kc-info-wrapper" class="${properties.kcInfoAreaWrapperClass!}">
                        <#nested "info">
                    </div>
                </div>
            </#if>
        </div>

        <div class="op-rodape">
            <span class="op-plataforma">
                <img src="${url.resourcesPath}/img/selo.svg" alt="" width="18" height="18" />
                ${msg("opPlataforma")} <b>O Plenário</b>
            </span>
        </div>
    </div>
</main>
</body>
</html>
</#macro>
