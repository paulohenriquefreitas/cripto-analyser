# Engine do padrao de tres candles V2

Implementada em `feat/three-candles-foundation`, em 31/10/2026. Criterio `CONTEXT_CLOSE_NEGATION_TEST_RETURN_M1_V2`.

## Sequencia implementada

1. Congelar contexto antes de C1. `ThreeCandleMarketContext` separa o fluxo de aproximacao do estado da estrutura principal/micro. O fluxo operacional inicial e o sinal da diferenca entre os dois ultimos fechamentos M5 consecutivos da propria sessao, ja disponiveis antes de C1. Sao incluidos ambos os candles como evidencia. Empate, lacuna ou menos de dois candles: contexto indefinido, sem candidato operacional. Nao usa o gap anterior nem exige que DMI/ADX vire para a direcao da entrada.
2. Contato com alvo publicado anteriormente e apenas `CANDIDATO_ALVO`. Compra exige aproximacao DOWN e abertura da observacao acima do alvo; venda exige UP e aproximacao pelo lado inferior. A abertura dentro da regiao continua inconclusiva. Alvos e seus horarios sao os ja publicados pelo estudo Dow/H1/50%, sem alterar motores ou detectores. Confluencias permanecem registros distintos, sem inventar numero de operacoes.
3. C2 e o M5 imediatamente seguinte, fechado. Compra: fechamento C2 >= fechamento C1. Venda: fechamento C2 <= fechamento C1. Igualdade aceita; cor e pavios nao sao requisitos adicionais. Sao armazenados preco comparado e motivo. Sem negacao, o contato e rejeitado e nao aparece numerado como C1.
4. Ao fechar C2 valido, C1 e C2 recebem juntos suas marcacoes. A marca 1 aparece na origem do candle anterior, mas sua disponibilidade e o fechamento de C2. `recognizedAtMsc` registra isso. Nao ha retroacao de sinais.
5. C3 e o M5 imediatamente seguinte. Sua abertura e a referencia inicial congelada: na primeira observacao do candle sua maxima/minima inicial coincide com a abertura. Compra exige excursao estrita abaixo dela; venda, acima. Nao exige retorno a uma faixa arbitraria de C2. O menor deslocamento positivo observado e suficiente nesta versao; nao foi acrescentado minimo de amplitude nao definido pelo usuario.
6. Apos reconhecer o teste com M1 nativo contiguo, disponibiliza-se um gatilho hipotetico fixo: abertura C3 +20 compra, -20 venda. O proprio M1 de teste nunca comprova execucao: ele pode ter feito teste e passagem pelo nivel em ordem desconhecida.
7. Outro M1, posterior ao teste e ao posicionamento, precisa abrir antes do gatilho e atingir seu nivel na direcao contraria ao fluxo original. Esse trajeto demonstra retorno apos o teste. O evento de rejeicao e separado do evento de alcance. Retorno abaixo/acima da abertura tambem e registrado antes do gatilho quando observado por fechamento M1.
8. Sem teste: sem ordem, mesmo que C3 atravesse o nivel que seria de entrada. Sem M1 suficiente, o range M5 nao inventa a sequencia. Lacunas e abertura M1 ja alem do gatilho ficam indeterminadas. Validade adotada: somente C3; ordem pendente expira em seu fechamento. Um alcance demonstrado nao e apagado por acontecimentos posteriores.

## Escolhas de engenharia e limites

O deslocamento entre dois fechamentos e uma primeira definicao objetiva do fluxo, NAO uma regra textual atribuida ao professor nem garantia de reproduzir toda leitura humana. O principal e o micro podem discordar e ambos permanecem na auditoria. A regra pode ser refinada por exemplos anotados, sem usar dados posteriores ao alvo.

A abertura de C3 como ancora fixa implementa a referencia inicial explicada pelo usuario. A interpretacao deve ser revista caso o usuario queira outra maxima/minima parcial especifica; nao usar o extremo final futuro nem mover a ordem continuamente.

