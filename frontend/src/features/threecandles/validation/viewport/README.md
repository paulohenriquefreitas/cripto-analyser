# Enquadramento do Replay — WIN$N, 16/10/2026

O `autoscaleInfoProvider` do componente compartilhado expandia os extremos da série para incluir todas as referências H1, independentemente do intervalo horizontal. M5 não participava dessa expansão. O enquadramento horizontal anterior reservava 60 posições futuras a partir da abertura e apenas 20 anteriores; não era um `fitContent` de todo o histórico. A escala nativa do Lightweight Charts 5.2.1 já considera somente os dados visíveis, conforme a documentação em `node_modules/lightweight-charts/dist/typings.d.ts`, em `autoscaleInfoProvider`.

A página `/three-candles` usa esses mesmos recursos nativos no gráfico dedicado `ThreeCandlesChart`: nenhuma expansão pelos níveis estruturais, escala nativa com `autoScale: true` e `scaleMargins` de 0,1 no topo e na base. Assim, os extremos visíveis ocupam aproximadamente 80% da altura útil; não há intervalo fixo em pontos. O intervalo horizontal mostra até 40 candles já liberados, com quatro posições de margem à direita. Os três pregões anteriores continuam na série para navegação. Na abertura às 09:00 ainda não existe candle fechado do dia: os últimos candles conhecidos do pregão anterior são o contexto inicial, sem fabricar o candle atual.

Carregar, trocar a data e reiniciar limpam a identidade visual antes de receber o snapshot. O primeiro snapshot com candles aplica o enquadramento. `Ajustar gráfico` restaura o intervalo próximo ao último candle liberado e a escala automática. Play, NEXT, polling, visibilidade e agrupamento não chamam o enquadramento novamente. Gestos manuais no eixo de preços podem desligar o autoscale nativo e são preservados durante o avanço. Enquanto o autoscale continuar ligado, o comportamento nativo acompanha os candles visíveis.

Todos os níveis continuam nos primitives; somente seu desenho fica fora da área quando o preço não cabe no viewport. Detectores, pivôs, agrupamento, histórico, processamento do Replay e classes Java não foram alterados. Outros consumidores do componente conservam o comportamento anterior porque a opção é desativada por padrão.

## Validação

127 testes de frontend passaram; TypeScript `tsc -b` passou. Os testes executam o componente real com a API gráfica substituída para verificar que o provider H1 não é instalado nesta página, que os níveis continuam anexados, que só candles liberados chegam ao gráfico e que fit/reset/data reativam a escala. Também verificam que o comportamento anterior continua nos outros consumidores e que o botão não envia comando ao servidor.

O navegador executou a página e o Lightweight Charts reais em Edge headless, com API interceptada usando prefixos de snapshots do Replay Java sobre histórico MT5 local. Foram exercitados o carregamento de 16/10 às 09:00, zoom horizontal e vertical manuais, NEXT, Ajustar gráfico e Reiniciar. Nenhuma exceção no navegador. Não foi usado o backend em execução nem acompanhado um pregão ao vivo.

| Momento | Menor rótulo do eixo | Maior rótulo do eixo |
|---|---:|---:|
| Inicial | 207250 | 210250 |
| Zoom manual | 206400 | 211200 |
| NEXT após zoom manual | 206400 | 211200 |
| Ajustar gráfico | 207250 | 210250 |
| Reiniciar | 207250 | 210250 |

Os rótulos são observados no canvas nativo, não um intervalo de preço imposto pelo teste. Os 40 candles conhecidos inicialmente enquadrados têm mínima 207440 e máxima 210095. As referências H1 vão de 206075 a 211750: as distantes ficam fora do eixo inicial sem comprimir os candles, permanecendo todas as nove na memória/desenho estrutural. [Métricas do navegador](browser.json).

- [Abertura legível](initial.png)
- [Zoom manual](manual.png)
- [Avanço preservando zoom manual](next-manual.png)
- [Botão Ajustar gráfico](adjusted.png)
- [Reinício restaurando enquadramento](reset.png)

Para repetir com o frontend em `localhost:5173` e o snapshot Java existente em `panic-scanner/target/threecandles-m5-validation/1000.json`, executar na raiz:

```powershell
node frontend/src/features/threecandles/validation/captureViewport.mjs
```
