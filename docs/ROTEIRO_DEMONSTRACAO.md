# Roteiro de demonstração do Monitor do sistema

Use a pasta `Phase 3` e reserve cerca de 10 minutos. O tema escolhido é Monitor
do sistema; Chat e Lottery são outros temas do enunciado.

## Preparação

Em cada máquina, confira `java -version` e `javac -version` (JDK 14 ou superior).
Abra o terminal na pasta `Phase 3` e compile:

```powershell
javac -encoding UTF-8 -Xlint:all -Werror ServidorPrototipo.java ClientePrototipo.java
```

Para demonstração local, abra um terminal de servidor e três de clientes.
Para demonstração remota, use dois computadores na mesma rede: servidor no A,
clientes no B. No A, execute `ipconfig` e anote o IPv4 da interface conectada.
Se o Windows solicitar acesso para Java, permita a rede usada na aula. Se houver
bloqueio, confira a permissão de entrada TCP 12345 no firewall do servidor.

## Fase 1: monitores e comunicação assíncrona

No computador A:

```powershell
java ServidorPrototipo 2
```

No cliente local, use `java ClientePrototipo`. No computador B, substitua o IP abaixo
pelo IPv4 do computador A:

```powershell
java ClientePrototipo 192.168.0.10
```

Mostre `<HORARIO>: CONECTADO!!` e o menu. No primeiro cliente, digite:

```text
CPU-2
memoria-3
```

Aguarde pelo menos 7 segundos. Mostre as várias medições nos intervalos próprios,
com o teclado ainda disponível. As métricas são do servidor. Abra uma aplicação
no servidor para observar a mudança; os valores não precisam coincidir exatamente
com o Gerenciador de Tarefas, pois as janelas de amostragem diferem.

Envie `memoria`: consulta única sem parar monitores. Envie `CPU-1`: muda apenas
o intervalo da CPU. Envie `Quit`: ambos param, mas a conexão permanece.
Envie `CPU-1` novamente para demonstrar reinício.

Explique: cliente lê teclado e rede em threads distintas; servidor possui worker
por sessão e thread por monitor. Cada tarefa possui sua própria flag de parada.
A escrita na conexão é sincronizada.

## Fase 2: isolamento, limite e vagas

Conecte o segundo cliente e envie `CPU-3` e `memoria-2`. No primeiro, envie `Quit`:
o segundo deve continuar recebendo métricas.

Conecte um terceiro cliente: deve receber `RECUSADO!` com o limite de dois e terminar.
Envie `Exit` no primeiro e conecte o terceiro novamente: agora deve ser aceito.
Mostre o contador de clientes no servidor.

Explique: cada sessão possui socket, mapa e parâmetros próprios. O contador usa
`AtomicInteger` e a lista compartilhada de handlers é sincronizada. A main registra
os handlers e cria as workers; a própria worker recusa clientes excedentes e remove
seu registro ao terminar, liberando a vaga somente se ela havia sido reservada.

## Fase 3: falhas e continuidade

| Ação | Resultado esperado |
|---|---|
| `CPU-abc`, `CPU-0`, `memoria-0`, `aleatorio` | Validação amigável; sessão continua |
| `Quit` sem monitor | Mensagem informando ausência de monitor |
| Fechar terminal cliente com monitores ativos | Vaga liberada; outro cliente continua |
| Abrir cliente após a queda | Nova conexão aceita |
| Parar servidor com Ctrl+C | Clientes avisam e terminam sem pressionar Enter |
| Cliente com servidor desligado | `Servidor indisponivel no momento.` |
| `java ClientePrototipo host-inexistente.invalid` | Endereço inválido |
| `java ServidorPrototipo 0` | Argumento inválido |
| Segundo servidor na mesma porta | Mensagem de porta ocupada, sem stack trace |

Reinicie o servidor e conecte novamente ao final. Para queda reproduzível, encerre
o processo; retirar cabo/Wi-Fi pode demorar a ser detectado pelo TCP.

## Evidências para a entrega

- Captura das versões Java e compilação sem avisos.
- Dois clientes com CPU e memória simultâneas.
- Recusa por limite e aceitação após liberar vaga.
- Contador após queda abrupta.
- Clientes encerrando após queda do servidor.
- Saída de `python tests/verificar_fase3.py`, na raiz, sem servidor manual aberto.

Registre quais computadores foram usados e o resultado remoto. Não marque a
demonstração em duas máquinas como concluída antes de executá-la.
