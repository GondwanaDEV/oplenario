"use client";

// O caminho, dentro do app, para a pessoa trocar o PRÓPRIO e-mail de acesso. O `admin_ente` não troca o e-mail de
// quem já entrou (seria tomar a conta); a própria pessoa troca na página de conta do Keycloak da Casa dela
// ("Personal info"), que é do Keycloak e está em inglês (docs/16, achado B de 05/10/2026).
//
// O link aponta para `/api/auth/conta`, que lê a descoberta do login (cookie httpOnly) e redireciona — o
// navegador nunca recebe host, realm nem client. Aparece só onde a conta existe:
//   - modo real (cookie de sessão): em modo dev o token de dev não tem Keycloak por trás;
//   - quem entrou pelo login da Casa: a sessão do gov.br é a de vínculo `cidadao` (ADR-0015), e a conta dessa
//     pessoa é a do gov.br, que não se troca aqui;
//   - e só depois de `/eu` responder com o tipo do vínculo: sem saber, não mostra.

import { modoReal } from "./modo";
import { useEu } from "./use-eu";

/** `true` quando a pessoa tem conta no Keycloak da Casa para trocar o e-mail. Chama o hook sempre (regra dos hooks). */
export function usePodeTrocarEmail(token: string | null): boolean {
  const eu = useEu(token);
  return modoReal() && eu.estado === "pronto" && eu.tipoVinculo !== null && eu.tipoVinculo !== "cidadao";
}

export function LinkTrocarEmail({ token, className }: { token: string | null; className?: string }) {
  const pode = usePodeTrocarEmail(token);
  if (!pode) return null;
  return <AnchorTrocarEmail className={className} />;
}

/** O link em si, para quem já decidiu mostrá-lo (o menu do topo usa `usePodeTrocarEmail` por conta própria). */
export function AnchorTrocarEmail({ className }: { className?: string }) {
  return (
    // <a> simples, não next/link: o destino é uma rota de API que responde com redirect, aberta em outra aba.
    <a className={className} href="/api/auth/conta" target="_blank" rel="noopener noreferrer">
      Trocar meu e-mail de acesso
      <span className="conta-nota"> — abre a página de conta, em inglês</span>
    </a>
  );
}
