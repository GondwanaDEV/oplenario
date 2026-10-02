// Layout de TODA página do portal de uma Câmara (/portal/casa/[ente]/...). Um só lugar decide se a Câmara foi
// ENCERRADA (ADR-0018 fatia 2): o backend responde 410 em toda rota dela, e aqui a página inteira vira o aviso
// "Esta Câmara não usa mais O Plenário" — com a data e o link para onde foi o acervo público, se informado. Qualquer
// outro veredito segue para a página, que faz o dela (nome, 404, degradação).

import { resolverCasa } from "@/lib/portal-api";
import { CasaEncerrada } from "../../../casa-encerrada";

export default async function LayoutDaCasa({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ ente: string }>;
}) {
  const { ente } = await params;
  const casa = await resolverCasa(ente);
  if (casa.estado === "encerrada") return <CasaEncerrada casa={casa} />;
  return <>{children}</>;
}
