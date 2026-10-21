# Recuperação de WINZ26 M1 para o Replay de Absorção

O terminal real **ClearInvestimentos-CLEAR**, conectado, forneceu histórico M1
do contrato **WINZ26**. Foram exportados **51 candles reais** para seis sessões,
sem minutos ausentes nos intervalos exportados. Nenhum preço foi sintetizado,
nenhum contrato foi substituído e nenhuma ordem foi enviada.

O gráfico existente agora aceita M1 e M5 neste modo. Com a base recuperada,
ficam posicionáveis **16 das 17 possíveis absorções e todas as 5 reações**.
O evento restante é a suspeita #9 da captura Python `joint-probe-30s`, que não
dispõe de evidência temporal nativa da própria sessão para normalizar o histórico.
Ela continua navegável, mas sem desenho sobre candle.

## Períodos reais e cobertura das sete capturas do EA

Períodos obtidos dos próprios `time_gmt_before/after` dos registros de consulta.
O fim é exclusivo e inclui o segundo final devido à resolução de segundos do EA;
não é precisão individual por tick nem o horário de encerramento do replay.

| Captura EA | Início UTC | Fim UTC exclusivo | Candles M1 exportados | Lacunas M1 | Suspeitas/reações posicionáveis |
| --- | --- | --- | ---: | --- | --- |
| 11754437 | 2026-10-19 21:26:03 | 2026-10-19 21:26:34 | 0 | sem histórico nas faixas consultadas | 0 / 0 |
| 150708125 | 2026-10-16 20:24:00 | 2026-10-16 20:24:31 | 1 | nenhuma | 1 / 0 |
| 151129062 | 2026-10-16 20:31:01 | 2026-10-16 20:36:02 | 6 | nenhuma | 6 / 2 |
| 151923890 | 2026-10-16 20:44:16 | 2026-10-16 20:59:17 | 16 | nenhuma | 3 / 3 |
| 152888781 | 2026-10-16 21:00:21 | 2026-10-16 21:10:42 | 11 | nenhuma | 0 / 0 |
| 3944703 | 2026-10-19 19:15:53 | 2026-10-19 19:30:54 | 16 | nenhuma | 6 / 0 |
| 8026703 | 2026-10-19 20:23:55 | 2026-10-19 20:23:56 | 1 | nenhuma | 0 / 0 |

A 11754437 tem apenas um quote, sem negócio válido para estabelecer a convenção
nativa do relógio. A consulta direta pelo UTC e a consulta na faixa bruta desse
quote retornaram zero candles. Ela já não tinha evento de absorção; não foi
criado um candle para preencher esse período.

