# Referencia do padrao de tres candles: aulas e correcoes do usuario

Atualizado em 01/11/2026. Implementacao vigente: [engine V4 com pavio minimo](three-candles-pattern-wick-v4.md). Consultar este documento ANTES de interpretar ou alterar o detector. As definicoes explicitamente confirmadas pelo usuario prevalecem sobre hipoteses de implementacao e transcricoes ambiguas.

## Fontes locais encontradas

- `Aula01_Estudo.mp4` e `Aula01_transcricao.txt`, na raiz do projeto.
- `Aula02_Estudo.mp4` e `Aula02_transcricao.txt`, na raiz do projeto.
- `Aula03_transcricao.txt`, na raiz do projeto. O manifesto posteriormente permitiu localizar `C:/Users/paulo/Videos/AulaFinal/Aula03_Estudo.mp4` e `Aula03.mp4`. Nao afirmar revisao audiovisual integral.
- `three-candles-experimental-detector.md` descreve a implementacao de 30/10, suas hipoteses e divergencias. Nao e a especificacao definitiva do metodo.
- Aquele documento menciona `Caderno_3_Candles_Aulas_01_02_03.md`, o manifesto posteriormente confirmou sua localizacao em `C:/Users/paulo/Downloads/Caderno_3_Candles_Aulas_01_02_03.md`. Foram conferidos os trechos sobre negacao, teste e gatilhos; as correcoes explicitas do usuario prevalecem.

A existencia de transcricoes nao equivale a ter revisto integralmente os videos. A revisao de 31/10 localizou os arquivos e conferiu os trechos abaixo; nao foi uma revisao audiovisual integral das tres aulas. Transcricao automatica tem erros: conferir o video quando a formula depender de um termo ambiguo.

## Indice de trechos conferidos

- Aula 01, 01:33:17-01:34:05 (linhas 2357-2377): sequencia alvo, negacao da continuacao e teste; referencias de maxima, minima e 50%.
- Aula 02, 00:51:35-00:52:47 (linhas 955-965): candle 11 em alvo, depende de 12 negar para reconhecer o padrao; candle 13 testa para cima; exemplo de venda com minima 136855 menos 20 = 136835.
- Aula 02, 01:12:32-01:13:31 (linhas 1367-1386): explicacao do teste para cima antes da venda e posicionamento 20 pontos abaixo. A transcricao inclui palavras contraditorias em trechos proximos; nao transforma-las automaticamente em regras.
- Aula 03, 01:29:00-01:29:04 (linhas 2307-2308): resumo verbal da sequencia. A expressao movimento contrario nao substitui a correcao explicita do usuario de que C2 nao precisa trocar de cor.

## Regras confirmadas pelo usuario nesta conversa

1. Contexto anterior importa. No exemplo de compra, o mercado chega em movimento de baixa a uma marcacao anterior. Nao exigir DMI de alta para reconhecer essa compra de rejeicao.
2. O candle que toca a marcacao e apenas candidato a C1. So se confirma como candle 1 do padrao se o candle seguinte demonstrar a negacao. Sem C2 valido, aquele contato nao constitui C1 confirmado do padrao.
3. C2 nao precisa mudar de cor nem demonstrar reversao pronta. Basta perder forca e nao romper pelo fechamento o fechamento do corpo de C1 na direcao do movimento anterior. A referencia explicada na conversa e o fechamento de C1, nao sua minima/maxima nem a faixa inteira de C2. Nao reintroduzir silenciosamente hipoteses A/B anteriores como se fossem a regra confirmada.
4. Depois do fechamento de C2, comeca C3 e sua referencia inicial pode ser marcada. Nao se espera C3 fechar para entrar.
5. C3 precisa tentar seguir o fluxo anterior e falhar. Para compra apos baixa: testar para baixo, falhar e voltar para cima. Candle que sobe direto sem testar NAO autoriza entrada, mesmo que alcance o preco do gatilho.
6. Compra: gatilho 20 pontos acima da referencia maxima de C3 marcada e disponivel naquele momento. Nao usar a maxima final futura nem deslocar continuamente o gatilho junto com a alta para impedir o proprio acionamento. Na aula de venda conferida, minima parcial menos 20 pontos. Confirmar a regra espelhada completa com o usuario quando necessaria; nao extrapolar detalhes de stop.
7. O teste deve acontecer ANTES do retorno e do gatilho. Apenas OHLC M5 ou cor do candle nao comprovam essa ordem. Evidencia M1 pode deixar o mesmo minuto ambiguo; ticks podem ser necessarios para demonstrar sequencias dentro dele.
8. No Replay, o numero 1 pode ser desenhado sobre C1 retrospectivamente, mas sua disponibilidade como C1 confirmado e o fechamento de C2. Nao retroagir conhecimento ou entrada ao instante do contato inicial.

