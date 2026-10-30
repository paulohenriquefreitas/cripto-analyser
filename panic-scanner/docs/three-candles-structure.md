# Referencias estruturais em /three-candles

Comportamento atual: preparacao H1 antes da abertura, com origens nos tres
pregoes anteriores e confirmacao ate a abertura do pregao alvo. M5 continua
calculado e armazenado, com exibicao desativada por padrao.

A especificacao implementada, criterio conservador, limites do historico e
validacao com o H1 nativo do MT5 estao em
[h1-preparation-2026-10-26.md](h1-preparation-2026-10-26.md).

O detector ConfirmedPivotDetector continua compartilhado, sem alterar as outras
estrategias. H1SessionPreparation gera um plano imutavel para ThreeCandleStructure:
referencias H1 fucsia (#FF00FF) pontilhadas, estaveis durante o pregao, sem limite
de quantidade, dependencia de distancia ou descarte por rompimento. Agrupamento
visual a ate 5 pontos preserva todos os candidatos e seus grupos para auditoria.

O plano preparado contem sessionDate, sessionOpenMsc, sourceSessions, sufficient,
criterion, candidates, levels e groups. Precos, tipo, origem, confirmacao e estado
continuam no contrato StructuralLevel com timeframeMinutes. A memoria estrutural
continua separada da selecao e do desenho. O plano e reconstruido no reinicio do
backend a partir do historico; nao ha persistencia em banco.

A API informa availableAtMsc como relogio da sessao, independentemente do ultimo
candle. Replay utiliza o corte da abertura e nunca utiliza barras posteriores
nesse plano, mesmo quando a hora inicial do Replay e mais tarde. Reset restaura
o mesmo plano. Live preserva o plano na reconexao e prepara outro na troca de dia.
Somente candles do pregao corrente chegam ao grafico M5; o warmup continua no
processamento. Controles de visibilidade afetam somente as linhas desenhadas.

O parametro legado maxLevels (1 a 12, padrao 6) continua controlando apenas o
conjunto M5 na pagina com plano preparado; H1 nao tem corte por quantidade.
A selecao dinamica generica anterior fica preservada para os consumidores que
nao fornecem uma preparacao. M15 continua sem marcacoes estruturais.
