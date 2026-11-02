# CHOP isolado — WINZ26, 30/10/2026

Implementado na branch `feat/three-candles-foundation`, Java 21/Maven, usando **`org.ta4j.core.indicators.ChopIndicator` do TA4J 0.22.6**, já declarado em `ta4j-engine/pom.xml`. Foram inspecionados o POM e o source JAR local. Nenhuma dependência foi atualizada. Não há integração do CHOP com ALVO/NEGA/TESTA/E, filtros operacionais ou ordens.

## Implementação e políticas

`ExperimentalChopStudy` é um observador separado. Os adaptadores da página acrescentam `chop` à resposta; os motores e o detector recebem os mesmos dados de antes. `ExperimentalChopPanel` é um painel inferior próprio, ativado por **Mostrar CHOP (experimental)**, inicialmente desligado. CHOP(14) aparece por padrão; 7 e 21 são comparações independentes. Nenhuma cor de candle é modificada. As linhas de 38,2/61,8 são exploratórias.

O cálculo nativo usa ATR(1), equivalente ao TR de cada barra, somado na janela, e a amplitude máxima–mínima da mesma janela. A guarda exige **n+1 barras conhecidas** para que todos os n TR tenham fechamento anterior conhecido. Histórico insuficiente, amplitude zero e resultado/logaritmo não finito produzem `value=null`, `regime=UNAVAILABLE` e motivo próprio; não viram zero. Não há limitação artificial do resultado a 0–100.

Política principal: **histórico M5 anterior disponível contínuo**, incluindo TR entre sessões. Isso evita reiniciar o indicador na abertura, mas incorpora a influência do gap. Não há preenchimento de candles ausentes. O teste também exporta uma política **SESSION_ONLY_N_PLUS_1**, sem carregar a sessão anterior, para comparação. Essa escolha de aquecimento não é uma regra validada para WIN.

Snapshot:

* `confirmed[]`: somente M5 fechados; cada ponto é imutável após confirmação.
* `provisional`: leitura atual com a parcial M5 conhecida; nunca é inserida como confirmada antes do fechamento.
* `readings[]`: período, valor, delta, inclinação, faixa e status para 7/14/21.
* `deltaBasis=PREVIOUS_CONFIRMED_M5`: tanto confirmado quanto provisório são comparados ao M5 confirmado anterior, não a uma parcial anterior.
* `Point`: OHLC disponível, horário do candle e de disponibilidade, deslocamento do fechamento e sua direção separados do CHOP, extremos conhecidos da sessão.

CHOP <38,2 → `DIRECTIONAL`; >61,8 → `CHOPPY`; inclusive os limites → `INTERMEDIATE`. “Direcional” não informa alta ou baixa. “Irregular” pode coexistir com deslocamento grande. O indicador mede a relação entre percurso/ranges e amplitude da janela.

## Dados nativos e cobertura

Nova consulta do mesmo contrato ao MT5: **114 M5**, de 09:00 a 18:30, e **566 M1**. Aquecimento anterior: 565 M5 disponíveis de 23, 26, 27, 28 e 29/10. Horários mantêm a convenção civil do feed já adotada na página; nenhum deslocamento adicional foi aplicado. Preços em pontos WIN.

As disponibilidades históricas são os fechamentos nominais de M1/M5, não horários comprovados de chegada ao computador. O CSV serializa o epoch usado pelo projeto em formato ISO com `Z`, mas deve ser interpretado conforme essa convenção **MT5_WALL_CLOCK**, sem uma segunda conversão automática para Brasília. CHOP e seus deltas são adimensionais.

Os 113 M5 de 09:00–18:25 possuem os cinco M1 necessários: **565 observações progressivas**. O último M5, 18:30, é uma barra plana de leilão em 170.350, com apenas M1 18:31 no arquivo; faltam 18:30/18:32/18:33/18:34. Ele é mantido como **M5 oficial fechado disponível às 18:35**, sem inventar parcial ou caminho intrabar. Assim, o replay do experimento percorre 565 passos M1 e mais um fechamento oficial de fallback. A ausência do primeiro/último M1 impede reproduzir progressivamente essa barra pelo construtor compartilhado; esse construtor não foi alterado.

