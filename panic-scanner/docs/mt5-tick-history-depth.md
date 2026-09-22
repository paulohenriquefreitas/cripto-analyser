# Profundidade histórica de ticks — WINV26

Diagnóstico executado em 21/09/2026 com MetaTrader5 Python 5.0.6180 e janelas
de cinco minutos. O script faz consultas de descoberta de apenas um tick e só
carrega a pequena janela quando encontra um negócio. Não houve consulta de
meses inteiros nem alteração de configuração do terminal.

## Limites encontrados

- Último tick disponível: `2026-09-21T18:31:52.785Z`.
- Primeiro tick de qualquer tipo encontrado desde 21/09/2024:
  `2025-01-14T06:00:03.275Z`.
- Primeiro **trade** encontrado desde 21/09/2024:
  `2026-04-15T12:10:04.837Z`.
- Dia imediatamente anterior, 14/04/2026: nenhum trade encontrado.
- `symbol_info.start_time`: zero, portanto a API não informou a data oficial
  de início de negociação.
- Expiração informada: epoch `1792016100`, equivalente a
  `2026-10-14T22:15:00Z`.

O primeiro tick de qualquer tipo em 2025 não significa negociação do contrato:
o histórico contém mensagens informativas/pré-mercado sem LAST/VOLUME. Para
backtest de Times & Trades, o marco relevante é o primeiro trade de abril/2026.

## Amostras

| Distância | Data efetiva | Eventos | Trades | BUY | SELL | BUY+SELL | Lado ausente |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 dia | 21/09/2026 | 79.646 | 78.147 | 39.145 | 36.297 | 2.705 | 0 |
| 7 dias | 14/09/2026 | 62.392 | 60.959 | 30.676 | 28.634 | 1.649 | 0 |
| 30 dias | 24/08/2026 | 76.690 | 75.193 | 34.417 | 36.846 | 3.930 | 0 |
| 60 dias | 23/07/2026 | 1.374 | 434 | 197 | 146 | 91 | 0 |
| 90 dias | 23/06/2026 | 466 | 93 | 35 | 46 | 12 | 0 |
| 180 dias | — | 0 | 0 | — | — | — | — |
| 365 dias | — | 0 | 0 | — | — | — | — |
| 540 dias | — | 0 | 0 | — | — | — | — |
| 730 dias | — | 0 | 0 | — | — | — | — |
| primeiro trade | 15/04/2026 | 80 | 4 | 4 | 0 | 0 | 0 |

Todos os trades das amostras com dados possuíam `volume_real` positivo e
inteiro. BUY/SELL continuaram presentes nas amostras antigas. BUY+SELL continua
sendo uma categoria ambígua e não foi artificialmente repartida.

As diferenças grandes de quantidade entre junho/julho e agosto/setembro são
compatíveis com aumento de liquidez conforme o vencimento se aproxima. Elas não
provam perda de histórico. A ausência anterior a 15/04 é indistinguível, pela
API isoladamente, entre “o contrato ainda não negociava” e “o servidor não
retém esses negócios”. O conjunto de evidências — vencimento específico V26,
primeiros poucos negócios em abril e liquidez crescente — favorece fortemente
a explicação de idade/liquidez do contrato, não retenção curta da Clear.

## Símbolos relacionados encontrados no servidor

Contínuos por liquidez:

- `WIN$` — ajuste proporcional;
- `WIN$D` — ajuste por diferença;
- `WIN$N` — sem ajustes.

Contínuos por vencimento:

- `WIN@` — ajuste proporcional;
- `WIN@D` — ajuste por diferença;
- `WIN@N` — sem ajustes.

Contratos específicos expostos no momento:

- `WINV26`, `WINZ26`, `WING27`, `WINM27`, `WINQ27`, `WINV27`,
  `WING28`, `WINJ28`, `WINM28`, `WINQ28`.

Os nomes e descrições acima vieram de `symbols_get`; nenhum padrão foi presumido.
O probe não validou nesta etapa profundidade, ajustes ou fidelidade Times &
Trades dos símbolos contínuos. Eles são candidatos reais para a próxima
investigação, não uma decisão de arquitetura já tomada.

## Conclusão para backtests

Há aproximadamente cinco meses de negócios recuperáveis do contrato WINV26
entre 15/04 e 21/09/2026. Os dados antigos mantêm LAST, VOLUME, BUY, SELL e
`volume_real`, portanto são suficientes para replay de Times & Trades dentro
desse período, preservando BUY+SELL como ambíguo.

Backtests de vários vencimentos não devem usar apenas WINV26. As opções são:

1. investigar a profundidade e semântica dos contínuos reais `WIN$*`/`WIN@*`,
   especialmente como os ajustes afetam preço e se o histórico de ticks mantém
   volume/lado agressor; ou
2. armazenar e unir contratos sucessivos com uma política explícita de rollover.

O futuro Market Data deve produzir o mesmo tipo de evento para LIVE e REPLAY;
o `TradeFlowEngine` não deve saber se a origem foi cursor ao vivo ou leitura
histórica. Essa camada não foi implementada nesta etapa.

## Uso

Probe progressivo:

```powershell
cd C:\Users\paulo\project\cripto-analyser\panic-scanner\scripts
python .\mt5_tick_history_probe.py --minutes 5
```

Data específica; finais de semana procuram o próximo pregão em até quatro dias
e a saída informa separadamente a data solicitada e a data efetivamente usada:

```powershell
python .\mt5_tick_history_probe.py --date 2026-08-20 --minutes 5
```

“SEM DADOS” é deliberadamente exibido como ambíguo entre contrato não negociado
e histórico indisponível. O script não afirma conhecer calendário completo B3.
