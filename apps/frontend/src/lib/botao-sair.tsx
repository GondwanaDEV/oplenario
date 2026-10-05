// "Sair" — encerra a sessão da Casa: POST em `/api/auth/logout` (apaga a sessão no backend, limpa os cookies e passa
// pelo logout do Keycloak, que volta para a raiz, e a raiz sem sessão abre `/entrar`). É um formulário, não um link:
// o logout só aceita POST, para que um link de fora não derrube a sessão de ninguém.
export function BotaoSair({ className }: { className?: string }) {
  return (
    <form method="post" action="/api/auth/logout" className="form-sair">
      <button type="submit" className={className}>
        Sair
      </button>
    </form>
  );
}