Esse fallback pertence à rotina de validação/exportação do experimento, não é uma nova política do Replay M1 da aplicação. A interface mantém suas verificações existentes e pode recusar uma carga progressiva que inclua essa barra sem cobertura M1; o M5 fechado continua sendo uma observação válida do CHOP.

Manifestos: [fontes e hashes](../../frontend/src/features/threecandles/validation/chop/sources.json), [verificação e cobertura](../../frontend/src/features/threecandles/validation/chop/verification.json). Dados novos em `src/test/resources/threecandles/chop/`; as fixtures anteriores do detector não foram alteradas.

## Replay M5 confirmado

Valores abaixo disponíveis no fechamento indicado; não no início do candle. Os CSV contêm todos os OHLC, deltas, direção do preço e extremos conhecidos.

| Disponível | CHOP(7) | CHOP(14) | CHOP(21) | Leitura dos dados |
|---|---:|---:|---:|---|
| 09:05 | 46,19 | 46,37 | 46,53 | Primeiro M5 de queda; todos intermediários |
| 09:15 | 43,04 | 40,20 | 40,89 | Queda da abertura ainda intermediária |
| 09:45 | 44,08 | 37,87 | 37,56 | 14/21 entram primeiro na faixa direcional |
| 09:50 | 29,23 | 32,88 | 32,94 | Os três direcionais; fechamento 169.170 |
| 10:55 | 38,03 | 42,68 | 48,39 | 7 direcional durante a alta anterior ao topo |
| 11:00 | 37,78 | 43,07 | 47,38 | Primeiro fechamento após topo 171.170; preço fecha 170.630 |
| 11:15 | 58,07 | 45,68 | 48,81 | Queda alcança 170.115; todos intermediários |
| 11:20 | 55,53 | 45,73 | 48,52 | M5 11:15 fez 170.055 e fechou 170.190 |
| 11:25 | 54,74 | 45,90 | 48,29 | Fechamento 170.155, antes da nova penetração |
| 11:30 | 50,11 | 50,92 | 49,99 | M5 11:25 fez 169.880 e recuperou para fechar 170.525 |
| 11:40 | 70,06 | 56,67 | 50,82 | 7 irregular; janela recente mistura movimentos opostos |
| 11:45 | 58,30 | 62,11 | 51,72 | 14 irregular apesar do M5 de alta de aproximadamente 390 pontos |
| 12:10 | 32,34 | 51,78 | 55,62 | 7 volta a direcional durante a queda; fecha 169.420 |
| 12:15 | 27,28 | 46,76 | 52,50 | Queda segue até fechamento 169.365; 14/21 intermediários |
| 16:30 | 64,82 | 64,57 | 54,38 | Oscilação 16:00–16:30: faixa de 575 pontos e deslocamento líquido de +40 |
| 18:35 | 54,99 | 60,71 | 58,99 | Última barra oficial, fallback fechado; todos intermediários |

### Atrasos observados, sem atribuir direção ao CHOP

1. **Abertura:** primeira classificação direcional de 14/21 às 09:45 e de 7 às 09:50. São 45/50 minutos desde 09:00, ou 40/45 minutos desde o primeiro M5 fechado às 09:05. Uma parte substancial da queda já ocorreu. Logo, não identificou imediatamente a primeira pernada.
2. **Mudança após a alta:** 7 sai da faixa direcional às 11:05, cinco minutos depois do fechamento de baixa das 11:00. 14/21 já estavam intermediários; não existe novo cruzamento equivalente. O menor período não é sempre o primeiro, como demonstra a abertura.
3. **Recuperação 11:25–11:30:** os três permanecem intermediários. Não há “sinal de alta atrasado”: CHOP não detecta sentido. As próximas mudanças são 7 para irregular às 11:40 e 14 para irregular às 11:45, 10/15 minutos após o fechamento de recuperação às 11:30. Esses intervalos são atraso de mudança de faixa, não detecção de reversão.
4. **Saída da irregularidade:** 7 passa de irregular às 11:40 para intermediário às 11:45 e direcional às 12:10. 14 fica irregular às 11:45/11:50, intermediário às 11:55, irregular novamente às 12:00 e intermediário às 12:05. Mostra oscilação perto do limite. Comparada ao primeiro fechamento de queda às 11:50, a classificação direcional de 7 às 12:10 tem 20 minutos de intervalo. 14/21 não classificam esse trecho como direcional até 12:15.

