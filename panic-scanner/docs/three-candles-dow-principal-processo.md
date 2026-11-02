# Como a Dow principal identifica e desenha uma onda

Investigacao do codigo vigente em 01/11/2026, branch feat/three-candles-foundation. Nenhuma regra da engine foi alterada nesta investigacao.

## Caminho dos dados

Candle fechado M5 -> CandlePipeline.accept -> ThreeCandleStructure.onCandle/process -> ConfirmedPivotDetector.onCandleClosed -> StructuralLevel -> DowStructureEngine.advance/accept -> Snapshot.movements -> API -> DowStructurePrimitive.

A Dow principal recebe o campo `dow`; a micro recebe `microDow` e usa 1 esquerda/1 direita em engine independente. A principal usa 2 esquerda/2 direita, totalizando cinco candles fechados. O detector M5 reinicia a janela ao mudar a sessao. A analise principal usa pivots e candles da sessao selecionada; aquecimento de H1 nao cria uma onda principal ligando ontem a hoje.

## Identificacao do topo

Para a janela A,B,T,C,D, o detector avalia o candle central T:

`T.high > A.high && T.high > B.high && T.high > C.high && T.high > D.high`.

Usa a maxima incluindo pavio. Cor, fechamento abaixo da minima anterior, volume, ADX, amplitude minima e retracao de 50% nao sao requisitos dessa deteccao. Igualdade com qualquer vizinho impede o topo estrito. Fundo usa minima estritamente menor que as quatro vizinhas.

O reconhecimento acontece ao fechar D. Se T abriu as 10:00 em M5, T fecha 10:05, C fecha 10:10 e D fecha 10:15. O topo e conhecido as 10:15, mas seu ponto geometrico fica na maxima do candle das 10:00. `extremeTimeMsc` e o horario de origem; `confirmedAtMsc` e a disponibilidade da confirmacao. Sao 10 minutos apos o fechamento de T, ou 15 apos sua abertura, supondo candles consecutivos.

A janela conta cinco candles recebidos, nao verifica explicitamente que suas origens sejam contiguas de cinco em cinco minutos. Com lacunas reais no M5, nao se deve prometer uma duracao fixa de confirmacao. Candles parciais nao entram na deteccao principal.

## Escolha das pontas da onda

A deteccao nao escolhe sozinha o que sera ligado. DowStructureEngine mantem uma cadeia de extremos estruturais:

- Primeiro pivo utilizavel inicia a cadeia, ainda sem segmento.
- Pivo posterior de tipo oposto entra na cadeia: fundo -> topo gera UP; topo -> fundo gera DOWN.
- Novo pivo do mesmo tipo do terminal so o substitui se for mais extremo: topo mais alto ou fundo mais baixo. O anterior permanece no historico com participacao SUPERSEDED.
- Mesmo tipo sem extensao fica LOCAL; nao vira ponta de segmento.
- Origem anterior ou igual ao terminal fica LOCAL.
- Topo e fundo com mesma origem e confirmacao ficam LOCAL, pois OHLC nao informa a ordem intrabar.

A onda de alta parte da minima do fundo estrutural escolhido anteriormente e termina na maxima do topo selecionado. Nao parte necessariamente do primeiro candle verde nem percorre cada candle intermediario. O backend produz `Movement(fromId,toId,knownAtMsc,kind)` ligando extremos consecutivos; sua disponibilidade e o maior horario de confirmacao das duas pontas.

## Topo, onda e tendencia sao decisoes diferentes

HH/LH comparam o topo com o topo estrutural de referencia; HL/LL comparam fundos. A classificacao e registrada na chegada do pivo. Pivos locais podem ter uma classificacao de comparacao sem participar da cadeia estrutural.

Estado UP exige o par HH e HL; DOWN exige LH e LL. Um segmento UP pode existir com tendencia geral DOWN, TRANSITION ou UNDEFINED. Um topo isolado nao prova tendencia de baixa. Rompimento estrutural exige fechamento M5 estritamente alem da referencia conhecida antes daquele candle; pavio isolado nao basta. Isso e separado da deteccao de topo, que usa pavios.

## Desenho

DowStructurePrimitive posiciona cada ponto por `extremeTimeMsc` e `price`, e liga apenas `snapshot.movements` disponiveis no instante. Nao recalcula pivots no navegador. Principal: linha fina continua; micro: ciano tracejado. Ponto cheio e estrutural; circulo vazio pode ser local ou terminal substituido. Portanto, um ponto isolado visivel nao prova que foi selecionado como ponta de onda.

A geometria se ancora no passado quando a confirmacao chega. Isso nao equivale a um sinal conhecido no candle de origem. Terminais podem mudar por extensao confirmada: o desenho corrente nao e imutavel, embora o registro dos pivots e eventos seja preservado. Reiniciar Replay deve respeitar o instante de disponibilidade.

## Aplicacao a imagem do usuario

O desenho mostra um fundo menor circulado e um topo maior com seta, ligados por segmento ascendente. Se esse segmento e a camada Dow principal, o processo e: fundo previamente selecionado -> topo candidato de maxima maior que duas vizinhas anteriores -> espera duas posteriores fechadas e inferiores -> confirma topo -> seleciona como terminal oposto -> liga fundo/topo nas coordenadas originais.

A imagem recortada nao informa data, contrato, horario, OHLC ou camadas habilitadas. Nao e possivel afirmar o ID, preco, horario de confirmacao, classificacao HH/LH ou valores exatos das quatro comparacoes desse ponto apenas pelo recorte. Foi solicitado ao usuario o contexto para confrontar o registro exato, sem atribuir valores inventados a imagem.

## Limite do nome principal

A principal atual e uma cadeia estrutural filtrada de fractais M5 2+2. Nao ha nessa selecao um filtro adicional por amplitude minima, ATR, percentual de correcao ou hierarquia de ondas maiores. Assim, principal nao garante automaticamente o mesmo topo dominante escolhido pelo olhar humano. A diferenca fundamental para a micro e a janela de confirmacao mais larga, alem da logica propria de estado estrutural.

## Arquivos centrais

- `application/win/strategy/ConfirmedPivotDetector.java`: janela e comparacoes estritas.
- `strategy/threecandles/application/ThreeCandleStructure.java`: candles fechados, reset de sessao e StructuralLevel.
- `strategy/threecandles/application/DowStructureEngine.java`: selecao, extensao, classificacao, tendencia, rompimentos e segmentos.
- `frontend/src/features/threecandles/components/DowStructurePrimitive.ts`: coordenadas e desenho.
- Testes existentes ThreeCandleStructureTest cobrem origem/confirmacao, candles parciais, igualdade e sessao; DowStructureEngineTest cobre cadeia, extensoes, ambiguidades, tendencia e rompimentos. A investigacao leu esses testes; nao afirma nova execucao nem rastreamento do ponto exato da imagem.
