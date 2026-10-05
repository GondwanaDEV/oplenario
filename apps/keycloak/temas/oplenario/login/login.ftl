<#--
  O Plenário · a tela de senha da Câmara (ADR-0024).

  Dois modos:
  - COM o usuário vindo do O Plenário (a pessoa digitou o CPF em /entrar e o app mandou o identidade-id como
    `login_hint`): o campo de usuário vai ESCONDIDO e a pessoa só digita a senha. O usuário da Casa é um UUID —
    reconhecido aqui pelo formato, o que também mantém o modo depois de uma senha errada.
  - SEM ele (alguém abriu o Keycloak direto): o campo `#username` aparece, como e-mail, e há o atalho para entrar pelo
    CPF. Os workflows de homolog preenchem `#username`/`#password` e clicam em `#kc-login` neste modo: os três ids
    ficam iguais aos do Keycloak.
-->
<#import "template.ftl" as layout>
<#assign usuarioDoApp = (login.username!'')?matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")>
<#assign erroCredencial = messagesPerField.existsError('username','password')>
<@layout.registrationLayout displayMessage=!erroCredencial; section>
    <#if section = "header">
        <#if usuarioDoApp>${msg("opDigiteSuaSenha")}<#else>${msg("doLogIn")}</#if>
    <#elseif section = "subtitulo">
        <p><#if usuarioDoApp>${msg("opSenhaSubtitulo")}<#else>${msg("opEmailSubtitulo")}</#if></p>
    <#elseif section = "form">
        <#if realm.password>
            <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                <#if usuarioDoApp>
                    <input type="hidden" id="username" name="username" value="${login.username}" autocomplete="username" />
                <#else>
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="username" class="${properties.kcLabelClass!}">${msg("opEmailInstitucional")}</label>
                        <input id="username" class="${properties.kcInputClass!}" name="username" value="${(login.username!'')}" type="text" autofocus autocomplete="username"
                               aria-invalid="<#if erroCredencial>true</#if>" aria-describedby="<#if erroCredencial>input-error</#if>" dir="ltr" />
                    </div>
                </#if>

                <div class="${properties.kcFormGroupClass!}">
                    <label for="password" class="${properties.kcLabelClass!}">${msg("password")}</label>
                    <div class="${properties.kcInputGroup!}" dir="ltr">
                        <input id="password" class="${properties.kcInputClass!}" name="password" type="password" autocomplete="current-password"
                               <#if usuarioDoApp>autofocus</#if>
                               aria-invalid="<#if erroCredencial>true</#if>" aria-describedby="<#if erroCredencial>input-error</#if>" />
                        <button class="${properties.kcFormPasswordVisibilityButtonClass!}" type="button" aria-label="${msg("showPassword")}"
                                aria-controls="password" data-password-toggle
                                data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                                data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
                            <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                        </button>
                    </div>
                    <#if erroCredencial>
                        <span id="input-error" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                            <#if usuarioDoApp>${msg("opSenhaIncorreta")}<#else>${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}</#if>
                        </span>
                    </#if>
                </div>

                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" name="login" id="kc-login" type="submit" value="${msg("doLogIn")}"/>
                </div>
            </form>

            <div class="op-nota">
                <#if realm.resetPasswordAllowed>
                    <a href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
                <#else>
                    <span>${msg("opEsqueceuASenha")}</span>
                </#if>
            </div>

            <#if (client.baseUrl)?has_content>
                <p class="op-trocar">
                    <a href="${client.baseUrl}"><#if usuarioDoApp>${msg("opOutroCpf")}<#else>${msg("opEntrarComCpf")}</#if></a>
                </p>
            </#if>
        </#if>
        <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
    </#if>
</@layout.registrationLayout>