Não existe um único atraso universal: ele depende do ponto de referência, do período, dos extremos que saem da janela e do regime anterior. Não foram calculados atrasos em relação a pivôs conhecidos apenas posteriormente.

## Provisório M1 versus confirmado

| Disponível | Situação do M5 11:25 | CHOP(7) | CHOP(14) | CHOP(21) |
|---|---|---:|---:|---:|
| 11:27 | Parcial: mínima 169.880; último preço 169.965 | 42,85 | 48,45 | 48,41 |
| 11:28 | Parcial: recuperação; último preço 170.250 | 43,80 | 48,76 | 48,61 |
| 11:30 | Confirmado: fecha 170.525 | 50,11 | 50,92 | 49,99 |

Cada observação usa somente minutos já fechados. A mínima do M1 11:26 fica conhecida às 11:27; não é atribuída um minuto antes. Não se presume ordem de máxima/mínima dentro de um mesmo M1. O fechamento M5 de 11:30 não substitui os registros provisórios anteriores do CSV.

Nos **452 instantes provisórios** por período:

| Período | Parciais com classe diferente do fechamento final do seu M5 | M5 que tiveram alguma dessas diferenças |
|---|---:|---:|
| 7 | 40 | 18 |
| 14 | 19 | 7 |
| 21 | 6 | 2 |

Essa comparação posterior mede a instabilidade da leitura provisória, não é usada para reescrever a classificação passada ou escolher operações.

## Comparação dos períodos e outros pregões

Classificações dos **114 M5 fechados de 30/10**:

| Período | Direcional | Intermediário | Irregular | Mudanças de faixa |
|---|---:|---:|---:|---:|
| 7 | 22 | 81 | 11 | 28 |
| 14 | 14 | 91 | 9 | 14 |
| 21 | 18 | 96 | 0 | 6 |

O 7 responde mais às oscilações locais e apresenta mais mudanças provisórias. O 21 é mais estável, mas neste dia nunca ultrapassa 61,8, mesmo em trechos com movimentos opostos. O 14 oferece uma referência visual intermediária; isso não demonstra superioridade preditiva.

Entre 16:00 e 16:30, os seis fechamentos são 169.845 / 170.020 / 169.965 / 169.670 / 169.770 / 169.855. A abertura do trecho é 169.815, máxima 170.115 e mínima 169.540. O pequeno deslocamento líquido (+40) comparado à faixa (575) fornece um caso concreto de oscilação com pouca direção líquida; 7 e 14 estão acima de 61,8 às 16:30. A faixa ainda é ampla: “lateral” não significa baixa volatilidade.

No **29/10**, também WINZ26, com 113 M5 disponíveis:

| Período | Direcional | Intermediário | Irregular |
|---|---:|---:|---:|
| 7 | 10 | 67 | 36 |
| 14 | 0 | 87 | 26 |
| 21 | 1 | 90 | 22 |

O comportamento muda substancialmente. A classificação baixa do 7 em certos trechos não é reproduzida pelos períodos maiores. O CSV adicional contém também 23/26/27/28; no início do primeiro dia carregado há valores indisponíveis por aquecimento. Esses dados não constituem uma validação fora da amostra ou rotulagem humana de regimes.

