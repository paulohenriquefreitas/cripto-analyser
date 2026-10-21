# Replay de Absorção — fase 2

**Atualização:** o [histórico M1 WINZ26 foi recuperado](absorption-m1-history.md).
A implementação atual carrega 51 candles reais e posiciona 16 suspeitas e as 5
reações. As referências abaixo à ausência total de candles descrevem a entrega
original; os resultados atuais estão na atualização e no inventário M1.

O modo opcional está em `/replay` e também no Replay de `/win-m5`, desligado
por padrão. Reutiliza `WinM5MonitoredChart` e `Mt5CandlestickChart`: não há outra
implementação de gráfico nem outro canvas paralelo de candles. A camada de
absorção é uma primitive anexada à série existente. Médias, VWAP, pivôs e o
replay operacional continuam disponíveis. Alternar o modo pausa um replay
operacional em andamento; voltar ao modo normal reutiliza sua sessão.

## Abrir no navegador

```powershell
cd frontend
npm run dev
```

Acesse `http://localhost:5173/replay`, ative **Replay de Absorção (experimental)**,
selecione a captura e use **Próximo estado**, **Estado anterior**, o cursor ou
**Reproduzir estados**. A reprodução avança um estado a cada 500 ms; não representa
a cadência de recebimento individual dos negócios. O seletor abaixo do gráfico
permite escolher marcadores já conhecidos, inclusive a bolha de uma suspeita
posteriormente invalidada. Com candles compatíveis, o clique no desenho também
seleciona o marcador e a navegação centraliza seu candle no gráfico.

O carregamento desse modo é local e não exige Java/MT5 ativos. O plugin Vite
serve apenas o arquivo fixo `experimental-absorption-capture-clock.json` em
`/absorption/replay.json`; o build incorpora esse mesmo artefato. Não existe
endpoint de leitura arbitrária de arquivos nem mudança no backend operacional.

## Referência temporal e apresentação

- A bolha amarela usa a transição `POSSÍVEL_ABSORÇÃO`, seu próprio
  `known_at_local_ms` e `reference_price`.
- A seta verde (hipótese compradora) ou vermelha (vendedora) usa o conhecimento
  de `REAÇÃO_CONFIRMADA` e `reaction.price`; não a hora da suspeita ou do fim do
  replay. Reação não é tratada como operação ou resultado financeiro.
- Cruz laranja distingue `INVALIDADA`; losango cinza, `DADOS_INSUFICIENTES`.
  A invalidação posterior não apaga a bolha anterior. Também são acessíveis as
  transições terminais de candidatos que nunca chegaram a POSSÍVEL.
- O preço é desenhado por `priceToCoordinate`, não acima/abaixo da máxima/mínima
  do candle. A posição horizontal interpola o horário dentro do intervalo M5;
  isso representa a captura registrada, não recebimento individual de cada tick.
  Uma faixa translúcida mostra a resolução UTC disponível no artefato. Eventos
  do EA conservam a faixa de segundos; o monotônico não vira um UTC artificial.
- Se a faixa UTC atravessa a borda de candles, não se escolhe um candle
  arbitrariamente. Sem horário conhecido, candle correspondente ou contrato/
  referência temporal compatíveis, não há desenho. Não existe nearest-candle,
  ajuste de três horas ou fallback para `reference_market_time_msc`.
- O cursor é um prefixo da ordem original das transições. Nenhum marcador de
  transição posterior aparece antes de avançar até ele. O painel mostra o estado
  conhecido até esse prefixo; não revela antecipadamente o estado final.
- O inventário geral e a lista de impossíveis são identificados como auditoria
  retrospectiva, separados do gráfico causal. O gráfico usa candles históricos
  completos como contexto, com aviso explícito; não simula formação intrabar,
  nem usa OHLC/indicadores futuros para detectar ou posicionar eventos.

O painel de seleção informa hipótese, horário/faixa, resolução, preço, volumes
BUY/SELL/ambíguo, delta, motivo, DOM amostrado, qualidade e `time_msc` bruto para
auditoria. Não altera o detector nem recalcula suas decisões.

## Candles e limitação real da entrega

Nas bases `historical-data`, há WINV26 e WIN$N, mas não WINZ26 com referência
de captura UTC comprovada para as sessões do JSON. Os arquivos da captura EA
contêm ticks/DOM; não foram convertidos em candles históricos completos nem
reposicionados por proximidade. Por isso o manifesto distribuído,
`frontend/public/absorption/candles.json`, declara `sessions: []` e a limitação.

**Os 17 eventos e as 5 confirmações estão carregados e navegáveis, mas nenhum
dos 22 marcadores pode ser desenhado sobre candles reais disponíveis nesta
entrega.** O gráfico permanece vazio com aviso, em vez de fabricar preços. A
camada de desenho foi testada com os 22 eventos reais e coordenadas/candles
sintéticos exclusivos dos testes; isso não é apresentado como validação sobre
histórico real. Não há candles sintéticos publicados nem selecionados por padrão.

Use **Carregar candles compatíveis** quando houver um arquivo com este contrato:

