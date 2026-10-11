# Setas de reversao da MME9 no Replay M5

As setas usam `Observation.confirmedReversal`, calculada exclusivamente com a
MME9 de candles fechados. A direcao inicial exige duas inclinacoes consecutivas
com o mesmo sinal e deslocamento acumulado **maior que 15 pontos na propria
MME9**. Estabelecer a primeira direcao nao produz uma seta.

Uma seta aparece quando duas inclinacoes consecutivas confirmam a direcao
oposta a uma direcao estabelecida. Uma inclinacao contraria isolada nao troca o
estado. Tres janelas consecutivas sem direcao suficiente apagam a direcao
anterior; sair dessa lateralidade estabelece uma nova direcao, sem chamar isso
de reversao. Lacunas entre candles e valores indisponiveis reiniciam o estado.

Excecao curta para uma virada gradual: se o reset ocorrer apos tres inclinacoes
consecutivas contra a direcao estabelecida, essa origem fica guardada por ate
dois candles adicionais. No primeiro, a inclinacao deve continuar no mesmo
sentido e o deslocamento acumulado desde o inicio da virada deve superar 15
pontos. A seta ainda exige a confirmacao normal: duas inclinacoes consecutivas
com deslocamento maior que 15 pontos entre elas. A referencia expira se houver
uma inclinacao zero, oscilacao, deslocamento insuficiente, lacuna, ou se a
confirmacao nao chegar dentro desses dois candles. Essa excecao nao mantem uma
tendencia antiga durante uma lateralidade prolongada.

Em 13/08/2026, a subida gradual de 13:10 ate 13:25 conserva essa referencia,
mas so produz a seta no candle de 13:30, depois de seu fechamento as 13:35 UTC.

A seta fica no **segundo candle**, depois de seu fechamento. Por exemplo, a
queda nos candles de 11:00 e 11:05 de 09/09/2026 confirma a reversao no candle
de 11:05, disponivel somente a partir de 11:10 (UTC). O backend envia o instante
real de disponibilidade; o frontend nao antecipa nem calcula a reversao.

O deslocamento minimo e uma calibracao visual inicial para WIN, nao uma medida
de angulo em pixels. Pode ser ajustado em
`Ema9ReversalDetector.DEFAULT_MIN_DISPLACEMENT_POINTS` ou no construtor do
detector. Zoom e tamanho da tela nao mudam o resultado.

A classificacao nativa de um candle (`Observation.reversal`) e os candidatos
de execucao do Setup91 permanecem independentes deste filtro visual.

O candle que confirma a reversao tambem exige deslocamento entre fechamentos:
para alta, `closeAtual > closeAnterior + 15`; para baixa,
`closeAtual < closeAnterior - 15`. Igualdade nao passa. O filtro usa os numeros
TA4J, sem arredondamento intermediario, e funciona mesmo com uma posicao aberta.
Rejeitar a seta nao reinicia o detector nem adia a seta para outro candle.

Regressoes locais com os datasets reais:

```powershell
mvn -pl panic-scanner -am test `
  '-Dsetup91.replay.history=C:/Users/paulo/project/cripto-analyser/historical-data/WINV26/2026-09-09.ndjson' `
  '-Dsetup91.replay.september14=C:/Users/paulo/project/cripto-analyser/historical-data/WINV26/2026-09-14.ndjson' `
  '-Dsetup91.replay.august13=C:/Users/paulo/project/cripto-analyser/historical-data/WINV26/2026-08-13.ndjson'
```

O teste de 14/09 cobre 12:15 a 13:00, incluindo a queda isolada de 12:45 e a
retomada de 12:50. Nenhuma das duas produz uma seta de reversao.
