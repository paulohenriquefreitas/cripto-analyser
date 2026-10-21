# WIN M5: monitoramento com Times & Trades

O gráfico mantém a aproximação de pivôs confirmados: ativa a até 100 pontos,
mantém MONITORING após tocar ou romper o nível e desativa apenas acima de 150
pontos de distância absoluta. Cada nível ativo usa o mesmo fluxo global; abrir
outro gráfico ou monitorar vários níveis não reconta negócios.

## Captura e identidade

`mt5_trade_stream.py` reutiliza `copy_ticks_*`, os filtros LAST/VOLUME e o cursor
existente `(time_msc, emitted_at_time)`. Faz warm-up de 35 segundos antes do
stream incremental. A mensagem inclui `cursorOrdinal`, `flags` e
`volumeSource=MT5_VOLUME_REAL`. O cliente Java conserva o último cursor entregue
e passa esse cursor ao próximo processo Python em uma reconexão. Ordinais novos
preservam negócios idênticos no mesmo milissegundo; ordinais entregues são ignorados.
O cursor só avança após a entrega bem-sucedida ao consumidor.

Somente `volume_real` finito e positivo entra nas somas. Não há fallback para
`volume`, tick volume, quantidade de mensagens ou contagem de negócios. Volume
inválido/ausente gera `invalid_trade`, avança a identidade da origem e marca a
janela como incompleta, sem inventar uma quantidade. A unidade exibida é volume
real informado pelo MT5; o painel não infere contratos a partir de eventos.

BUY/SELL exclusivos nas flags são a classificação da origem. Ambos, nenhum,
bits desconhecidos ou divergência entre flags e lado normalizado resultam em
`AggressorSide.AMBIGUOUS`, exibido como UNKNOWN. A direção do preço não classifica
agressão. O contrato original de `MarketTrade`, `AggressorSide` e `FlowSnapshot`
permanece compatível com os motores do scanner.

## Janelas e comparação

`TradeFlowEngine`/`RollingTradeWindow` calculam janelas `(T - janela, T]` de 5,
10 e 30 segundos. `snapshotAt` expira negócios mesmo no silêncio do mercado,
sem inserir eventos artificiais. Delta = comprador - vendedor; UNKNOWN não
entra no delta. As porcentagens exibidas usam comprador + vendedor + UNKNOWN
como denominador; sem volume, porcentagens são indisponíveis, não zero.

O deslocamento usa LAST em T menos o último LAST conhecido na fronteira
T - janela. Preços são mantidos por 60 segundos, incluindo uma âncora anterior.
Se a âncora ou o preço atual estiverem mais de 2 segundos defasados, o
deslocamento fica indisponível. Não é calculado a partir do primeiro negócio
remanescente da janela nem do horário de abertura de um candle M5.

O fluxo é mantido mesmo sem níveis ativos. Cada entrada em MONITORING conserva
a última amostra disponível anterior à ativação, com todas as métricas das três
janelas; o painel mostra delta, deslocamento e UNKNOWN pré-aproximação por nível.
A amostra é renovada numa nova ativação e preservada durante o rompimento.
Sem uma amostra anterior, o painel informa a ausência explicitamente.
O T&T também atualiza a proximidade pelo timestamp real do LAST; amostras de
preço antigas não sobrescrevem preços mais novos.

## Qualidade e alertas

O painel mostra `GOOD`, `WARMING_UP`, `DISCONNECTED`, `INVALID_VOLUME`,
`NO_TRADES`, `STALE`, `HIGH_UNKNOWN`, `PRICE_UNAVAILABLE` ou `UNVALIDATED_SYMBOL`.
GOOD exige conexão, cobertura completa da janela, volume validado, negócios e
LAST recentes (até 2 segundos), preço na fronteira e UNKNOWN de no máximo 20%.
Uma falha de consulta sinaliza indisponibilidade; a recuperação volta a exigir
cobertura da janela. Métricas de dados insuficientes permanecem descritivas.
No LIVE, o corte usa o UTC atual com proteção monotônica: negócios antigos do
warm-up, como os do último pregão num fim de semana, não aparecem como fluxo atual.

`POSSIBLE_ABSORPTION` só aparece numa janela GOOD com volume conhecido de pelo
menos 20, participação de um lado de pelo menos 70% do volume conhecido e
deslocamento absoluto do LAST de até 5 pontos. É uma descrição de fluxo
concentrado com pouco deslocamento, não uma confirmação causal de absorção.
Não há ordens, recomendações de entrada ou alteração de ranking/scoring.

## Transporte, replay e operação

Uma captura compartilhada e snapshots a cada 250 ms alimentam mensagens FLOW
no WebSocket existente `/ws/mt5/ticks`. A conexão inicial/reconexão recebe o
snapshot global mais recente. O gráfico apresenta as métricas junto ao candle
atual apenas enquanto houver níveis em MONITORING. A desconexão do navegador
invalida as métricas em cache e oculta alertas.

O replay usa os mesmos flags e `volumeReal` do arquivo histórico, observa também
eventos VOLUME sem LAST e conserva a seleção original de eventos para candles,
indicadores e scanner. Janelas usam tempo histórico e não a velocidade de
entrega. A agressão está validada para WINV26; WIN$N exibe UNVALIDATED_SYMBOL e
nunca gera alertas de agressão, seguindo a restrição do adaptador histórico
existente. Arquivos sem volume/flags confiáveis não produzem interpretações.

Reinicie o backend para carregar a integração. `mt5.trade-monitoring.enabled`
é true por padrão e pode ser configurado como false para desativar a captura
compartilhada. O Python precisa conseguir acessar o terminal MT5 instalado.