Também foram inspecionadas as duas capturas Python do replay. A captura somente
DOM não contém negócios nem candidatos. A conjunta de 15/10 abrange
20:47:15.280 a 20:47:45.267 UTC, possui uma suspeita (#9), e a consulta na faixa
bruta retornou dois candles M1. Eles ficaram preservados na auditoria, mas não
foram normalizados/associados por um offset emprestado de outra sessão.

## Timestamps: evidência usada, sem deslocamento arbitrário

As primeiras consultas `copy_rates_range(WINZ26, TIMEFRAME_M1, ...)` utilizando
diretamente os epochs UTC das capturas retornaram zero candles. Na faixa do
`time_msc` bruto, a API devolveu candles com preços correspondentes à gravação.
Portanto, rotular diretamente o epoch retornado pela API como UTC de captura
produziria uma associação incorreta nesta fonte.

A normalização das **seis sessões nativas exportadas** exige, na própria sessão:

1. Diferença constante entre todos os pares `time_gmt_*` e `time_local_*` dos
   registros book/tick; foram observados 10.800 segundos nessas sessões.
2. Registros bem-sucedidos de ticks novos com `TimeCurrent == TimeLocal` e o
   segundo do último `time_msc` do lote igual a esses dois valores. Os relógios
   antes/depois da consulta precisam permanecer no mesmo segundo.
3. Mesmos servidor e pasta do terminal entre o arquivo original e o terminal
   consultado, contrato exato WINZ26 e hash original igual ao do detector.

Essas observações estabelecem que, nas sessões verificadas, o datetime bruto do
servidor/tick compartilha a representação de horário de parede de Windows local,
enquanto `TimeGMT` fornece a representação UTC de captura. A conversão dos
**candles**, documentada em `clock_mapping`, usa essa diferença observada. Não
usa mediana de recepção, ajuste de latência ou proximidade de preços para escolher
um horário. O valor de 10.800 s não é uma constante aplicada a qualquer arquivo:
sem os registros nativos requeridos, a exportação fica bloqueada.

Exemplo na sessão 150708125: `TimeGMT=1792182240`, `TimeLocal=1792171440`,
`TimeCurrent=1792171440` e último tick `1792171440925`. O candle bruto
`time=1792171440` corresponde a `20:24 UTC` nessa convenção. O export mantém
`rawTime=1792171440` e `time=1792182240`. OHLC e volumes permanecem os da API.
O `time_msc` original dos negócios e todos os estados do detector ficam intactos.

Há 213 âncoras nativas na 150708125, 4.047 na 151129062, 8.331 na 151923890,
4.298 na 152888781, 11.789 na 3944703 e 3 na 8026703. As âncoras têm resolução
de segundos; não são calibração de relógio do feed nem comprovação de atraso
máximo. Não se reivindica UTC externo/NTP validado. Não se converte o contador
monotônico em fração artificial de UTC.

## Conferência de preços e qualidade

Foram comparados minutos inteiros interiores às capturas, usando LASTs positivos
com flag LAST, preservando ordem e duplicatas. Os minutos parciais inicial/final
não são tratados como OHLC completo da captura.

**40 dos 41 minutos comparáveis** tiveram OHLC exatamente igual ao histórico M1.
Na sessão 151129062, minuto normalizado **2026-10-16 20:32 UTC**, a abertura do
histórico é **209935**, enquanto a abertura dos LAST gravados é **209930**.
Máxima 209985, mínima 209890 e fechamento 209960 coincidem. A causa da diferença
de abertura não foi estabelecida: a captura não certifica todos os negócios e o
histórico pode diferir do fluxo observado. Nenhum dado foi corrigido para produzir
igualdade; a divergência é registrada e aparece em um aviso na tela.

O histórico M1 completo não remove lacunas/desconexões dos arquivos de captura.
As falhas e bloqueios do detector continuam preservados. Candles são contexto
retrospectivo; não recalculam hipóteses nem antecipam os estados. Marcadores usam
o conhecimento da captura, não o horário de mercado bruto nem o OHLC futuro.

## Artefatos e integração

- `scripts/mt5_absorption_m1_history.py`: consulta read-only com a integração Python
  MetaTrader5 existente, reutilizando `utc()`/`candle()` de `mt5_price_history.py`.
  Só chama metadados, seleção do símbolo quando necessária e consulta de rates;
  não há chamada de negociação.
- `dom-captures/WINZ26-m1-history-investigation.json`: períodos, hashes, consultas
  UTC/brutas, linhas originais retornadas, evidências de relógio, comparações,
  cobertura e motivos de recusa.
- `dom-captures/WINZ26-m1-replay-candles.json`: 51 candles M1 normalizados, com
  rawTime e proveniência preservados.
- `frontend/public/absorption/candles.json`: o mesmo bundle, carregado
  automaticamente no replay; não é preciso importar manualmente.
- `docs/absorption-m1-positioning-validation.json`: inventário dos 17+5 estados,
  indicando 21 posições válidas e a única recusa, com motivo e timestamps.

O frontend aceita agora `timeframe_seconds: 60` ou `300`, sem arredondar M1 para
M5. O modo mostra M1 e conserva a camada de absorção no mesmo gráfico. O painel
e os pivôs específicos de monitoramento M5 ficam fora apenas deste modo, para
não interpretar candles M1 como estrutura M5. O gráfico operacional não mudou.

Para atualizar a investigação sem sobrescrever arquivos de auditoria:

```powershell
python -B panic-scanner/scripts/mt5_absorption_m1_history.py --report panic-scanner/dom-captures/nova-investigacao-m1.json --candles panic-scanner/dom-captures/novos-candles-m1.json
```

O terminal Clear precisa estar conectado. Os intervalos e contratos vêm das
capturas originais vinculadas ao relatório; não há login alternativo nem ordem.
A consulta não executa o detector e não gera novos sinais.

## Abrir e validar

```powershell
cd frontend
npm run dev
```

Abra `http://localhost:5173/replay`, ative **Replay de Absorção**, escolha uma
captura com dados (por exemplo, `151129062`) e avance os estados. Aparecem
automaticamente os candles M1 e os marcadores conhecidos até o cursor. A seleção
mostra **16 suspeitas e 5 reações posicionáveis** no total; a captura Python #9
continua com aviso. Para ver a primeira reação dessa sessão, navegue até o
evento #31. A sessão 151923890 também tem três reações confirmadas.

Testes executados:

```powershell
python -B -m unittest discover -s panic-scanner/scripts -p test_mt5_absorption_m1_history.py -v
cd frontend
npm test
npm run build -- --outDir .absorption-build
node --experimental-strip-types scripts/auditAbsorptionReplay.mjs
```

**5 testes Python e 77 testes frontend passaram**, incluindo o posicionamento
nos candles reais recuperados, sem fallback de mercado/horário, preservação de
rawTime, recusa sem evidência nativa e conversão com virada de data.
Build TypeScript/Vite passou. Lint: zero erros; permanece o aviso anterior de
Fast Refresh no export `setup91Details` do gráfico compartilhado.

O smoke test em Chrome headless real passou com o histórico recuperado: uma
única instância do gráfico, dados M1 carregados, contagem 16/5, navegação causal
até uma reação confirmada real, e nenhuma exceção JavaScript. O resultado foi
também inspecionado visualmente. Os cinco estados confirmados não são operações
lucrativas nem validação de absorção como causa da reação.

Não foram alterados detector, regras de absorção, EA, Java operacional,
estratégias ou execução de ordens.
