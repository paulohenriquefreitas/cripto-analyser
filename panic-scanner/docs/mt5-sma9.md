# WIN M5 — SMA9 via TA4J

Abra http://localhost:5173/win-m5 com o MT5, backend e frontend existentes.
A linha laranja SMA 9 compartilha escala, tempo, zoom e pan com os candles.
O valor corrente aparece acima do gráfico, com quatro casas decimais para
comparação com o Profit (simples, período 9, fechamento). Não há arredondamento
adicional no backend; o transporte usa double.

## Conversão e indicador

Reutilizamos a dependência TA4J 0.22.6 fornecida pelo módulo ta4j-engine e o
package infrastructure.ta4j já existente. Nenhuma dependência/versão nova.
Mt5BarSeriesConverter converte OHLC para Num com a numFactory da BarSeries.
Usa realVolume, sem somar volumes de ticks; amount e trades ficam zero porque
não são fornecidos por Mt5Candle. Isso não participa da SMA.

O timestamp MT5 continua bruto. Instant.ofEpochSecond(time) é o início da barra;
o fim da barra TA4J é início + duração de cinco minutos. Essa duração é o
intervalo da barra, não uma correção de fuso. A resposta REST e a linha usam
sempre o timestamp original de abertura. Nenhum offset manual.

O indicador é SMAIndicator(new ClosePriceIndicator(series), 9), do package
org.ta4j.core.indicators.averages. O TA4J permite janelas parciais, mas aqui os
primeiros oito candles do histórico disponível recebem sma9=null e não são
desenhados. Calculamos com até 1000 candles antes de recortar os últimos 100:
por isso os 100 pontos exibidos normalmente já têm janela completa.

## REST e streaming

GET /api/mt5/candles continua retornando um array dos últimos 100 candles,
com todos os campos anteriores e sma9. Mt5ChartCandle usa JsonUnwrapped para
reutilizar o objeto Mt5Candle sem duplicar seus campos.

Ta4jMt5Sma9Adapter mantém uma série compartilhada, limitada a 1000 barras.
Cada consulta REST cria e aquece uma série fora do lock do tick; a troca de
referência e reaplicação do último tick são breves e sincronizadas. Consultas
REST concorrentes são serializadas no use case, sem segurar o lock do stream
enquanto Python é executado.

No mesmo bucket bruto (floorDiv(time, 300)), addPrice altera somente a última
barra TA4J. Open/volume são preservados; close/high/low acompanham last.
O TA4J 0.22.6 invalida seu cache da última barra quando close muda. A consulta
SMA corrente tem trabalho limitado à janela de nove barras. Não há Python,
REST, reconstrução de histórico nem crescimento de memória por tick.

/ws/mt5/ticks acrescenta sma9 e sma9Time (abertura bruta do candle calculado).
Antes do histórico ou numa nova janela ainda não sincronizada, ambos são null.
Os demais campos e a infraestrutura de conexão/processo permanecem intactos.

A virada continua sendo detectada pelo frontend existente, com um único resync
em andamento e retry de cinco segundos. Não criamos barras a partir de ticks.
Após o REST instalar a série oficial, o handler reenvia o último tick enriquecido,
mesmo que não haja outro negócio: assim um tick recebido durante o resync não
fica sem SMA. O frontend guarda/reaplica esse evento e só aceita sma9 quando
sma9Time corresponde ao candle atual. Ticks de buckets anteriores são ignorados.

A Lightweight Charts usa LineSeries.setData no histórico/resync e
LineSeries.update no ponto corrente. O frontend não calcula média nem atualiza
o estado React da página por tick. A legenda numérica é atualizada junto à linha.

## Validação desta etapa

- mvn -B verify, Java 21: seis módulos com BUILD SUCCESS; 148 testes no
  panic-scanner e 14 no crypto-analyzer, todos aprovados.
- npm.cmd test: 15 testes aprovados, sem MT5.
- npm.cmd run build e npm.cmd run lint aprovados. Permanece o aviso existente
  do Vite sobre bundle maior que 500 kB.
- Testes: OHLC/volume/instantes na BarSeries; closes 100..108 => 104;
  101..109 => 105; close corrente 108 => 117 resulta em 105; atualizações
  repetidas invalidam cache; warmup, limite de memória, tick antes de REST,
  virada, replay via WebSocket e preservação da precisão no frontend.
- Edge headless com MT5 real: 100 candles e 100 pontos SMA, uma instalação de
  histórico na série, mais de 25 updates e valores SMA variando. Os pontos
  recebidos foram comparados aos valores exatos do WebSocket; sem REST adicional
  durante a amostra intrabar, sem recriar canvas e sem erros JavaScript.
  Amostras reais: 188375.5555555556, 188375, 188374.4444444444.
  O modo React StrictMode fez duas tentativas iniciais de REST (cleanup aborta
  a primeira); isso não representa request por tick.
