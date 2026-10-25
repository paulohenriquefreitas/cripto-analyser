# Delta de 500 ms e CVD por sessão

`VolumeDeltaTracker(500, 5000)` é um observador adicional do fluxo de negócios.
Ele não altera decisões de Setup91, detector de absorção, janelas FLOW de
5/10/30 segundos ou Volume Profile.

## Ingestão

`MonitoringFlowEngine.onTrade()` alimenta o tracker uma vez por negócio aceito,
após a verificação de ordem/identidade. Esse ponto é compartilhado pelo replay e
pelo serviço ao vivo. O replay injeta sua instância privada na engine.

No parser, o callback de negócios ocorre após `awaitEvent` e antes do callback
de preço `M5ReplaySession.accept()`. Isso inclui mensagens VOLUME-only que não
formam candles. Repetir o append no callback de preço duplicaria negócios.
Ao entrar em `accept`, o resultado já está disponível para regras opcionais.

O tracker usa timestamps dos eventos, sem relógio de parede nem threads próprias.
A janela é `(referência - 500 ms, referência]`; ticks com mesmo timestamp e
identidades diferentes são preservados. AggressorSide vem do mapeador existente
de flags. AMBIGUOUS permanece neutro e separado: não é uma prova de operação
direta nem é convertido em venda.

## Payload e memória

`session.volumeDelta()` retorna o último resultado imutável opcional. Os campos
`volumeDelta` dos snapshots MARKET_STATE/FLOW e do FLOW ao vivo contêm:

- `delta`: BUY menos SELL na janela móvel de 500 ms.
- `cumulativeDelta`: BUY menos SELL processado desde o início da instância;
  não expira com a janela e não representa necessariamente o pregão completo.
- `buyVolume`, `sellVolume`, `ambiguousVolume`, `events`, `processedTicks`.
- `timeMsc`, `windowMillis`, `quality`, `complete`, `cumulativeComplete`.

O buffer contém até 5.000 registros. Se exceder capacidade, o resultado da
janela recebe CAPACITY_EXCEEDED/complete=false até os registros perdidos
expirarem. O CVD ainda soma todos os volumes válidos recebidos. Volume
fracionário, zero ou fora de long gera INVALID_VOLUME; nunca é truncado.
`cumulativeComplete=false` persiste após volume inválido. Esses indicadores
descrevem buffer/validade numérica, não comprovam cobertura da captura,
classificação de agressão desconhecida ou validação do relógio.

A engine calcula mesmo quando a tela agrupa quadros. A tela recebe snapshots
amostrados com o último resultado, não um novo canal de mensagens por negócio.
`replayConnection.getVolumeDelta()` e `ChartSink.onVolumeDelta` disponibilizam o
valor sem exigir redesenho de candle; estados visuais antigos não fazem o valor
retroceder. Mensagens antigas sem esse campo continuam compatíveis.

Não há filtro de entrada operacional baseado nesse valor. A referência temporal
existente ao vivo e suas marcações de não confirmação permanecem preservadas.
