# Monitor de Sistema Remoto (Cliente/Servidor com Sockets)

Projeto Prático 1 — Redes de Computadores | Engenharia de Computação | PUC-Campinas

Aplicação cliente/servidor em Java que permite monitorar remotamente o sistema operacional da máquina onde o servidor está rodando (uso de CPU e memória). O servidor atende vários clientes ao mesmo tempo, cada um totalmente isolado dos demais, e continua funcionando mesmo quando clientes caem ou a rede falha.

Tema escolhido: **Monitor do sistema**.

---

## Sumário

- [Funcionalidades](#funcionalidades)
- [Estrutura do projeto](#estrutura-do-projeto)
- [Requisitos](#requisitos)
- [Como compilar e executar](#como-compilar-e-executar)
- [Comandos disponíveis](#comandos-disponíveis)
- [Exemplo de uso](#exemplo-de-uso)
- [Como funciona](#como-funciona)
- [Tratamento de exceções (Fase 3)](#tratamento-de-exceções-fase-3)
- [Cenários de teste](#cenários-de-teste)
- [Decisões de projeto](#decisões-de-projeto)

---

## Funcionalidades

**Fase 1 — Cliente único**
- Conexão TCP com mensagem de boas-vindas (`<HH:mm>: CONECTADO!!`) e menu de comandos.
- Comunicação bidirecional assíncrona com duas threads em cada lado.
- Monitor de CPU periódico (`CPU-<segundos>`), rodando em thread própria.
- Consulta de memória sob demanda (`memoria`) — bônus.
- `Quit` para parar o monitor e `Exit` para encerrar a sessão.

**Fase 2 — Multi-cliente**
- Uma worker thread por cliente; a thread principal só faz `accept()` e delega.
- Limite máximo de clientes passado por linha de comando.
- Cliente recusado recebe mensagem informando o limite e é desconectado.
- Ao desconectar, a vaga é liberada.
- Estado (flag do monitor, thread do monitor) é de instância, então um cliente nunca interfere em outro.

**Fase 3 — Controle de exceções**
- Nenhum stack trace ou erro do runtime aparece no console, nem no cliente nem no servidor.
- Servidor sobrevive a quedas abruptas de clientes e mantém o contador de clientes ativos correto.
- Cliente trata servidor offline, timeout, host inválido e queda do servidor no meio da execução.
- Sockets e Scanner são fechados via try-with-resources / `finally`.

---

## Estrutura do projeto

```
.
├── ClientePrototipo.java    # Cliente: conecta, lê o teclado e imprime o que o servidor envia
├── ServidorPrototipo.java   # Servidor: aceita conexões e cria uma worker thread por cliente
└── README.md
```

---

## Requisitos

- **JDK 14 ou superior** (o servidor usa `OperatingSystemMXBean.getCpuLoad()`, e o cliente usa try-with-resources sobre variável já declarada, que exige Java 9+).
- É necessário o **JDK** (não só o JRE), por causa do pacote `com.sun.management`.
- Porta **12345** livre na máquina do servidor.

Para conferir a versão:

```bash
java -version
```

---

## Como compilar e executar

### Compilar

```bash
javac ServidorPrototipo.java ClientePrototipo.java
```

### Iniciar o servidor

O argumento é o número máximo de clientes simultâneos (inteiro >= 1):

```bash
java ServidorPrototipo 3
```

### Iniciar um cliente

Sem argumento, conecta em `127.0.0.1`. Para outra máquina, informe o IP:

```bash
java ClientePrototipo
java ClientePrototipo 192.168.0.10
```

Abra quantos terminais quiser para simular vários clientes.

---

## Comandos disponíveis

| Comando | O que faz |
|---|---|
| `CPU-<segundos>` | Inicia o monitor de CPU, enviando o uso a cada N segundos (ex.: `CPU-5`) |
| `memoria` | Mostra memória usada, total, livre e percentual de uso |
| `Quit` | Para o monitor de CPU em execução |
| `Exit` | Encerra a sessão: o servidor fecha a conexão e o cliente termina |

Observações:
- Cada cliente pode ter apenas um monitor de CPU ativo por vez; use `Quit` antes de iniciar outro.
- `Quit` e `Exit` diferenciam maiúsculas de minúsculas. `memoria` não.
- Formatos inválidos (ex.: `CPU-abc`, `CPU-0`) retornam uma mensagem de ajuda, sem derrubar nada.

---

## Exemplo de uso

```
<14:32>: CONECTADO!! Menu: CPU-<segundos>, memoria, Quit, Exit
CPU-2
MONITOR CPU: 12.40%
MONITOR CPU: 9.85%
MONITOR CPU: 11.02%
memoria
MEMORIA: usada 8120 MB / total 16000 MB (livre 7880 MB) - 50.75% em uso
Quit
Monitor CPU encerrado.
Exit
```

Quando o limite de clientes é excedido:

```
<14:35>: RECUSADO! Limite de 3 clientes excedido. Tente mais tarde.
Ligacao terminada pelo servidor.
```

---

## Como funciona

### Servidor

```
main (accept loop)
 ├── accept() ──► vaga disponível? ── não ──► envia RECUSADO e fecha
 │                      │
 │                     sim
 │                      ▼
 │              new Thread(ClienteHandler)  ← worker thread do cliente
 │                      ├── lê comandos do socket (loop)
 │                      └── cria thread de monitor de CPU quando solicitado
 └── volta imediatamente ao accept()
```

- **Thread principal:** só aceita conexões e controla o limite. Nunca atende comandos.
- **`ClienteHandler`:** uma instância por cliente, com socket, `PrintWriter`, flag e thread do monitor próprios.
- **Contador de clientes:** `AtomicInteger` compartilhado. Só a thread principal incrementa; os workers só decrementam, no `finally`. Por isso nunca existem mais workers ativos que o limite.
- **Monitor de CPU:** thread daemon por cliente. O `Quit` zera a flag e dá `interrupt()` para acordar o `sleep` e parar na hora.

### Cliente

- **Thread principal (thread 1):** lê o teclado e envia os comandos ao servidor.
- **Thread leitora (thread 2, daemon):** lê o socket e imprime na tela.
- A flag `saidaVoluntaria` distingue "eu mandei `Exit`" de "o servidor fechou a conexão". No segundo caso, o cliente avisa e encerra, em vez de ficar preso esperando o teclado.

---

## Tratamento de exceções (Fase 3)

### Cliente

| Situação | Mensagem exibida |
|---|---|
| Servidor offline / porta fechada | `Servidor indisponivel no momento.` |
| Servidor não responde a tempo (timeout de 5 s) | `Servidor nao respondeu a tempo.` |
| Host inválido | `Endereco do servidor invalido: <host>` |
| Outra falha ao conectar | `Nao foi possivel conectar ao servidor.` |
| Servidor cai durante a sessão | `Ligacao terminada pelo servidor.` |
| Falha ao enviar (conexão perdida) | `Conexao perdida com o servidor.` |

Detalhe: `PrintWriter` não lança `IOException`, ele engole o erro. Por isso o cliente usa `checkError()` depois de cada envio para detectar a conexão caída.

### Servidor

- **Cliente fecha o terminal sem `Exit`:** o `readLine()` lança `IOException` (ou retorna `null`), o `finally` do worker para o monitor, fecha o socket e decrementa o contador. A vaga é liberada.
- **Falha no `accept()`:** é registrada e o loop continua; uma conexão ruim não derruba o servidor.
- **Falha ao criar a worker thread:** o contador é revertido e o socket é fechado, já que o `finally` do worker nunca chegaria a rodar.
- **Monitor de CPU com cliente morto:** a thread detecta com `checkError()` e para sozinha.
- **Porta em uso:** mensagem amigável em vez de stack trace.
- **Argumento inválido:** mensagem de uso e saída limpa.

---

## Cenários de teste

1. **Fluxo normal:** conectar, `CPU-2`, `memoria`, `Quit`, `Exit`.
2. **Servidor offline:** rodar o cliente sem o servidor ligado. Deve mostrar `Servidor indisponivel no momento.`
3. **Host inválido:** `java ClientePrototipo host.que.nao.existe`.
4. **Limite de clientes:** subir o servidor com limite 2 e abrir 3 clientes. O terceiro deve ser recusado.
5. **Liberação de vaga:** com o limite cheio, fechar um cliente com `Exit` e conectar outro. Deve ser aceito.
6. **Queda abrupta de cliente:** fechar a janela do terminal com o monitor rodando. O servidor deve registrar a desconexão e liberar a vaga.
7. **Queda do servidor:** parar o servidor (Ctrl+C) com clientes conectados. Os clientes devem avisar e encerrar sem erros.
8. **Isolamento:** dois clientes com monitores de CPU em intervalos diferentes; `Quit` em um não afeta o outro.
9. **Entradas inválidas:** `CPU-`, `CPU-abc`, `CPU-0`, comando aleatório e `Quit` sem monitor ativo.

---

## Decisões de projeto

- **Estrutura simples:** duas classes (cliente e servidor) e uma classe interna `ClienteHandler`, sem interfaces ou padrões extras, que não cabem no escopo do trabalho.
- **Estado por cliente em campos de instância:** a flag do monitor era `static` na Fase 1 e passou a ser de instância na Fase 2 para garantir isolamento.
- **`AtomicInteger` em vez de `synchronized`:** a reserva de vaga é atômica e simples de raciocinar.
- **Rejeição ativa:** o socket do cliente excedente é aceito só para enviar a mensagem de recusa e fechado em seguida, como pede a especificação.
- **Liberação de recursos no `finally`:** garante que a vaga volte e as threads não fiquem presas, qualquer que seja a causa do encerramento.
