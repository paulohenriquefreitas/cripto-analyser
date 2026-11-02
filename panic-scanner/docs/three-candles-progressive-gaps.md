# Replay progressivo com M1 incompleto

Correcao para WIN$N em 16/09/2026. O Replay falhava com HTTP 422 na ausencia do M1 de 09:00. Confirmado em runtime com logpoint sem suspensao na sessao ThreeCandlesApplication. O cache e uma nova consulta direta ao MT5 retornaram os mesmos 563 M1, iniciando as 09:02. Os M5 oficiais incluem 09:00: 113 M5 da sessao, 565 minutos esperados, dois ausentes. Nao foi concluido que nao houve negocios nesses minutos; apenas que a fonte consultada nao os fornece.

## Comportamento

- Cada M5 usa somente o prefixo contiguo de M1 nativos, iniciado na abertura real do bucket.
- Sem o primeiro M1, nao usa a abertura oficial M5 antecipadamente nem desloca a abertura para o primeiro minuto disponivel. Aguarda o fechamento oficial M5.
- Ao faltar um minuto intermediario, conserva as observacoes anteriores; o restante daquele M5 aguarda o fechamento oficial, pois nao se conhece a amplitude acumulada completa.
- Sem o ultimo minuto, publica o fechamento M5 oficial no instante proprio, sem fabricar o M1 final.
- Se todo o bucket estiver ausente no M1, preserva apenas seu M5 oficial fechado.
- O proximo M5 reinicia a progressao se houver prefixo M1 contiguo.
- OHLC contraditorio, ordem invalida ou identidade errada continuam sendo erros. Os dados disponiveis precisam respeitar o envelope M5 oficial; se os cinco minutos existem, o agregado precisa ser exatamente igual ao oficial.

Assim, 16/09 carrega as 09:00, sem inventar candle parcial. O primeiro passo revela o M5 de 09:00 somente as 09:05. O seguinte mostra o M5 de 09:05 em formacao as 09:06. O primeiro bucket nao recebe passos ficticios de 09:00/09:01/09:02.

`ProgressiveM1History.buildDetailed` entrega observacoes e lacunas nativas separadas. Controller/carregador passam essa cobertura explicitamente ao Replay. Um fechamento oficial nao pode mascarar a ausencia do M1 final. A lista missingM1Times descreve cobertura do arquivo conhecido ao carregar o Replay; nao e um sinal calculado com precos futuros.

A interface informa minutos ausentes, incluindo limites dos M5, e explica que os trechos incompletos aguardam o fechamento oficial. Sem preenchimento, interpolacao ou mudanca de contrato. O detector de tres candles continua usando M1 nativo independente; o fechamento oficial nao comprova uma sequencia de teste/retorno dentro do candle.

## Validacao

Fixtures reais em `src/test/resources/threecandles/progressive-gaps`. [Origem e evidencia de runtime](three-candles-progressive-gaps-verification.json).

ProgressiveM1GapsNativeTest percorre os 113 fechamentos e compara candles, estrutura, Dow principal e 50% com Replay fechado. Reconstrucao por prefixo coincide com avancos. O mesmo teste passa pelo carregador/controller usados pela aplicacao e exporta 09:00, 09:05 e 09:06 em `frontend/src/features/threecandles/validation/progressive-gaps`.

Testes sinteticos cobrem minuto inicial, interior, final e bucket inteiro ausentes, retomada no seguinte, reset, cobertura exata e divergencia M1/M5. Testes existentes de dados nativos progressivos e detector do padrao continuam incluidos. Frontend valida os tres cortes reais, a ausencia de precos futuros e o aviso de lacunas.

Reiniciar o backend e recriar a sessao de Replay para aplicar o codigo novo. A sessao de debug original nao foi interrompida. application.yml nao precisou ser alterado.
