# Validação da versão final do Monitor do sistema

Referência: Especificação do Projeto Prático 1 - Completo, páginas 1, 2, 4 e 5.
Data: 4 de outubro de 2026. Ambiente: Windows, OpenJDK Temurin 25.0.1, TCP loopback.
Arquivos: `Phase 3/ServidorPrototipo.java` e `Phase 3/ClientePrototipo.java`.

## Requisitos e evidências locais

| Requisito | Evidência |
|---|---|
| TCP bidirecional assíncrono | Worker de leitura, threads de monitor e thread leitora do cliente |
| Horário e menu | Dois clientes receberam boas-vindas e sintaxe |
| CPU e memória periódicas simultâneas | Várias amostras dos dois tipos na mesma sessão |
| Quit encerra monitoração | Confirmação seguida de ausência de novas métricas |
| Exit encerra sessão | Confirmação, término do cliente e vaga disponível |
| Clientes independentes | Quit no A não interrompeu CPU do B |
| Limite por argumento | Limite 2 recusou o terceiro cliente |
| Liberação de vagas | Nova conexão após Exit e reset abrupto |
| Controle de exceções | Porta ocupada, argumentos e comandos inválidos com mensagens de aplicação |
| Cliente tolera indisponibilidade | Servidor offline e host inválido sem stack trace |
| Cliente termina após queda do servidor | Processos encerraram mesmo com teclado aberto, sem entrada |
| Continuidade do servidor | Novas conexões aceitas após desconexões |

## Correções

- Memória periódica restaurada na Fase 3, simultânea à CPU.
- Tarefas e flags independentes por tipo e por sessão.
- Quit e substituição aguardam o término da tarefa anterior.
- Flag de saída voluntária marcada antes do envio de Exit.
- Cliente aguarda confirmação do servidor, com limite de 5 segundos.
- Falha de envio detectada por `checkError()` fecha o socket no servidor.
- Métodos de memória substituídos por `getTotalMemorySize()` e `getFreeMemorySize()`.
- Protocolo de texto UTF-8 e documentação atualizada.
- Recusa executada pela worker, conforme o fluxo descrito na Fase 2 do enunciado.
- Lista sincronizada de handlers registrada pela main e limpa no término das workers.
  Apenas sessões com vaga reservada decrementam o contador ao terminar.

## Resultado reproduzível

Na raiz, sem servidor manual aberto:

```powershell
python tests/verificar_fase3.py
```

Todos os testes passaram. Compilação com `-Xlint:all -Werror` sem erros ou avisos.
Também foi validada a compilação com `--release 14`, conferindo compatibilidade
da linguagem e das APIs com Java 14; a execução dos testes utilizou Java 25.
Foram exercitados monitores simultâneos, isolamento, entradas inválidas, 30 ciclos
de Quit/reinício, alteração de intervalo, recusa, vagas após Exit e reset,
saída voluntária, queda do servidor, porta ocupada, argumentos, servidor offline
e host inválido. Não houve stack traces nos cenários executados.

## Limites e pendências

A validação foi local. Demonstração em dois computadores permanece pendente:
seguir `ROTEIRO_DEMONSTRACAO.md`. Não foram simulados timeout por descarte de
pacotes, esgotamento de memória da JVM nem todos os comportamentos de perda física
de rede. Há tratamento de timeout de conexão no código; uma sessão estabelecida
depende da detecção de falha pelo TCP. Os testes não garantem ausência de qualquer
erro em todo ambiente possível.

As fases anteriores e o relatório da Fase 1 são históricos. A versão de entrega é
`Phase 3`. Chat e Lottery não foram finalizados como parte deste tema.