**Sensibilidade à inicialização:** sem aquecimento anterior, o primeiro valor válido surge às 09:40/10:15/10:50 para 7/14/21. A primeira faixa direcional nessa política surge às 09:50/10:15/13:50. Isso é uma diferença de cobertura e inicialização, não motivo para escolher retrospectivamente a política que melhor acompanha este dia.

### Utilidade e limites

Para a próxima avaliação visual, faz sentido manter **14 como referência e 7 como comparação de sensibilidade local**, com 21 como contexto mais lento. Não foi escolhido um “melhor parâmetro” operacional. O CHOP pode caracterizar irregularidade e eficiência do percurso, mas **não separa sozinho correção curta, reversão e lateralização sem direção**. Janelas que abrangem duas pernadas contrárias podem ter CHOP alto durante uma recuperação rápida; 11:45 é um exemplo concreto.

Sem comparação independente com regiões/estrutura e sem rotulagem de outros pregões, não há base para transformar os limites exploratórios em bloqueio. Não houve integração ao detector nem cálculo financeiro.

## Artefatos, testes e capturas

* [CSV integral de 30/10](../../frontend/src/features/threecandles/validation/chop/WINZ26-2026-10-30.csv): política principal em cada M1 disponível/fechamento e comparação reiniciada por sessão nos fechamentos. Cada período ocupa uma linha; ausências são células vazias com status explícito.
* [CSV das sessões anteriores](../../frontend/src/features/threecandles/validation/chop/WINZ26-previous-sessions.csv).
* [Transições e estatísticas reproduzíveis](../../frontend/src/features/threecandles/validation/chop/analysis.json).
* Capturas reais React/canvas: [queda direcional 09:50](../../frontend/src/features/threecandles/validation/chop/0950.png), [correção/testes 11:20](../../frontend/src/features/threecandles/validation/chop/1120.png), [mínima parcial 11:27](../../frontend/src/features/threecandles/validation/chop/1127.png), [retorno acima da região 11:28](../../frontend/src/features/threecandles/validation/chop/1128.png), [recuperação confirmada 11:30](../../frontend/src/features/threecandles/validation/chop/1130.png), [faixa irregular apesar da alta 11:45](../../frontend/src/features/threecandles/validation/chop/1145.png), [saída para movimento direcional 12:15](../../frontend/src/features/threecandles/validation/chop/1215.png).
* [Oscilação/lateralização às 16:30](../../frontend/src/features/threecandles/validation/chop/1630.png), para comparação com a recuperação de grande deslocamento.

As capturas usam dados e snapshots calculados pelo teste Java, consumidos pela interface real com API interceptada localmente. As outras camadas são apenas ocultadas pelos controles existentes para estudar CHOP; seus dados permanecem presentes. O gráfico de candles tem altura de 420px somente nas capturas para acomodar os dois painéis. O painel CHOP mostra toda a sessão conhecida, com eixo temporal próprio; não segue pan/zoom do gráfico principal. Os arquivos `-browser.json` registram que o controle começou desligado e os erros de console.

Validação: **5 testes CHOP**, **6 de adaptadores**, **9 do detector experimental** passaram; **186 testes frontend** passaram; TypeScript compilado. Os testes CHOP verificam fórmula independente com TR/gap, histórico insuficiente, amplitude zero, logaritmo não finito, ausência de candles futuros, equivalência entre parcial final e fechamento, replay incremental/reconstrução e M5 fechado versus M1 progressivo nos cortes conhecidos. Os **19 hashes protegidos** conferem. Nenhum motor restrito, classe do detector ou dependência foi modificado.

Reproduzir: com Java 21, `mvn -pl panic-scanner -am test -Dtest=ExperimentalChopStudyTest,ThreeCandleAdaptersTest,ExperimentalThreeCandlePatternDetectorTest -Dsurefire.failIfNoSpecifiedTests=false`. Em `frontend`: `node node_modules/typescript/bin/tsc -b`, `npm test`; com Vite local, `node scripts/captureChop.mjs`.
