# Continuidade do micro 1+1

Branch feat/three-candles-foundation. Mudancas limitadas ao observador micro, seu contrato e visualizacao de Three Candles. Sem alteracao de Fibonacci, detector Dow principal, sinais ou estrategias.

## Limite da referencia visual

A imagem com tres vistos brancos nao esta disponivel neste turno. Data, contrato e intervalos dessa captura NAO foram certificados. Foi solicitada identificacao ao usuario. As capturas abaixo sao amostras tecnicas, nao uma afirmacao de reproducao exata dos vistos.

Fonte tecnica: WINZ26 M5 nativo MT5, exportacao existente em frontend/src/features/threecandles/validation/dow/WINZ26-m5.json, pregao 16/10/2026. Horarios civis da fonte, sem conversao automatica de fuso. Outro teste usa 45 barras seguramente fechadas da exportacao nativa de 28/10/2026 (ultima barra potencialmente em formacao excluida).

## Diagnostico anterior a alteracao

Baseline congelado em continuity/prefixes-before.json contem todos os 113 prefixos do algoritmo anterior. As tres janelas tecnicas sao 09:00-11:00, 11:00-13:00 e 13:40-17:00 de 16/10. regions.json lista cada OHLC M5, avaliacoes estritas, fractais, preco, formacao, confirmacao, uso anterior/atual e decisao. Os arquivos 2026-10-16-candles-before.json e 2026-10-28-candles-before.json cobrem todas as barras disponiveis, inclusive bordas e empates. Avaliacoes nesses arquivos sao diagnostico retrospectivo; somente fractais confirmados ate o cursor entram nos snapshots e no desenho.

Causas distintas encontradas:

1. Mesmo tipo consecutivo: terminal era sempre trocado, mesmo se menos extremo, sem atualizar a aresta anterior. A aresta seguinte partia do novo terminal, desconectado da anterior. Observado em execucao com logpoint no antigo terminal=p.
2. Extremo duplo: ambos os fractais eram registrados, mas o terminal era zerado. Isso impedia ligar a sequencia anterior ao proximo extremo nao ambiguo. Observado em execucao em origin=1792146600000, com terminal conhecido no fundo origin=1792145400000 antes do reset. Evidencia em debugger-before.json. Breakpoints criados pelo agente foram removidos; a sessao de teste do depurador foi encerrada depois da coleta, e os testes completos executados via Maven.
3. Limite do detector: no dia 16 foram encontradas quatro avaliacoes com maxima/minima igual a vizinho. Essas avaliacoes nao confirmam fractal estrito 1+1. Nao sao descartes de renderizacao nem justificam criar pivos.
4. Renderer: consumia as arestas enviadas e todos os pontos; nao conectava automaticamente terminais desconectados. O defeito de continuidade estava na selecao, nao em limite de distancia, quota ou tendencia principal.

Na janela tecnica inicial ha um terminal substituido e dois fractais ambiguos; na janela central ha duas substituicoes e tres fractais menos extremos; na janela final ha quatro fractais ambiguos e dois menos extremos. Todas essas contagens sao por origem na janela, usando apenas conhecimento disponivel no cursor final correspondente. Nao se presume que essas causas correspondam aos vistos ausentes.

## Politica nova e auditoria

MicroZigZag seleciona os fractais originais, sem exigir HH/HL/LH/LL de Dow, tendencia ou distancia minima. Uma cadeia usa topo/fundo alternados e origem estritamente crescente. Extremos consecutivos do mesmo tipo conservam o maior topo/menor fundo; empate de preco entre fractais conserva a origem mais antiga. Empates de vela no detector continuam nao sendo fractais. Extremos opostos com preco igual ou direcao inconsistente nao geram segmento.

Um fractal mais extremo substitui o terminal vigente e sua aresta de entrada somente a partir da nova confirmacao. movementHistory preserva todas as versoes geradas e decisions registra a aresta substituida, a nova aresta, motivo e instante. No Replay de um instante anterior, a antiga aresta e reconstituida. Um ponto inicial substituido antes de existir aresta tambem permanece auditado.

Fractais duplos permanecem originais e marcados AMBIGUOUS, sem presumir ordem intrabar. Eles nao entram na cadeia e nao apagam seu terminal. A proxima ligacao usa extremos nao ambiguos conhecidos, podendo atravessar a vela ambigua sem inventar uma oscilacao dentro dela.