```json
{
  "schema_version": 1,
  "sessions": [{
    "capture_sha256": "SHA-256 da captura conforme o JSON do detector",
    "symbol": "WINZ26",
    "time_reference": "capture_utc",
    "timeframe_seconds": 300,
    "source": "origem do histórico e comprovação da referência temporal",
    "candles": []
  }]
}
```

Cada candle real deve conter `time` (epoch UTC em segundos, início M5), `open`,
`high`, `low`, `close`, `tickVolume`, `realVolume`. `sma9`, `sma21`, `ema9`, `vwap`
e `vwapSession` são opcionais e preservados. O frontend não inventa médias
faltantes. Exige ordem crescente, OHLC consistente e nenhuma sessão duplicada.
O arquivo identifica a captura pelo hash exato e precisa cobrir os horários
de conhecimento, não apenas os horários de mercado brutos.

A declaração `capture_utc` precisa decorrer de uma convenção comprovada do
histórico. Renomear os epochs brutos da API MT5 ou somar três horas por conta
própria não constitui comprovação. O frontend valida a declaração e os dados,
mas não consegue certificar a origem externa de um arquivo importado.

## Validação das nove capturas

O inventário reproduzível completo está em
`docs/absorption-visualization-validation.json`, incluindo cada evento, transição,
faixa UTC, preço, ordem observada, hash e motivo da impossibilidade de posicionar.

| Captura | IDs que entraram em POSSÍVEL | IDs com REAÇÃO_CONFIRMADA | Desenháveis com histórico disponível |
| --- | --- | --- | ---: |
| Python 20261015T190503150562Z | nenhum | nenhum | 0 |
| Python joint-probe-30s | 9 | nenhum | 0 |
| EA 11754437 | nenhum | nenhum | 0 |
| EA 150708125 | 2 | nenhum | 0 |
| EA 151129062 | 19, 31, 33, 56, 57, 68 | 31, 57 | 0 |
| EA 151923890 | 20, 28, 52 | 20, 28, 52 | 0 |
| EA 152888781 | nenhum | nenhum | 0 |
| EA 3944703 | 8, 9, 10, 15, 40, 41 | nenhum | 0 |
| EA 8026703 | nenhum | nenhum | 0 |
| **Total** | **17** | **5** | **0** |

O motivo dos 22 casos é o mesmo: falta de candles WINZ26 com referência
capture_utc comprovada para a respectiva captura. Não houve correspondência
forçada. As nove fontes, incluindo as três sem candidatos, foram examinadas.
As 109 invalidações e os 78 estados insuficientes também foram preservados no
carregamento e são diferenciados quando suas transições são selecionadas.

## Arquivos e testes

Arquivos de integração modificados:

- `frontend/src/features/replay/pages/ReplayPage.tsx`: modo opcional.
- `frontend/src/features/win/components/WinM5MonitoredChart.tsx`: passa a camada
  opcional ao gráfico existente.
- `frontend/src/features/win/components/Mt5CandlestickChart.tsx`: primitive,
  seleção por clique, foco e identificação do contrato no modo de absorção.
- `frontend/vite.config.ts`: serve/incorpora o relatório offline fixo.

Novos componentes/modelos: `AbsorptionReplayPanel.tsx`, `absorptionReplay.ts`
e `AbsorptionPrimitive.ts`. Novos arquivos auxiliares: manifesto público de
candles, dois testes, `auditAbsorptionReplay.mjs`, `smokeAbsorptionBrowser.mjs`
e esta documentação/relatório de validação. Nenhum detector, critério, EA,
Java, estratégia ou execução de ordens foi modificado.

```powershell
cd frontend
npm test
npm run build -- --outDir .absorption-build
node --experimental-strip-types scripts/auditAbsorptionReplay.mjs
```

Resultado: **76 testes passaram**, incluindo oito novos testes de dados/renderização.
O teste de prefixos compara todas as transições das nove capturas, garantindo
ausência de marcadores/estados futuros. A primitive foi exercitada com os 17
eventos/5 reações reais, verificando preço, coordenadas, formas, seleção, zoom
e preservação das bolhas após o desfecho. O build TypeScript/Vite passou.
Lint dos arquivos alterados: zero erros, com um aviso já existente de Fast Refresh
em `Mt5CandlestickChart.tsx` (export de `setup91Details`).

Também foi executado smoke test no Chrome headless real, via CDP local, com Vite:
modo desligado inicialmente; carregamento 9/17/5; apenas um gráfico; aviso de
ausência de histórico; navegação até POSSÍVEL e depois INVALIDADA; bolha mantida
no seletor; nenhuma exceção JavaScript. Não houve validação manual sobre candles
reais, pois estes faltam.

Para reproduzir o smoke test com Chrome isolado, Vite em 5173 e saída de build
existente, inicie Chrome headless com perfil temporário e depuração local em
9333 e execute `node scripts/smokeAbsorptionBrowser.mjs`. O script captura
`.absorption-build/absorption-missing-candles.png` apenas como evidência da tela
com limitação, sem produzir candles ou sinais.
