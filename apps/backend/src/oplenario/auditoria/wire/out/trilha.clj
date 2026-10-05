(ns oplenario.auditoria.wire.out.trilha
  "Contrato de SAIDA da trilha de auditoria da Casa (ADR-0017). Quem, o que, sobre o que, decisao, quando e de onde
  — nunca conteudo. O cidadao sai pseudonimizado; o IP sai truncado (o completo fica no banco, por 6 meses). Malli
  fechado.")

(def ^:private Instante :string)

(def AtorOut
  [:map {:closed true}
   [:tipo [:enum "pessoa" "cidadao" "agente"]]
   [:nome [:maybe :string]]
   [:papeis [:vector :string]]
   [:via [:maybe :string]]])

(def RegistroOut
  [:map {:closed true}
   [:seq :int]
   [:em Instante]
   [:ator AtorOut]
   [:acao :string]
   [:classe [:enum "escrita" "negacao" "entrada" "leitura_sensivel"]]
   ;; `sem_desfecho` = a escrita foi iniciada e o desfecho dela nao foi registrado (ADR-0017, adendo de 04/10/2026)
   [:decisao [:enum "permitido" "negado" "falhou" "sem_desfecho"]]
   [:recurso [:maybe [:map {:closed true} [:tipo [:maybe :string]] [:id [:maybe :string]] [:rotulo [:maybe :string]]
                      ;; o rotulo e' o resumo da ACAO (`resumos/por-acao`), e nao o nome de um objeto dado pelo handler:
                      ;; a tela decide por este campo, nunca pelo texto do rotulo (que pode vir de dado de usuario)
                      [:do-ato :boolean]]]]
   [:campos [:vector :string]]
   [:canal :string]
   [:ip [:maybe :string]]
   [:selo :string]
   [:selo-anterior :string]])

(def AtuacaoOperacaoOut
  [:map {:closed true} [:em Instante] [:acao :string] [:operador [:maybe :string]] [:selo :string]])

(def TrilhaOut
  [:map {:closed true}
   [:escopo [:enum "casa" "acessos" "propria"]]
   [:total :int]
   [:total-da-casa [:maybe :int]]
   [:registros [:vector RegistroOut]]
   [:proximo [:maybe :int]]
   [:operacao [:maybe [:vector AtuacaoOperacaoOut]]]])

(def SeloDoDiaOut [:map {:closed true} [:dia :string] [:seq :int] [:selo :string]])

(def IntegridadeOut
  [:map {:closed true}
   [:integra :boolean]
   [:total :int]
   [:cabeca [:maybe :string]]
   [:quebra-em [:maybe :int]]
   [:selos-do-dia [:vector SeloDoDiaOut]]
   ;; quantas escritas foram iniciadas e ficaram sem desfecho registrado, e o seq da mais antiga
   [:sem-desfecho :int]
   [:primeiro-sem-desfecho [:maybe :int]]])

(def SelosPublicosOut
  [:map {:closed true} [:selos-do-dia [:vector SeloDoDiaOut]]])
