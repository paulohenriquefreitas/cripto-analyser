# Histórico compartilhado WIN contínuo

O símbolo `WIN$N` do MT5 representa o WIN contínuo por liquidez sem ajuste.
O importador mantém ticks, flags, volume, ordem e candles M5 no NDJSON schema 2.
Terminal e replay visual leem os mesmos arquivos e manifestos de integridade em
`historical-data/WIN$N/YYYY-MM-DD.ndjson`. WINV26 permanece em sua própria pasta.

Com MT5 conectado, execute na raiz do projeto:

```powershell
.\panic-scanner\scripts\import-win-history.ps1 -Days 180
```

O padrão é WIN$N. O intervalo contém 180 dias UTC encerrados, terminando ontem
UTC. Não significa seis meses de calendário. O script compila com Maven,
prepara o classpath e chama HistoricalDatasetImportCommand, que usa
mt5_price_history.py diariamente. Arquivos COMPLETE são reutilizados; falhas
são informadas por data e permitem repetir o mesmo comando. O dia UTC atual
não é importado. A exportação exige histórico disponível e candles de warm-up.

Para definir explicitamente o ativo em PowerShell, use aspas simples:

```powershell
.\panic-scanner\scripts\import-win-history.ps1 -Days 180 -Symbol 'WIN$N'
```

O contínuo troca de contrato conforme a liquidez: não é um vencimento único.
Os preços permanecem os fornecidos pelo MT5, sem reconstrução ou ajuste local.

No replay visual, escolha **WIN contínuo sem ajuste** e a data; clique CARREGAR.
No IntelliJ, Setup91TriggerResearchCommand usa os mesmos dados. Exemplo:

```text
WIN$N 2026-08-01 2026-08-31 PARTIAL_1R_ATR_TRAILING research-results/setup91-continuous-agosto
```

Use JDK 21, classpath panic-scanner e diretório de trabalho na raiz do projeto.
O diretório de relatório deve ser novo. O comando batch usa somente dados locais.
run-canonical-shadow.ps1 continua destinado à sessão de diagnóstico ao vivo.
