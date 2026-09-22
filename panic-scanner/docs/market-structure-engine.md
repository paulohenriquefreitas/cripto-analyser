# MarketStructureEngine

## Objetivo

O `MarketStructureEngine` mede, em tempo de evento, a interação do preço com
SMA9 e SMA21. Ele não produz recomendação, lado operacional, alerta, suporte,
resistência ou classificação de rejeição.

```text
Live Price
    │
    ├── SMA9 intrabar
    │      ↓
    │   tracker SMA9
    │
    └── SMA21 intrabar
           ↓
        tracker SMA21
           │
           ▼
MovingAverageInteraction
```

`MovingAverageInteraction` é uma medição objetiva. Não representa recomendação,
sinal ou rejeição validada.

## Entrada e fonte LIVE

O domínio recebe somente `MarketStructureSample(symbol, timeMsc, price, sma9,
sma21)`. Ele não conhece MT5, Python, JSON, WebSocket ou TA4J.

No LIVE, `Mt5TickWebSocketHandler` já entrega cada `Mt5Tick` ao
`Ta4jMt5Sma9Adapter`. Esse adaptador mantém a série M5 oficial usada pelo
gráfico, substitui intrabar o fechamento do candle corrente e calcula SMA9 e
SMA21 na mesma `BarSeries`. `MarketStructureLiveObserver` usa exatamente o
`last`, o `timeMsc` e o resultado dessa atualização. Nenhuma segunda fórmula de
média foi criada e o payload visual não mudou.

Enquanto a série ainda não possui 21 candles, a observação aguarda ambas as
médias. Isso evita alimentar o domínio com valores parciais ou artificiais.

## Configuração inicial

Todos os valores são **pontos de preço do WIN**, não quantidade de ticks:

| Parâmetro | Valor | Uso |
|---|---:|---|
| `nearDistancePoints` | 10 | entra na região próxima em `abs(distance) <= 10` |
| `exitDistancePoints` | 20 | conclui em `abs(distance) >= 20` |
| `touchTolerancePoints` | 2,5 | registra toque sem exigir igualdade de double |
| `approachObservationCount` | 3 | exige três reduções consecutivas da distância |
| `approachMinimumReductionPoints` | 5 | ignora convergência/afastamento microscópico |

O preço observado do WIN varia em grade de cinco pontos. A tolerância de 2,5
pontos permite comparar essa grade com SMAs fracionárias. Estes defaults são
parâmetros iniciais de observação e não thresholds validados para trading.

## Estados e histerese

Cada média possui tracker independente e memória constante:

```text
FAR
  -> APPROACHING  após convergência configurada
  -> NEAR         abs(distance) <= 10
  -> TOUCHING     abs(distance) <= 2,5
  -> PENETRATED   sinal da distância cruza o lado de aproximação
  -> MOVING_AWAY  distância cresce pelo menos 5 desde o ponto mais próximo
  -> FAR          abs(distance) >= 20; interação concluída
```

TOUCHING e PENETRATED são fatos independentes: um salto discreto pode cruzar
sem produzir uma amostra dentro da tolerância de toque. O cruzamento também não
implica rompimento, rejeição ou continuidade.

A histerese usa 10 pontos para entrada e 20 para saída. Oscilações em 10/11 ou
9/11 não criam várias interações. Eventos com o mesmo `timeMsc` são aceitos;
timestamp menor que o anterior e mistura de símbolos são rejeitados. `reset()`
limpa símbolo, relógio, trackers e última interação.

Se a primeira amostra já estiver exatamente sobre a média e não houver lado
anterior conhecido, `approachSide` é `AT`. O motor não inventa ABOVE ou BELOW.

## Resultado e eventos

A interação concluída preserva os timestamps de aproximação, entrada na região,
toque, cruzamento, afastamento e saída; valores da média em near/touch/exit;
lado de entrada e saída; distância mínima; preço e instante mais próximos;
penetração máxima; duração total e tempo dentro da região com histerese.

Eventos objetivos são emitidos somente nas transições:

```text
APPROACH_STARTED
NEAR_ENTERED
TOUCH_DETECTED
CROSS_DETECTED
MOVING_AWAY_DETECTED
INTERACTION_COMPLETED
```

O diagnóstico LIVE registra esses eventos, sem log por tick. Um futuro
consumidor pode observar `MarketStructureUpdate.events()` e congelar snapshots
pelos mesmos `timeMsc` usados pelo `TradeFlowEngine`.

## Performance e replay

Cada amostra executa trabalho O(1) em dois trackers. Cada tracker guarda apenas
a amostra anterior, candidato de aproximação, interação ativa e última interação
concluída. Não há lista crescente, stream, serialização ou leitura do relógio.

A mesma sequência de `MarketStructureSample` produz os mesmos eventos e
interações em LIVE ou em um replay futuro.

## Diagnóstico manual

```powershell
java -cp "target/classes;target/dependency/*" `
  br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.Mt5MarketStructureDiagnostics `
  --duration-seconds 30
```

Em uma observação real de 30 segundos em 22/09/2026, nenhuma interação ocorreu
com os limites padrão. O último estado foi:

```text
SMA9  FAR price=188560 average=188737,22 distance=-177,22
SMA21 FAR price=188560 average=188784,05 distance=-224,05
```

Os thresholds não foram alterados para forçar eventos.
