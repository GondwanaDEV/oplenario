// /vereador/assistente — o endereço antigo da tela cheia do assistente no app do vereador. A Clara agora abre no
// próprio app, expandida (ADR-0024, fatia 4): esta rota só leva à home com `?clara=expandida`, que a moldura lê, abre
// e tira da URL. Fica de pé para os links antigos e para o gate do middleware (que mede as páginas em disco).
//
// Server Component: o redirecionamento sai antes de qualquer tela. O `?token=` de dev vai junto (comToken).

import { redirect } from "next/navigation";
import { comToken } from "@/lib/nav";

export default async function PaginaAssistenteVereador({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { token } = await searchParams;
  redirect(comToken("/vereador?clara=expandida", typeof token === "string" ? token : null));
}
