# DMI/ADX isolado por sessao intradiaria

Branch: feat/three-candles-foundation.

O painel DMI do modulo de tres candles utiliza `DmiStudy`, um calculo proprio de Wilder. Os adaptadores TA4J do scanner geral e `crypto-analyzer/application.yml` nao alimentam esse painel e nao foram alterados.

Antes: `DmiStudy.analyze` processava todos os candles recebidos para inicializar OHLC anterior, TR, DM e suavizacoes, filtrando somente os pontos exibidos para a data do cursor. Assim o historico anterior ficava oculto no painel, mas participava do calculo e do aquecimento. Um teste de regressao com gaps reproduziu ADX 99,963239 e baixa ja no primeiro candle do novo dia; a analise isolada do mesmo candle corretamente ainda nao tinha valores.

Agora: a entrada e filtrada antes de construir o estado numerico, mantendo somente candles do simbolo M5 da data do cursor e com origem a partir das 09:00. Sao preservados timestamps civis nativos do feed, sem deslocamento ou uso da data do computador. Como cada snapshot reconstrui o estado, o reset funciona no tempo real, no Replay fechado e no progressivo. Nao depende de receber exatamente um evento as 09:05; se o primeiro candle disponivel for posterior as 09:00, ele se torna a ancora. Nenhuma barra ausente e fabricada.

O historico do grafico e o aquecimento dos demais indicadores continuam intactos. Gaps dentro do proprio pregao continuam participando do True Range; apenas a ligacao com a sessao anterior e removida.

Aquecimento: esta implementacao produz DI apos 14 movimentos entre barras (15 candles) e ADX apos mais 14 DX, totalizando 28 candles. Com abertura as 09:00 e sem lacunas, o primeiro DI fechado fica disponivel as 10:15 e o primeiro ADX confirmado as 11:20. O horario fixo 09:30/09:45 nao substitui esse aquecimento. Disponibilidade do primeiro valor tambem nao significa garantia de calibracao ou resultado operacional.

A interface informa aquecimento da sessao e contagem de candles fechados. O histograma nao espera o ADX: ele utiliza campos separados `warmupPlusDi`/`warmupMinusDi` para exibir uma estimativa inicial cumulativa da sessao antes da semente de Wilder. O primeiro candle e uma ancora neutra (sem movimento direcional anterior); os seguintes mostram os movimentos observados. Barras em aquecimento tem tons suaves e contorno pontilhado. DI ja semeado pode ser exibido antes de o ADX ficar pronto. Valores DI/ADX confirmados e o regime oficial continuam seguindo a formula anterior, sem antecipacao. Uma parcial nao antecipa o estado confirmado. O schema do frontend rejeita pontos DMI de outro dia ou anteriores as 09:00; o historico principal pode continuar exibindo dias anteriores.

Validacao: regressao antes/depois com gaps de alta e baixa e movimentos intradiarios em ambas as direcoes; igualdade entre analisar toda a memoria e analisar somente a sessao em cada prefixo; primeiro candle ausente; pre-abertura; parcial inicial sem herdar aquecimento; novo dia sem dados; formula independente de Wilder; horario, replay progressivo e adaptadores. Testes frontend cobrem transporte, aquecimento e classificacao, mantendo ADX<20 lateral independentemente do sinal de DI.

Executar: `mvn -o -pl panic-scanner -am -Dtest=DmiStudyTest,ThreeCandleTimeTest,ProgressiveM1ReplayTest,ThreeCandleAdaptersTest -Dsurefire.failIfNoSpecifiedTests=false test`; frontend: `node --experimental-strip-types --test tests/threeCandlesDmi.test.mjs`.


## Histograma desde a abertura

O primeiro ADX pertence ao candle de origem 11:15 e so fica disponivel no fechamento 11:20. Essa diferenca e origem versus disponibilidade, sem deslocamento de timestamp. O erro visual era exigir `adx != null` para desenhar qualquer barra, deixando vazio o periodo de abertura mesmo quando ja havia movimentos conhecidos.

A estimativa inicial e exclusivamente visual: razao entre DM acumulado e TR acumulado da propria sessao. Nao altera os acumuladores de Wilder, nao inventa um ADX e nao libera o regime oficial. O primeiro candle das 09:00 fornece a referencia neutra; a primeira comparacao direcional surge no segundo candle (09:05, disponivel as 09:10 no Replay fechado). Em parciais ela pode ser observada antes do fechamento, sempre identificada como parcial. Se faltar o primeiro candle, a primeira barra efetivamente recebida e a ancora. Nenhum candle e fabricado.

Testes verificam as duas direcoes desde a segunda barra, ancora neutra, compatibilidade dos valores estritos com a referencia independente de Wilder, estimativas sem alterar a classificacao, reset sem gap de ontem e representacao distinta de aquecimento e parcial no frontend.
