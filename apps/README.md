# Aplicações independentes — primeira etapa

| Aplicação | Frontend dev / preview | Backend | Main |
|---|---|---|---|
| Scanner | 5173 / 4173 | 8081 | `br.com.bauzin.apps.scanner.ScannerApplication` |
| WIN M5 / Fluxo | 5174 / 4174 | 8082 | `br.com.bauzin.apps.winm5.WinM5Application` |
| Padrão de 3 Candles | 5175 / 4175 | 8083 | `br.com.bauzin.apps.threecandles.ThreeCandlesApplication` |

Os tres processos Spring Boot tem composicao explicita. O component scan fica no pacote de cada aplicacao. Scanner nao cria captura MT5 nem endpoints WIN/3 Candles. 3 Candles tem acesso proprio ao MT5 e nao registra endpoints de acoes/WIN. Os servidores usam loopback por padrao.

## Estado desta etapa

As entradas, builds, rotas e processos foram separados; a movimentação física completa das funcionalidades ainda não foi feita. Os fontes de negócio existentes continuam canônicos em `panic-scanner/src` e `frontend/src/features`. Os novos backends dependem temporariamente do JAR normal `panic-scanner`, sem iniciar seu main. Isso mantém os motores e as alterações não commitadas intactos, mas ainda inclui classes de outras funcionalidades no classpath. A próxima extração física deve substituir essa dependência por bibliotecas menores.

Os frontends reutilizam a instalação de ferramentas em `frontend/node_modules` e os fontes existentes; não são cópias dos algoritmos. Cada build tem uma lista de dependências proibidas e falha se incluir funcionalidades de outra aplicação. Em 3 Candles, a única implementação WIN permitida é a `SwingPivotPrimitive` existente. Imports de tipos não entram no bundle. A primitiva será candidata a pacote gráfico próprio em etapa posterior.

O frontend legado e seu main continuam disponíveis. Não executar seu servidor dev junto do novo Scanner na porta 5173. O Dashboard demonstrativo foi removido; `/` e links antigos para `/dashboard` levam a `/scanner`, tanto no frontend legado quanto no Scanner independente.

## Build

Na raiz do repositório, com Java 21 e Maven:

```powershell
mvn -pl apps/scanner/backend,apps/win-m5/backend,apps/three-candles/backend -am package -DskipTests
```

Os JARs Spring Boot ficam em `apps/<aplicacao>/backend/target/`. Execute a partir da raiz para preservar a resolução dos scripts e históricos existentes:

```powershell
java -jar apps/scanner/backend/target/scanner-app-0.0.1-SNAPSHOT.jar
java -jar apps/win-m5/backend/target/win-m5-app-0.0.1-SNAPSHOT.jar
java -jar apps/three-candles/backend/target/three-candles-app-0.0.1-SNAPSHOT.jar
```

Use terminais separados. Esses comandos não foram usados para iniciar captura real durante a implementação.

Para cada frontend:

```powershell
# A instalação compartilhada pode ser preparada com npm ci em frontend/.
cd apps/three-candles/frontend
npm run dev
```

O mesmo vale para `apps/scanner/frontend` e `apps/win-m5/frontend`. Cada diretório tem `npm run build`, `npm run preview` e `.env.example`. `VITE_API_URL` aponta por padrão para o backend correspondente; mock fica desativado por padrão nas novas entradas. Copie `.env.example` para `.env` para personalizar.

## MT5: cada aplicacao funciona sozinha

**3 Candles nao precisa do backend WIN M5 nem de MARKET_INTERNAL_TOKEN.** A dependencia HTTP entre aplicacoes foi removida.

Para usar somente 3 Candles:

1. Mantenha o terminal MetaTrader 5 conectado e o Python com o pacote MetaTrader5 disponivel.
2. Rode `ThreeCandlesApplication` (8083).
3. Rode `npm run dev` em `apps/three-candles/frontend` (5175).
4. Para Replay, escolha a sessao e carregue. Se o cache nao existir, o proprio backend consulta e armazena M5/M1 usando `Mt5ProcessClient`.
5. Para Tempo Real, acrescente `--market.capture.enabled=true` aos Program arguments do main no IntelliJ e reinicie. O stream pertence ao proprio 3 Candles.

A captura continua permanece desativada por padrao; historico/replay nao exige ativa-la. `MT5_SYMBOL` configura o contrato live. Nao e necessario token entre aplicacoes, URL de proprietario ou frontend WIN.

WIN M5 continua com sua ativacao propria de captura. Se as duas aplicacoes forem executadas ao mesmo tempo com captura ativa, cada processo tera sua propria conexao MT5. Compartilhar biblioteca evita duplicacao de codigo, mas nao transforma processos independentes em uma conexao unica global.

A implementacao e reutilizada, sem copia das formulas ou dos clientes: `Mt5ProcessClient`, `Mt5TickStreamLifecycle`, o adaptador TA4J e o carregador de historico continuam com uma fonte canonica. `LocalMt5DataConfiguration` compoe os servicos locais sem iniciar stream por si so. O main de cada aplicacao controla sua ativacao.

O cliente de consultas e seus modelos foram extraidos para o modulo Maven `mt5-client`, sem dependencia de nenhuma aplicacao e sem alteracao de conteudo. Os servicos e motores de nivel mais alto ainda sao reutilizados pelo JAR temporario `panic-scanner`; sua separacao fisica continua pendente. Dependencia de biblioteca nao exige executar `PanicScannerApplication` ou `WinM5Application`.


## Validação

Relatórios e logs ficam em `docs/application-split/`. Incluem:

- Manifesto SHA-256 do workspace anterior e verificação de preservação.
- Testes de isolamento dos três contextos Spring.
- Historico local sem outro backend ou token; precos, timestamps e cache preservados.
- Regressões de médias móveis, CHOP e um teste causal de Dow de 29 de outubro.
- Suíte frontend existente e builds independentes com verificação de fronteiras.

O teste Dow selecionado é `incrementalAndRebuiltPrefixesAgreeAndDoNotUseFutureBars`; o método que exporta arquivos para o frontend não é executado. A captura MT5 e a conexão Profit não foram iniciadas pelos testes.

Validacao da independencia: `docs/application-split/standalone-history-validation.log`.

Extracao do cliente MT5 e build limpo dos tres executaveis: `docs/application-split/standalone-mt5-package-validation.log`. Hashes dos fontes movidos: `docs/application-split/mt5-client-extraction.json`.
