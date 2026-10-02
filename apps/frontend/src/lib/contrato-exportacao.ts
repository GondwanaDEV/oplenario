// ADR-0018 (fatia 2) — o contrato da EXPORTAÇÃO completa da Câmara (9.6), escrito à mão a partir de
// `admin_sistema/wire/out/exportacao.clj` (ExportacaoOut, ExportacoesDaCasaOut). O mesmo metadado serve o console do
// operador e a área do administrador da Câmara; o conteúdo só sai pelo download, que é do administrador.

/** ADR-0018 (fatia 2): a exportação completa — METADADO (estado, tamanho, código, manifesto resumido). O conteúdo só
 *  sai pelo download, que é do administrador da Câmara. */
export type Exportacao = {
  id: string;
  estado: "gerando" | "pronta" | "falhou";
  solicitadaEm: string | null;
  solicitadaPor: "operador" | "admin_ente";
  concluidaEm: string | null;
  sha256: string | null;
  bytes: number | null;
  manifesto: Record<string, unknown> | null;
  erro: string | null;
  confirmadaEm: string | null;
  confirmadaPor: "admin_ente" | "oficio" | null;
  oficio: string | null;
};

/** O bloco "Exportar os dados da Câmara" (GET /administracao/exportacoes). */
export type ExportacoesDaCasa = {
  /** a exportação existe nesta instalação (e a Câmara está no registro da plataforma) */
  disponivel: boolean;
  /** a Câmara está em encerramento: a confirmação abre a contagem dos 90 dias */
  emEncerramento: boolean;
  exportacoes: Exportacao[];
};
