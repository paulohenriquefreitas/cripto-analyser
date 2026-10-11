# Relatorio de reversoes da MME9 no shell

Na raiz do projeto, em PowerShell:

```powershell
.\panic-scanner\scripts\test-ema9-reversals.ps1
```

O padrao e `WIN$N`, os ultimos seis meses de calendario ate ontem em UTC,
com ATR dos fechamentos desligado (`-AtrFilter Off`).
O script compila o codigo atual e usa `Setup91.Observation.confirmedReversal`,
o mesmo campo que produz as setas no Replay M5. Conta alta e baixa por dia,
por mes e no periodo completo. O estado reinicia por dia com o aquecimento do
dataset; os candles do aquecimento nao entram na contagem. O ultimo candle
sem fechamento confirmado por um novo LAST tambem nao entra. Os filtros de
entrada, stop e posicao nao limitam esta contagem.

A seta confirmada precisa de fechamento maior que o anterior +15 para alta,
ou menor que o anterior -15 para baixa. Esse limite e registrado em `run.json`
como `minDirectionalCloseDisplacementPoints`. O deslocamento da MME9 continua
sendo um filtro separado. A comparacao permite mudancas nesses filtros entre
as execucoes, mantendo o mesmo periodo e os mesmos arquivos de mercado.

Para uma comparacao reproduzivel, fixe as datas e o simbolo. Exemplo de cerca de
seis meses usando a cobertura local existente em 01/10/2026:

```powershell
.\panic-scanner\scripts\test-ema9-reversals.ps1 `
  -Symbol 'WIN$N' -StartDate 2026-04-03 -EndDate 2026-09-30 `
  -AtrFilter Off -OutputDirectory research-results/ema9-base
```

Depois de alterar o detector, rode com o mesmo periodo e uma pasta nova:

```powershell
.\panic-scanner\scripts\test-ema9-reversals.ps1 `
  -Symbol 'WIN$N' -StartDate 2026-04-03 -EndDate 2026-09-30 `
  -AtrFilter Off -OutputDirectory research-results/ema9-novo `
  -CompareWith research-results/ema9-base
```

O comparador mostra o total anterior/atual, a diferenca por dia e quantas setas
foram adicionadas ou removidas. Compara tambem os candles e os sentidos: dois
relatorios podem ter a mesma contagem e setas em momentos diferentes.
Para comparar arquivos existentes sem rodar novamente:

```powershell
.\panic-scanner\scripts\compare-ema9-reversals.ps1 `
  -Baseline research-results/ema9-base -Current research-results/ema9-novo
```

Cada execucao cria uma pasta nova em `research-results/ema9-reversals` quando
`-OutputDirectory` nao e informado. Os resultados anteriores sao preservados.

| Arquivo | Conteudo |
| --- | --- |
| `report.txt` | O mesmo relatorio mostrado no console |
| `daily.csv` | Contagens diarias, status e SHA256 do dataset |
| `events.csv` | Cada reversao: candle, disponibilidade, sentido e valores da MME9 |
| `run.json` | Periodo, simbolo, limite de deslocamento e SHA256 do codigo compilado |
| `comparison.txt` / `comparison.csv` | Resultado da comparacao, quando solicitada |
| `event-changes.csv` | Candles/sentidos adicionados ou removidos |

`OK` significa dataset COMPLETE com integridade validada. Um dia COMPLETE sem
ticks e um zero valido. `MISSING` e `FAILED` nao sao tratados como zeros validos:
o relatorio fica PARCIAL e o script retorna codigo 2. Codigo 0 indica relatorio
completo; codigo 1 indica falha do comando. O script le os arquivos locais e nao
faz importacoes do MT5. Atualmente o inicio local em 03/04 deixa os dias 01/04 e
02/04 ausentes se o periodo padrao de seis meses for usado em 01/10.

A comparacao exige o mesmo simbolo, periodo e politica de contagem. Dias com
dados ausentes, invalidos ou SHA256 diferente sao explicitamente excluidos;
nessa situacao a comparacao e parcial. O codigo pode mudar entre as execucoes.

Parametros adicionais: `-Months`, `-HistoryTemplate` (com `{symbol}` e `{date}`)
e `-SkipBuild`. Use `-SkipBuild` somente se as classes e o classpath ja estiverem
atualizados; para comparar uma alteracao do detector, mantenha o build normal.

Para exatamente 180 dias corridos, use 04/04/2026 a 30/09/2026.
O ATR dos fechamentos foi desativado: somente `-AtrFilter Off` e aceito.
A reversao conserva o limite estrito de fechamento >15 no seu sentido.
ATR14 e aplicado somente ao risco da entrada: piso=max(130,1.0*ATR14),
teto=min(550,4.0*ATR14). O relatorio de reversoes nao mede operacoes aceitas;
para auditar a frequencia, use Setup91RiskCalibrationCommand, que publica
contagens diarias e media por dia com LASTs, incluindo dias sem entradas.
Dias invalidos sao listados e nunca tratados como zero; cobertura parcial
retorna codigo 2.