- Observado um único processo Python persistente de streaming após reinício.
  Não foi feito benchmark formal de latência nem comparação automatizada com Profit.

## Arquivos desta etapa

Criados:
- src/main/java/.../application/Mt5ChartCandle.java
- src/main/java/.../infrastructure/ta4j/Mt5BarSeriesConverter.java
- src/main/java/.../infrastructure/ta4j/Ta4jMt5Sma9Adapter.java
- src/test/java/.../infrastructure/ta4j/Ta4jMt5Sma9AdapterTest.java
- docs/mt5-sma9.md

Modificados:
- src/main/java/.../application/usecase/GetMt5CandlesUseCase.java
- src/main/java/.../api/Mt5CandlesController.java
- src/main/java/.../api/Mt5TickWebSocketHandler.java
- src/test/java/.../api/Mt5CandlesControllerTest.java
- src/test/java/.../api/Mt5TickWebSocketHandlerTest.java
- src/test/java/.../infrastructure/marketdata/Mt5TickStreamLifecycleTest.java
  (somente adaptação do construtor do handler)
- frontend/src/features/win/api/mt5Api.ts
- frontend/src/features/win/api/mt5LiveConnection.ts
- frontend/src/features/win/models/mt5Intrabar.ts
- frontend/src/features/win/components/Mt5CandlestickChart.tsx
- frontend/tests/mt5Intrabar.test.mjs
- frontend/tests/mt5LiveConnection.test.mjs

As reticências Java representam br/com/bauzin/market/panic/panicscanner.

Referências de API consultadas:
- https://raw.githubusercontent.com/ta4j/ta4j/0.22.6/ta4j-core/src/main/java/org/ta4j/core/indicators/averages/SMAIndicator.java
- https://tradingview.github.io/lightweight-charts/docs/api/interfaces/ISeriesApi#update


## Evolução: SMA21 vermelha

A implementação agora também calcula SMAIndicator(close, 21), compartilhando
exatamente a mesma BarSeries e instância ClosePriceIndicator com a SMA9.
O adapter existente foi estendido sem introduzir outro serviço/série/processo.
Os primeiros 20 candles disponíveis recebem sma21=null; o recorte para os 100
últimos continua acontecendo depois do cálculo sobre até 1000 candles.

REST acrescenta sma21 ao objeto existente. WebSocket acrescenta sma21 e
sma21Time (mesmo timestamp bruto de abertura usado na SMA9, ou null enquanto
não houver janela completa). Uma única atualização addPrice alimenta ambas
as médias. O trabalho por tick fica limitado às janelas 9 e 21, sem IO extra.
O método refreshSma9 existente também reenvia a SMA21 depois do mesmo resync.

A segunda LineSeries usa vermelho explícito #ff5252, mesma escala direita e
mesmo eixo temporal; setData no histórico/resync e update no ponto corrente.
A legenda SMA 21 vermelha aparece abaixo de SMA 9 com quatro casas decimais.
A aparência da SMA9 permanece intacta. Nenhuma fórmula foi adicionada ao JS.

Validação da extensão:
- Maven verify: 151 testes panic-scanner + 14 crypto-analyzer aprovados;
  todos os seis módulos com BUILD SUCCESS.
- Frontend: 16 testes, build e lint aprovados. Aviso de bundle grande permanece.
- Sequências 1..21 => 11 e 2..22 => 12; close 22 => 43 produz SMA21=13.
  Testes também cobrem atualização repetida, warmup, virada/replay, payload
  WebSocket com ambas as médias e timestamp do ponto no frontend.
- Navegador com MT5 real: 100 candles e 100 pontos em cada média; SMA21 variou
  entre 188339.0476190476 e 188338.8095238095 durante a amostra, exatamente
  conforme o WebSocket. Cor #ff5252 verificada; canvas preservado, uma chamada
  setData da SMA21, sem REST adicional durante os ticks e sem erros JS.
  Não houve comparação automática com Profit nem benchmark formal de latência.

Arquivos modificados nesta extensão (nenhum novo arquivo de produção/teste):
- infrastructure/ta4j/Ta4jMt5Sma9Adapter.java
- application/Mt5ChartCandle.java
- api/Mt5TickWebSocketHandler.java
- testes Ta4jMt5Sma9AdapterTest.java, Mt5CandlesControllerTest.java e
  Mt5TickWebSocketHandlerTest.java
- frontend/src/features/win/api/mt5Api.ts e mt5LiveConnection.ts
- frontend/src/features/win/models/mt5Intrabar.ts
- frontend/src/features/win/components/Mt5CandlestickChart.tsx
- frontend/tests/mt5Intrabar.test.mjs e mt5LiveConnection.test.mjs
- este documento.
