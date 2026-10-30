# Ondas Dow micro experimentais

Branch: feat/three-candles-foundation.

DowStructureEngine e ConfirmedPivotDetector permanecem intactos. O contrato acrescenta microDow nas respostas M5 de Replay e live. MicroDowEngine observa exclusivamente candles M5 fechados da sessao, sem alimentar a estrategia, o Dow dominante ou Fibonacci.

Criterio micro: extremo estrito 1+1. Igualdade com vizinho nao confirma extremo. Formacao usa extremeTimeMsc; confirmacao usa availableAtMsc do fechamento da barra direita. O movimento so fica conhecido no maior confirmedAtMsc de suas pontas. Classificacao HH/HL/LH/LL compara exclusivamente extremos micro anteriores do mesmo tipo, sem estado de tendencia principal. FIRST/EQUAL permanecem explicitos. Topo e fundo simultaneos sao auditados como ambiguos, nao entram na comparacao nem em conexoes; a cadeia recome?a depois dessa ambiguidade. Pivos consecutivos do mesmo lado permanecem registrados sem ligar topo a topo/fundo a fundo; o ultimo origina a proxima conexao alternada. Nenhum evento ou movimento anterior e apagado.

Visual: Dow principal conserva suas linhas; micro usa ciano tracejado, etiquetas com prefixo micro e controles independentes. Todos os controles sao apenas visuais. Auditoria micro expande o snapshot completo com extremos, comparacoes, confirmacoes, movimentos e eventos. Auditoria principal continua independente.

## Evidencia historica

Fonte existente: frontend/src/features/threecandles/validation/dow/WINZ26-m5.json, exportacao nativa MT5 documentada no README desse diretorio. Sessao usada: 16/10/2026, 113 candles M5, horario civil da fonte sem deslocamento. A imagem citada nao foi fornecida neste turno; data, contrato e intervalo exatos dessa imagem NAO estao certificados por esta validacao.

MicroDowEngineTest gera frontend/src/features/threecandles/validation/dow/micro-report.json com snapshots completos e comparacao por tipo e instante de origem. Para cada extremo micro informa participacao do original 2+2 e confirmacao original, ou NOT_CONFIRMED_2_2. Assim e possivel distinguir atraso de confirmacao, descarte estrutural e necessidade de maior sensibilidade.

Resultado: 31 pivos originais e 22 movimentos principais; 51 extremos micro e 26 conexoes micro. Correspondencias: 23 STRUCTURE, 6 LOCAL, 2 SUPERSEDED; 20 extremos exigem 1+1. Dez extremos micro sao ambiguos e ficam sem conexao. Esses numeros nao significam que cada extremo adicional seja uma correcao operacional valida.

Cada um dos 113 prefixos incrementais foi comparado com reconstrucao recebendo deliberadamente todas as barras futuras e respeitando o cursor. Comparacao inclui integralmente pivos, classificacoes, movimentos e eventos, tanto micro quanto principal. Dow principal nao sofreu edicao. Testes cobrem idempotencia, regressao, confirmacao, igualdade e ambiguidade. Testes do desenho cobrem linha tracejada, prefixo micro, filtros de confirmacao, coordenadas e visibilidade.

## Lateralizacao e limites

Uma sequencia sintetica lateral alternando extremos durante 30 barras produz 28 pivos e 27 conexoes: o 1+1 e ruidoso nesse contexto. Nao foi introduzido filtro arbitrario de amplitude; camada experimental desativada por padrao, sem sinais operacionais. A escolha do 1+1 e experimental, nao substitui o 2+2. Validacao visual no navegador e reproducao exata da captura do usuario ainda dependem da referencia ausente.

Para executar: com JDK 21, mvn -pl panic-scanner -am test "-Dtest=DowStructureEngineTest,MicroDowEngineTest" "-Dsurefire.failIfNoSpecifiedTests=false". Interface: npx tsc -b e node --experimental-strip-types --test tests/threeCandles*.test.mjs, no diretorio frontend.

Atualizacao posterior de selecao micro e contagens: [continuidade micro](three-candles-dow-continuity.md). Os resultados acima descrevem a etapa anterior.
