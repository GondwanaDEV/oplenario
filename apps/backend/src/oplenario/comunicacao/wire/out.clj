(ns oplenario.comunicacao.wire.out
  "Contratos de SAIDA dos comunicados internos (ADR-0020, §22.10 wire/out). Chaves kebab no fio; datas ISO (`ciencia-
  ate` e' o DIA, AAAA-MM-DD; as marcas e o envio sao instantes ISO-8601 UTC). `vencido` (e nao `vencido?`, como a ADR
  escreve) porque a chave vira campo de TypeScript.")

(def ^:private Instante :string)
(def ^:private Dia :string)

(def RefComunicado
  [:map {:closed true} [:id :string] [:protocolo :string]])

(def Pessoa
  [:map {:closed true} [:identidade-id :string] [:nome :string]])

(def DestinoOut
  [:map {:closed true}
   [:tipo [:enum "pessoa" "vereador" "setor" "comissao" "todos"]]
   [:alvo-id [:maybe :string]]
   [:alvo-nome :string]])

(def ObjetoOut
  [:map {:closed true} [:tipo [:enum "sessao" "proposicao" "protocolo"]] [:id :string]])

(def AnexoOut
  [:map {:closed true}
   [:id :string]
   [:nome :string]
   [:tipo-midia :string]
   [:bytes :int]
   [:sha256 :string]
   [:enviado-em Instante]])

(def MarcasOut
  "As marcas DESTA pessoa (destinataria). `vencido` = o comunicado pede ciencia, o prazo passou e ela nao deu."
  [:map {:closed true}
   [:recebido-em [:maybe Instante]]
   [:lido-em [:maybe Instante]]
   [:ciente-em [:maybe Instante]]
   [:vencido :boolean]])

(def ComunicadoOut
  "GET /comunicados/:id (e o 201 do envio). `minhas-marcas` so' para quem e' destinatario (nil para os outros).
  `prazo-vencido` = o prazo de ciencia passou (para todos). `pode-anexar` = quem enviou, nos 10 min, com menos de 5."
  [:map {:closed true}
   [:id :string]
   [:protocolo :string]
   [:assunto :string]
   [:corpo :string]
   [:remetente Pessoa]
   [:enviado-em Instante]
   [:exige-ciencia :boolean]
   [:ciencia-ate [:maybe Dia]]
   [:prazo-vencido :boolean]
   [:objeto [:maybe ObjetoOut]]
   [:destinos [:vector DestinoOut]]
   [:destinatarios :int]
   [:anexos [:vector AnexoOut]]
   [:substitui [:maybe RefComunicado]]
   [:substituido-por [:maybe RefComunicado]]
   [:minhas-marcas [:maybe MarcasOut]]
   [:pode-ver-leitura :boolean]
   [:pode-anexar :boolean]])

(def EnviadoOut
  "O 201 de POST /comunicados: o comunicado (como o remetente o ve) + `sem-acesso` = quantos membros dos grupos pedidos
  nao entraram por nao terem acesso ao sistema (vereador sem identidade) — a tela avisa."
  (into ComunicadoOut [[:sem-acesso :int]]))

(def ItemCaixaOut
  [:map {:closed true}
   [:id :string]
   [:protocolo :string]
   [:assunto :string]
   [:trecho :string]
   [:remetente Pessoa]
   [:enviado-em Instante]
   [:via :string]
   [:exige-ciencia :boolean]
   [:ciencia-ate [:maybe Dia]]
   [:vencido :boolean]
   [:recebido-em [:maybe Instante]]
   [:lido-em [:maybe Instante]]
   [:ciente-em [:maybe Instante]]
   [:objeto [:maybe ObjetoOut]]
   [:n-anexos :int]
   [:substituido-por [:maybe RefComunicado]]])

(def CaixaOut
  "GET /meu/comunicados (a caixa): os 50 mais recentes + os TOTAIS (fora do teto): `nao-lidos`, `pendentes-ciencia` e o prazo
  mais proximo das ciencias pendentes ainda nao vencidas (`proxima-ciencia-ate`)."
  [:map {:closed true}
   [:itens [:vector ItemCaixaOut]]
   [:nao-lidos :int]
   [:pendentes-ciencia :int]
   [:proxima-ciencia-ate [:maybe Dia]]])

(def ItemEnviadoOut
  [:map {:closed true}
   [:id :string]
   [:protocolo :string]
   [:assunto :string]
   [:remetente Pessoa]
   [:enviado-em Instante]
   [:destinos [:vector DestinoOut]]
   [:exige-ciencia :boolean]
   [:ciencia-ate [:maybe Dia]]
   [:prazo-vencido :boolean]
   [:destinatarios :int]
   [:recebidos :int]
   [:lidos :int]
   [:cientes :int]
   [:pendentes-vencidos :int]
   [:substituido-por [:maybe RefComunicado]]])

(def EnviadosOut
  "GET /meu/comunicados/enviados[?escopo=casa]."
  [:map {:closed true}
   [:escopo [:enum "meus" "casa"]]
   [:itens [:vector ItemEnviadoOut]]])

(def LinhaLeituraOut
  [:map {:closed true}
   [:identidade-id :string]
   [:nome :string]
   [:via :string]
   [:recebido-em [:maybe Instante]]
   [:lido-em [:maybe Instante]]
   [:ciente-em [:maybe Instante]]
   [:vencido :boolean]])

(def LeituraOut
  "GET /comunicados/:id/leitura: \"12 de 15 leram, 3 faltam\" — os totais e uma linha por destinatario."
  [:map {:closed true}
   [:comunicado [:map {:closed true}
                 [:id :string] [:protocolo :string] [:assunto :string] [:exige-ciencia :boolean]
                 [:ciencia-ate [:maybe Dia]] [:prazo-vencido :boolean]]]
   [:totais [:map {:closed true}
             [:destinatarios :int] [:recebidos :int] [:lidos :int] [:cientes :int] [:faltam-ler :int]
             [:faltam-ciencia :int] [:vencidos :int]]]
   [:linhas [:vector LinhaLeituraOut]]])

(def CienciaOut
  "POST /comunicados/:id/ciencia (idempotente)."
  [:map {:closed true}
   [:id :string]
   [:protocolo :string]
   [:minhas-marcas MarcasOut]])

(def DestinosOut
  "GET /meu/comunicados/destinos: as opcoes do formulario. Os grupos (setores ativos, comissoes vigentes com a Mesa, todos
  os setores) vem vazios/zero para quem nao pode enviar a grupo. `membros` = quantas pessoas com acesso recebem;
  `sem-acesso` = membros da comissao sem acesso ao sistema."
  [:map {:closed true}
   [:pode-enviar-a-grupos :boolean]
   [:pessoas [:vector Pessoa]]
   [:vereadores [:vector [:map {:closed true} [:id :string] [:nome :string] [:tem-acesso :boolean]]]]
   [:setores [:vector [:map {:closed true} [:id :string] [:nome :string] [:membros :int]]]]
   [:comissoes [:vector [:map {:closed true} [:id :string] [:nome :string] [:membros :int] [:sem-acesso :int]]]]
   [:todos-os-setores :int]])
