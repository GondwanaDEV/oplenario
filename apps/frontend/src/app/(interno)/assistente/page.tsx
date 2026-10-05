// /assistente — o endereço antigo da tela cheia do assistente da secretaria. A Clara agora é o painel que acompanha toda
// tela interna (ADR-0024, fatia 5): esta rota só leva à Central da Casa (`/inicio`, a página inicial da secretaria, que
// era quem abria esta tela) com `?clara=expandida`, que a moldura lê, abre e tira da URL. Fica de pé para os links
// antigos e para o gate do middleware (que mede as páginas em disco). Mesmo desenho de /vereador/assistente.
//
// Server Component: o redirecionamento sai antes de qualquer tela. O `?token=` de dev vai junto (comToken).

import { redirect } from "next/navigation";
import { comToken } from "@/lib/nav";

export default async function PaginaAssistente({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { token } = await searchParams;
  redirect(comToken("/inicio?clara=expandida", typeof token === "string" ? token : null));
}
