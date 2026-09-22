# TradeFlowEngine

Primeira versão do núcleo de fluxo de negócios do Panic Scanner. O código é
Java puro e não depende de MT5, Python, Spring, WebSocket, relógio do computador
ou persistência.

## Modelo

- `MarketTrade` representa um negócio normalizado: símbolo, `timeMsc`, preço,
  volume e lado agressor.
- `AggressorSide` aceita `BUY`, `SELL` e `AMBIGUOUS`. A normalização da fonte
  deve mapear BUY exclusivo para `BUY`, SELL exclusivo para `SELL` e BUY+SELL
  para `AMBIGUOUS`. O motor não tenta inferir ou corrigir o agressor.
- `FlowSnapshot` é uma medição imutável de uma janela.
- `TradeFlowEngine` recebe uma sequência com `onTrade` e expõe snapshots de
  1, 3, 5 e 10 segundos.

Preço e volume usam `double`, o mesmo tipo numérico entregue pelo MT5. Para o
WIN, os preços observados são pontos inteiros e `volume_real` é fornecido como
`double`; `BigDecimal` adicionaria custo e conversões sem recuperar precisão que
já não esteja na fonte. O modelo rejeita zero, valores negativos, NaN e infinito.
Snapshots vazios representam preços e métricas derivadas de preço com
`OptionalDouble.empty()`, evitando confundir ausência com preço zero.

## Semântica temporal

Para o negócio mais recente no instante `T`, uma janela de duração `W` contém:

```text
(T - W, T]
```

O negócio exatamente em `T - W` é removido. Negócios com o mesmo `timeMsc` são
aceitos, preservados na ordem de chegada e nunca deduplicados. Dois eventos
completamente iguais continuam sendo dois negócios.

Os eventos devem chegar em ordem não decrescente de `timeMsc`. Um evento com
timestamp menor que o último é rejeitado com `IllegalArgumentException`, antes
de modificar qualquer janela. Uma instância aceita apenas um símbolo; outro
símbolo também é rejeitado até que `reset()` seja chamado.

O timestamp do evento é a única referência temporal. A velocidade de entrega
em LIVE ou REPLAY não altera o resultado.

## Métricas

```text
totalVolume     = buyVolume + sellVolume + ambiguousVolume
knownVolume     = buyVolume + sellVolume
knownDelta      = buyVolume - sellVolume
buyShare        = buyVolume / knownVolume
sellShare       = sellVolume / knownVolume
tradesPerSecond = tradeCount / duração nominal em segundos
contractsPerSecond = totalVolume / duração nominal em segundos
priceChange     = lastPrice - firstPrice
priceRange      = maxPrice - minPrice
priceVelocity   = priceChange / duração nominal em segundos
```

`AMBIGUOUS` entra no volume total e não entra no volume conhecido nem no Delta.
Quando `knownVolume` é zero, ambos os shares são zero. Atividade e velocidade
usam a duração nominal da janela mesmo quando os negócios ocupam somente parte
dela, permitindo comparar snapshots entre instantes.

O motor mede e não produz CVD, absorção, exaustão, momentum, sinais, scores ou
qualquer interpretação estratégica.

## Estrutura incremental

Cada janela mantém:

- uma fila temporal de negócios;
- acumuladores BUY, SELL e AMBIGUOUS;
- uma fila monotônica para preço mínimo;
- uma fila monotônica para preço máximo.

Cada negócio entra uma vez e sai uma vez de cada estrutura da janela. Atualizar
as quatro janelas tem custo amortizado constante por janela e memória limitada
aos negócios dos últimos dez segundos. Criar um snapshot consulta acumuladores
e extremidades das filas, sem percorrer todos os negócios.

## Uso

```java
TradeFlowEngine engine = new TradeFlowEngine();
engine.onTrade(new MarketTrade(
        "WINV26",
        timeMsc,
        price,
        volumeReal,
        AggressorSide.BUY));

FlowSnapshot fiveSeconds = engine.snapshot(TradeFlowEngine.FIVE_SECONDS);
```

LIVE e REPLAY devem normalizar suas entradas para o mesmo `MarketTrade`. O motor
não recebe nem conhece a origem do evento.
