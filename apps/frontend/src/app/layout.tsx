import type { Metadata } from "next";
import "./globals.css";
import { TemaProvider, SCRIPT_TEMA_INICIAL } from "@/lib/tema";

export const metadata: Metadata = {
  title: "O Plenário",
  description: "Onde a câmara acontece.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  // suppressHydrationWarning no <html>: o script anti-FOUC seta data-tema na raiz antes da hidratação — o
  // mismatch com o HTML do servidor é esperado e benigno (padrão de tema do Next).
  return (
    <html lang="pt-BR" suppressHydrationWarning>
      <head>
        {/* anti-FOUC: aplica o tema antes da pintura (evita flash claro->escuro na hidratação) */}
        <script dangerouslySetInnerHTML={{ __html: SCRIPT_TEMA_INICIAL }} />
      </head>
      <body>
        <TemaProvider>{children}</TemaProvider>
      </body>
    </html>
  );
}