pivots conserva todos os fractais, suas classificacoes e comparacoes originais. participation informa STRUCTURE, SUPERSEDED ou LOCAL no instante atual. selectedPivotIds identifica a cadeia atual; movements somente as linhas vigentes; movementHistory todas as versoes; decisions tambem explica porque uma linha nao foi gerada. events conserva integralmente os eventos originais do detector micro.

A referencia de abertura permanece separada e acompanha o primeiro extremo selecionado, inclusive quando ele e substituido. openingHistory registra suas versoes anteriores. Nunca e convertida em fractal, classificacao ou ancora Fibonacci.

Interface: linhas micro ciano, controle opcional Mostrar todos os fractais micro confirmados e marcadores pequenos para locais/substituidos. O toggle nao muda linhas, Replay ou Fibonacci. Auditoria JSON inclui todos os campos e horarios; o resumo mostra originais, utilizados, segmentos atuais e versoes historicas. O contrato valida confirmacao, decisoes/versoes futuras e consistencia da cadeia selecionada.

## Resultado

Na sessao inteira: 51 originais antes/depois, 35 extremos em ligacoes antigas desconectadas contra 29 na cadeia atual; 26 segmentos antes e 28 atuais. Historico com 32 versoes, incluindo quatro arestas substituidas. Decisoes: 1 inicio, 28 segmentos novos, 5 substituicoes de terminal (uma anterior a qualquer aresta), 7 locais menos extremos e 10 fractais ambiguos. Todos os fractais permanecem na auditoria.

Contagens acumuladas ate cada cursor (utilizados e segmentos em formato antes/depois):

| Cursor | Fractais | Utilizados antes/depois | Segmentos antes/depois | Versoes atuais+substituidas |
|---|---:|---:|---:|---:|
| 11:00 | 11 | 8 / 8 | 6 / 7 | 7 |
| 13:00 | 21 | 15 / 13 | 11 / 12 | 14 |
| 17:00 | 44 | 31 / 26 | 23 / 25 | 27 |

Contagens restritas as janelas por origem dos fractais e destino dos segmentos:

| Janela tecnica | Fractais | Utilizados antes/depois | Segmentos antes/depois |
|---|---:|---:|---:|
| 09:00-11:00 | 11 | 8 / 8 | 6 / 7 |
| 11:00-13:00 | 10 | 7 / 5 | 5 / 5 |
| 13:40-17:00 | 18 | 13 / 12 | 11 / 12 |

Menos pontos utilizados em uma janela pode resultar da eliminacao de terminais redundantes desconectados, mantendo todos os fractais como marcadores opcionais. O criterio e uma cadeia coerente de extremos confirmados, nao maximizar o numero de segmentos.

## Capturas e verificacao

Arquivos continuity/11-before.png, 11-after.png, 13-before.png, 13-after.png, 17-before.png e 17-after.png. Interface React real, respostas Java interceptadas por HTTP, cursor e ajuste de escala identicos em cada par. Niveis M5/H1 e Fibonacci ocultados apenas para tornar o desenho legivel; Dow principal permanece independente. Os marcadores de todos os fractais estao ligados. Os tres pares sao tecnicos e aguardam identificacao da imagem original.

MicroDowContinuityTest compara os 113 prefixos incremental/reconstrucao/ThreeCandleReplay. Todos os campos originais dos fractais, exceto participation (agora selecao efetiva), e todos os eventos sao comparados ao baseline congelado; Dow principal e comparado integralmente nos checkpoints. Verifica ordem, alternancia, continuidade entre arestas, disponibilidade e idempotencia. Testes simetricos cobrem alta/baixa, substituicao, empate entre fractais, locais menos extremos, ambiguidade, rejeicao temporal e historico imutavel. Testes de lateralizacao conservam oscilacoes pequenas sem filtro de amplitude. Testes de abertura cobrem tambem 28/10, reset, sem lookahead e duplicacoes. Renderer verifica que marcadores extras nao desenham segmentos historicos nem alteram a cadeia.

Executado: 57 testes frontend e TypeScript; 13 testes Java aprovados, com dois testes condicionais nao executados (captura do baseline original e teste historico legado com propriedade). Maven: -pl panic-scanner -am test -Dtest=MicroDowContinuityTest,MicroDowOpeningTest,MicroDowEngineTest,DowStructureEngineTest -Dsurefire.failIfNoSpecifiedTests=false.

Nao se implementou detector alternativo: reconhecer um plato exigiria outra regra de sensibilidade e sua propria confirmacao causal, separada do 1+1 estrito. Oscilacoes sem fractais, ambiguidades intrabar e bordas sem vizinhos permanecem limitacoes explicitas.
