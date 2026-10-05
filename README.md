# Monitor de Sistema Remoto

Projeto Prático 1 de Redes de Computadores, Engenharia de Computação, PUC-Campinas.
Tema escolhido: **Monitor do sistema**. A aplicação usa sockets TCP e threads em Java
para acompanhar CPU e memória do computador onde o servidor é executado.

**A versão para execução e entrega é a pasta `Phase 3`.** Ela reúne os monitores da
Fase 1, o atendimento multi-cliente da Fase 2 e o controle de exceções da Fase 3.
As versões anteriores estão preservadas como histórico.

## Estrutura

```text
Phase 1/
  Prototype/              Protótipo inicial
  Final Code/             Implementação anterior de CPU e memória
  Relatorio_Fase_1.pdf     Relatório histórico da Fase 1
Phase 2/
  Prototype/              Monitor com múltiplos clientes
  Chat/                   Outro tema do enunciado
  Lottery/                Outro tema do enunciado
Phase 3/
  ServidorPrototipo.java   Servidor final do Monitor do sistema
  ClientePrototipo.java    Cliente final
tests/
  verificar_fase3.py       Testes de integração com Java e sockets reais
docs/
  ROTEIRO_DEMONSTRACAO.md  Demonstração local e em duas máquinas
  VALIDACAO_FASE_3.md      Requisitos, resultados e limites da validação
```

Chat e Lottery não fazem parte da entrega do tema escolhido. As fases têm classes
com nomes repetidos: compile apenas a pasta indicada, sem misturar versões.

## Requisitos

- JDK 14 ou superior, com `java` e `javac` disponíveis no terminal.
- Porta TCP 12345 disponível no servidor.
- Para uso remoto: rede acessível e entrada TCP 12345 permitida no firewall do servidor.
- Python 3 somente para o teste automatizado; a aplicação depende de Java.

Confira a instalação:

```powershell
java -version
javac -version
```

## Compilar e executar no Windows

Na raiz do repositório, entre na versão final e compile:

```powershell
cd "Phase 3"
javac -encoding UTF-8 -Xlint:all -Werror ServidorPrototipo.java ClientePrototipo.java
java ServidorPrototipo 2
```

O argumento `2` permite dois clientes simultâneos e deve ser um inteiro positivo.
Mantenha este terminal aberto. Em outro terminal, também na pasta `Phase 3`:

```powershell
java ClientePrototipo
```

Sem argumento, o cliente usa `127.0.0.1`. Para outra máquina:

```powershell
java ClientePrototipo 192.168.0.10
```

O IP é um exemplo: consulte `ipconfig` no servidor e use o IPv4 da interface em uso.
Cada terminal de cliente representa uma sessão.

## Comandos

| Comando | Ação |
|---|---|
| `CPU-2` | Medição imediata de CPU, repetida a cada 2 segundos |
| `memoria-3` | Medição imediata de memória, repetida a cada 3 segundos |
| `memoria` | Consulta única de memória, sem iniciar outro monitor |
| `Quit` | Para todos os monitores daquela sessão e mantém a conexão |
| `Exit` | Para os monitores, solicita fechamento da conexão e encerra o cliente |

Os comandos aceitam maiúsculas/minúsculas e espaços nas extremidades. Intervalos:
inteiros de 1 a 2147483647 segundos. CPU e memória funcionam simultaneamente com
intervalos independentes. Repetir um tipo substitui seu monitor e altera o intervalo.
Um comando inválido não substitui um monitor válido em andamento.

Digite uma linha por vez, aguardando as medições antes de enviar Quit:

```text
CPU-2
memoria-3
memoria
Quit
CPU-1
Exit
```

CPU pode mostrar `N/D` quando o sistema não fornece uma amostra. Memória representa
memória física do sistema remoto, não apenas heap da JVM. Os valores variam com a
carga da máquina; o separador decimal depende do ambiente Java.

## Threads e dados

- A thread principal do servidor faz `accept()`, reserva uma vaga e cria uma worker
  por conexão, inclusive para clientes excedentes. A própria worker envia a recusa
  e encerra quando não há vaga. O contador compartilhado usa `AtomicInteger`.
- Os handlers são registrados pela main em uma lista sincronizada compartilhada.
  Cada worker remove seu registro ao terminar. Se a thread não iniciar, a main
  desfaz o registro e a reserva; clientes recusados não diminuem o contador.
- Cada worker tem seu socket e mapa de monitores e interpreta os comandos. Somente
  ela altera o mapa, portanto ele não precisa ser concorrente.
- Cada monitor tem thread e flag `volatile` próprias. O envio na conexão é sincronizado
  para serializar mensagens de CPU, memória e comandos.
- Ao substituir um monitor ou executar Quit, a worker sinaliza parada, interrompe
  o sleep e aguarda o término antes de confirmar.
- No cliente, a thread principal lê o teclado e envia comandos; uma thread leitora
  imprime mensagens da rede. O recebimento de métricas não depende do teclado.

## Conexões e exceções

O cliente excedente recebe `RECUSADO!` com o limite e é desconectado. No `finally`
da worker, o servidor fecha o socket, encerra monitores e libera a vaga.
Uma queda de cliente não encerra as demais sessões.

O cliente informa servidor offline, endereço inválido e timeout de conexão (5 segundos).
Quando recebe fechamento ou erro do servidor, termina mesmo esperando teclado:
a thread leitora usa `System.exit(0)` em desconexões não solicitadas. Na saída
voluntária, marca a flag antes de enviar Exit e aguarda até 5 segundos pela resposta
final e pelo fechamento do servidor.

Porta ocupada, argumentos inválidos e falhas de comunicação geram mensagens de
aplicação. Como `PrintWriter` não lança falhas de envio, usa-se `checkError()`; uma
falha detectada no servidor fecha o socket e desbloqueia a leitura da worker.
Uma perda física de rede sem FIN/RST pode depender da detecção TCP do sistema:
não há heartbeat nem prazo de timeout para uma sessão já estabelecida.

## Testes

Feche o servidor manual antes do teste, que precisa da porta 12345. Na raiz:

```powershell
python tests/verificar_fase3.py
```

O teste compila em uma pasta temporária, inicia processos Java e verifica monitores
simultâneos, entradas inválidas, isolamento, limite, vagas, reinício rápido, alteração
de intervalo, Exit, recusa, queda do servidor, servidor offline e host inválido.
A compilação falha se houver qualquer aviso.

Veja [Validação da Fase 3](docs/VALIDACAO_FASE_3.md) e
[Roteiro de demonstração](docs/ROTEIRO_DEMONSTRACAO.md). Os testes locais não
substituem a demonstração em duas máquinas.
