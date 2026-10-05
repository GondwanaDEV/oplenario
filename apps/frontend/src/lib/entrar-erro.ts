// View-model puro das mensagens de /entrar e /entrar/[ente]. Quem põe o `?erro=` na URL: o login handler
// (app/api/auth/login/route.ts, `login` — a descoberta do tenant falhou, sem dizer qual Casa existe) e a entrada pelo
// CPF (app/api/auth/entrar/route.ts, ADR-0024). Esta função só deriva a mensagem; a página decide renderizar.

const MENSAGENS: Record<string, string> = {
  "cpf-invalido": "Este CPF não confere. Revise os 11 números.",
  "sem-acesso":
    "Não encontramos acesso de servidor ou vereador para este CPF. Se você trabalha na Câmara, fale com o administrador dela.",
  "sem-acesso-nesta":
    "Este CPF não tem acesso a esta Câmara. Se você trabalha em outra, entre pela página inicial do O Plenário.",
  "muitas-tentativas": "Muitas tentativas a partir desta rede. Espere alguns minutos e tente de novo.",
  indisponivel: "Não conseguimos conferir o CPF agora. Tente de novo em instantes.",
  escolha: "O tempo para escolher a Câmara acabou. Digite o CPF de novo.",
};

const GENERICA = "Não foi possível concluir o login. Verifique o endereço da sua câmara e tente novamente.";

export function mensagemErroEntrada(erro?: string): string | null {
  if (!erro) return null;
  return MENSAGENS[erro] ?? GENERICA;
}