O envelope de maximas/minimas C1/C2 deixou de ser uma invalidacao automatica: esse stop nao foi definido pelo usuario. Nao foram inventados stop, quantidade, gestao de posicao, targets financeiros ou taxa de acerto. O modulo reconhece e audita candidatos e gatilhos HIPOTETICOS; nao envia ordens a corretora.

M1 fechado nao reproduz uma decisao aos quatro segundos de C3. Sequencias dentro do mesmo minuto podem continuar inconclusivas e precisar de ticks. Nenhum fill, instante intraminuto ou ordem real e inferido. O gatilho fica disponivel somente quando o teste e conhecido; o preco de abertura e a ancora, mas seu horario de conhecimento em M1 e o fechamento da primeira observacao nativa de C3, sem retroagir ao inicio nominal do minuto. A ordem nunca e retroativa.

## Integracao e interface

A implementacao substitui a politica anterior no `ExperimentalThreeCandlePatternDetector`, reutilizando `ThreeCandlePatternStudy`, ledger e dados nativos ja existentes. A mesma reconstrucao causal e usada no tempo real, Replay fechado e progressivo. Schema frontend aceita V1 para dados legados e valida separadamente V2, inclusive fluxo, candles anteriores, confirmacao tardia de C1, teste estrito, referencia fixa, rejeicao e gatilho posterior.

A selecao inicial e o acompanhamento automatico priorizam candidatos que ja tenham marcacoes confirmadas; contatos ainda nao negados continuam consultaveis na tabela, sem numeros antecipados. A auditoria mostra fonte do fluxo, estados Dow, comparacao dos fechamentos, teste e motivos. As hipoteses A/B anteriores aparecem somente como legado. Dow, Fibonacci manual/antiga, DMI e agrupamento M5 nao foram alterados por esta implementacao.

## Validacao

Testes sinteticos cobrem compra/venda, C2 da mesma cor, igualdade de fechamentos, rejeicao de continuacao, C1 sem marca antes de C2, contexto ausente/tardio/imutavel, abertura de C3 fixa, teste obrigatorio, subida direta sem entrada, passagem no proprio M1, lacunas/abertura alem do nivel, idempotencia e ausencia de M1.

Dataset nativo: WINZ26 de 30/10/2026, 86 M5 da sessao e 430 M1 ate 16:10, com aquecimento estrutural anterior. Avanco incremental em 430 observacoes comparado com reconstrucao em cada fechamento M5 e cortes selecionados, incluindo Replay fechado e progressivo. Fonte: `src/test/resources/threecandles/pattern-detector/`. Nao e o mesmo pregao dos exemplos de aula.

Resultados em `frontend/src/features/threecandles/validation/pattern-detector-v2/`: 258 registros por contato/referencia, 93 confirmados por C2 e 40 alcances hipoteticos por referencia. Esses numeros nao sao taxa de acerto nem contagem de trades independentes; `verification-v2.json` inclui contagem distinta por trio/direcao/nivel e todos os motivos. Snapshots publicados: 0921, 0926, 0927, 1113, 1120, 1121, 1123, 1140 e 1155.

Frontend: 104 testes passaram; TypeScript passou. Java: 45 casos executados passaram, com um teste Dow principal ja marcado como ignorado. Regressao Java inclui detector, auditoria visual, adaptadores, horario, Replay progressivo, DMI e motores Dow. Um teste de Dow principal ja era condicionalmente ignorado.

Reiniciar backend e recarregar frontend para ativar a V2. Sem alteracao em `crypto-analyzer/application.yml`, pois esse arquivo nao alimenta o detector desta pagina.


Captura de validacao: `frontend/src/features/threecandles/validation/pattern-detector-v2/example-buy.png`. Pagina React real e snapshot nativo congelado das 11:23; API interceptada, sem conexao operacional ou ordem real. O exemplo selecionado e uma compra em C1 11:10, C2 11:15, C3 11:20, alvo 170115 e gatilho 170260. Reproduzir a captura com `frontend/scripts/capturePatternV2.mjs` e a auditoria com `frontend/scripts/auditPatternV2.mjs`.