Nao foram quantificados pelo usuario: intensidade minima de perda de forca, excursao/duracao minima do teste, regra numerica de fluxo anterior, stop exato, validade/cancelamento e desempate entre contatos concorrentes. Registrar essas lacunas em vez de preencher com suposicoes de estrategia.

## Estado V1 conferido antes da implementacao

A versao V1 era experimental e divergia de partes dessas regras. `ExperimentalThreeCandlePatternDetector` deduz direcao pelo lado da abertura em relacao a faixa; ja registra ALVO antes da negacao; utiliza hipoteses de extremos de C1/C2 ou falso rompimento para NEGA; usa faixa completa de C2 para teste e M1 para gatilho hipotetico. `ThreeCandlePatternStudy` organiza publicacao causal de referencias. DMI e Dow existem em paralelo e ainda nao fornecem ao detector um contexto direcional formal para a sequencia inteira.

Nao afirmar que reconhecer compra/venda em um candidato significa que a engine conhece ou valida o contexto correto do metodo. Nao afirmar que o codigo reproduz fielmente a aula ate confrontar as regras e os casos.

DMI: reset da sessao e estimativa visual de aquecimento nao equivalem a um filtro operacional ja conectado ao padrao. Distinguir direcao estimada, classificacao com ADX pronto e movimento anterior ao alvo.

Este registro e uma referencia persistente no projeto. Ele nao altera codigo, nao confirma desempenho financeiro e nao significa que as divergencias ja foram corrigidas.


## Implementacao V2 em 31/10

A implementacao atual usa `CONTEXT_CLOSE_NEGATION_TEST_RETURN_M1_V2`. A especificacao e os limites estao em [engine V2](three-candles-pattern-v2.md). O contexto de aproximacao e uma escolha objetiva de engenharia (dois fechamentos M5 anteriores a C1), separado do estado principal/micro. Nao e apresentado como regra textual comprovada da aula. C1 so recebe marca com C2; negacao pelo fechamento; teste obrigatorio; referencia inicial de C3 congelada na abertura; gatilho apos teste e retorno. Fonte audiovisual e conversa continuam sendo referencias para refinamento.


Antecipacao informativa e exibicao dos candidatos no inicio de C3: [Dow 2+1](three-candles-dow-anticipation.md). Nao e um filtro obrigatorio do padrao nem altera a Dow confirmada 2+2.

Replay progressivo agora tolera lacunas M1 com fechamento M5 oficial apenas no instante proprio: [politica e validacao](three-candles-progressive-gaps.md).

Criterio proposto em 02/11: rejeicao minima 30 ticks (150 pontos WIN), mantendo provisoriamente a entrada anterior a 20 pontos enquanto a unidade e esclarecida; consultar V4.

Correcao explicita do usuario em 02/11: pavio minimo de **30 pontos**, equivalente a 6 ticks WIN; entrada permanece 20 pontos. Esta correcao substitui os 30 ticks propostos anteriormente.
