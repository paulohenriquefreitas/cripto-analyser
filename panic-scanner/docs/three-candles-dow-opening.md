# Primeira onda Dow micro: borda de abertura

Branch: feat/three-candles-foundation.

## Diagnostico antes da alteracao

Investigado WINZ26, ativo configurado no backend. A captura citada nao veio anexada; o simbolo da captura ainda depende de confirmacao do usuario. Fonte consultada: MetaTrader5 nativo pelo mt5_reader.py, intervalo 28/10/2026, M5. Dados brutos, hash, snapshots e logs de depuracao em frontend/src/features/threecandles/validation/opening/. Relogio civil MT5, sem conversao automatica de fuso.

Abertura configurada: 09:00. Primeiro candle disponivel: 09:00, aberto em 178.290, maxima 178.700, minima 177.240, fechado em 177.365. O intervalo inicial de comparacao termina no cursor 10:40, com 20 candles fechados.

O topo de 178.700 nao esta no backend como pivo 1+1. MicroDowEngine.analyze cria um observador novo e filtra candles pelo dia da sessao, retirando o warmup anterior. advance exige tres barras e avalia a barra central: a primeira origem elegivel e 09:05. A barra 09:00 nunca tem vizinho esquerdo nessa janela. Portanto a ausencia precede a selecao e o renderer. Nao houve descarte de topo existente nem segmento escondido fora do viewport: o snapshot anterior nao contem a origem nem a aresta.

O detector M5 principal tambem reinicia por dia em ThreeCandleStructure.process, mas nao foi alterado. M5ReplaySession.java pertence a outro fluxo; a pagina /three-candles usa ThreeCandleReplay, ThreeCandlesController e MicroDowEngine.

Depuracao real: logpoint nao suspensivo no primeiro teste 1+1 registrou 3:1793178000000:1793178900000 (3 barras, origem inicial 09:00, disponibilidade 09:15); apos essa avaliacao o candidato central e 09:05. Um breakpoint ao final do teste preservou os resultados para inspecao e drenagem dos eventos, pois o teste termina rapidamente. Frame openingDiagnosis:33, pilha JUnit; micro inicia no fundo de 177.010. Evidencia salva em debugger.json. Breakpoints criados pelo agente removidos apos a investigacao.

Primeiros cinco pivos micro do snapshot anterior e posterior, identicos:

| Tipo | Preco | Formacao | Confirmacao |
|---|---:|---|---|
| Fundo | 177.010 | 09:15 | 09:25 |
| Topo | 177.710 | 09:30 | 09:40 |
| Fundo | 176.825 | 09:35 | 09:45 |
| Topo | 177.690 | 10:00 | 10:10 |
| Fundo | 175.340 | 10:25 | 10:35 |

## Correcao independente

OpeningWave observa exclusivamente o primeiro M5 fechado das 09:00. As duas referencias candidatas usam maxima/minima desse candle e observedAtMsc=09:05. Sao ancoras de borda, nunca pivos confirmados, nao recebem HH/HL/LH/LL e nao participam da comparacao micro ou da Fibonacci.

WAITING aguarda o primeiro extremo nao ambiguo confirmado. A referencia oposta so fica CONNECTED se manteve estritamente o extremo ate a confirmacao do destino; igualdade ou ultrapassagem rejeitam a ligacao. Extremos intrabar ambiguos anteriores tambem rejeitam a ligacao. Se a primeira barra disponivel nao e 09:00, o estado e UNAVAILABLE; nenhum inicio de janela arbitrario vira abertura.

Em 28/10: referencia TOP 178.700 formada em 09:00 e observada em 09:05 conecta ao fundo 177.010 formado em 09:15 SOMENTE as 09:25. O trecho cobre 1.690 pontos. O desenho usa linha ambar pontilhada, circulo vazio e texto Abertura (referencia, nao pivo). O controle de ondas micro tambem governa esse trecho; o texto de referencia permanece mesmo com etiquetas HH/HL desligadas.

A validacao pelo extremo oposto resolve a ancora provisoria como referencia de borda sustentada pelos dados conhecidos. Ela nunca e promovida a pivo 1+1, pois falta o vizinho esquerdo. Se rejeitada, permanece auditada sem linha; o primeiro pivo confirmado segue a cadeia habitual. Nao se desloca a origem usando maxima/minima do dia. O trecho historico aceito nao e apagado por novos extremos posteriores ao destino. Eventos OPENING_OBSERVED e OPENING_CONNECTED/REJECTED ficam em opening.events; eventos micro originais permanecem intactos.

## Validacao

Snapshots before.json e after.json com os mesmos candles e cursor. O teste compara integralmente dow e os campos micro pivots, movements e events: igualdade exata. A unica adicao e microDow.opening.

Cada um dos 45 candles seguramente fechados de 28/10 foi comparado no modo incremental, reconstrucao com toda a memoria futura fornecida e corte por instante, e avanco de ThreeCandleReplay. A ultima barra do snapshot nativo foi excluida conservadoramente por poder estar em formacao. Casos adicionais: repeticao sem duplicacao, reset, abertura ausente, referencia superada, extremos duplos, direcao simetrica de alta e exemplo historico 16/10/2026. Nesse exemplo, minima inicial 208.075 conecta ao topo de 209.180 formado em 09:10, apenas na confirmacao 09:20.

Frontend valida o contrato causal e desenha a referencia separadamente; testes cobrem confirmacao futura, rejeicao e espera sem linha. Nenhuma integracao nova com Fibonacci, sinais ou estrategias. DowStructureEngine e o criterio 1+1 existente permanecem preservados.

Capturas before.png e after.png usam a interface React real com os snapshots Java interceptados por HTTP, mesma escala inicial e cursor. Nao sao capturas da sessao original do usuario; reproduzem o historico nativo do contrato investigado.

Resultados finais: 56 testes frontend aprovados; 5 testes de abertura, 2 micro e 4 Dow principal aprovados (1 teste Dow historico condicionado a propriedade nao executado). Verificacao TypeScript com npx tsc -b.

Atualizacao posterior de selecao micro e contagens: [continuidade micro](three-candles-dow-continuity.md). Os resultados acima descrevem a etapa anterior.
