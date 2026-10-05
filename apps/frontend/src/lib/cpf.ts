// O CPF conferido no navegador e no BFF pelo dígito verificador — espelha `oplenario.kernel.cpf` do backend, que
// confere de novo. Serve o console do operador (1º administrador de uma Casa) e a entrada pelo CPF (ADR-0024).

export function soDigitos(entrada: string): string {
  return entrada.replace(/\D/g, "");
}

export function cpfValido(entrada: string): boolean {
  const d = soDigitos(entrada);
  if (d.length !== 11 || /^(\d)\1{10}$/.test(d)) return false;
  const dv = (n: number) => {
    let s = 0;
    for (let i = 0; i < n; i++) s += Number(d[i]) * (n + 1 - i);
    const r = s % 11;
    return r < 2 ? 0 : 11 - r;
  };
  return dv(9) === Number(d[9]) && dv(10) === Number(d[10]);
}
