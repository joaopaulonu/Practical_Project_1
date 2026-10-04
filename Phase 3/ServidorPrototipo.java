// ServidorPrototipo.java
import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

public class ServidorPrototipo {
    // Porta onde o servidor escuta
    static final int PORTA = 12345;
    // Formato de hora para mensagens
    static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    // Contador de clientes ativos
    static final AtomicInteger clientesAtivos = new AtomicInteger(0);
    // Bean para obter informações do sistema operacional
    static final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    // O método main do servidor recebe como argumento o número máximo de clientes simultâneos.
    public static void main(String[] args) {
        // Verifica se o argumento(<max_clientes>) foi fornecido corretamente
        if (args.length < 1) {
            System.err.println("Uso: java ServidorPrototipo <max_clientes>");
            return;
        }
        final int limiteClientes;
        try {
            limiteClientes = Integer.parseInt(args[0]);
            if (limiteClientes < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Erro: <max_clientes> deve ser um inteiro >= 1.");
            return;
        }
        // Inicia o servidor na porta especificada
        try (ServerSocket server = new ServerSocket(PORTA)) {
            System.out.println("Servidor aguardando conexoes na porta " + PORTA
                    + " (limite: " + limiteClientes + " clientes)...");

            // Loop principal do servidor: aceita conexões e cria threads.      
            while (!server.isClosed()) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (IOException e) {
                    // Falha numa conexão não pode derrubar o servidor
                    if (server.isClosed()) break;
                    System.out.println("Falha ao aceitar conexao: " + e.getMessage());
                    continue;
                }
                atenderConexao(socket, limiteClientes);
            }
        } catch (IOException e) {
            System.err.println("Nao foi possivel iniciar o servidor na porta " + PORTA
                    + " (porta em uso?).");
        }
    }

    private static void atenderConexao(Socket socket, int limite) {
        // Só a thread principal incrementa, então nunca passa do limite
        if (clientesAtivos.incrementAndGet() > limite) {
            clientesAtivos.decrementAndGet();
            rejeitarCliente(socket, limite);
            return;
        }
        try {
            new Thread(new ClienteHandler(socket)).start();
        } catch (RuntimeException | OutOfMemoryError e) {
            // Thread não arrancou, logo o finally do worker nunca vai correr
            clientesAtivos.decrementAndGet();
            fechar(socket);
            System.out.println("Nao foi possivel atender " + socket.getRemoteSocketAddress());
        }
    }

    private static void rejeitarCliente(Socket socket, int limite) {
        System.out.println("Ligacao recusada (limite atingido): " + socket.getRemoteSocketAddress());
        try (Socket s = socket;
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            out.println("<" + LocalTime.now().format(HORA) + ">: RECUSADO! Limite de "
                    + limite + " clientes excedido. Tente mais tarde.");
        } catch (IOException e) {
            // Cliente já foi embora
        }
    }

    private static void fechar(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    static class ClienteHandler implements Runnable {
        private final Socket socket;
        private final String id;
        private PrintWriter out;

        private volatile boolean monitorRodando = false;
        private Thread monitorThread;   // só mexido pela worker thread

        ClienteHandler(Socket socket) {
            this.socket = socket;
            this.id = String.valueOf(socket.getRemoteSocketAddress());
        }

        @Override
        public void run() {
            System.out.println("Cliente ligado: " + id + " | ativos: " + clientesAtivos.get());
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                out.println("<" + LocalTime.now().format(HORA)
                        + ">: CONECTADO!! Menu: CPU-<segundos>, memoria, Quit, Exit");

                String comando;
                // Loop de leitura de comandos do cliente
                while ((comando = in.readLine()) != null) {
                    comando = comando.trim();

                    if (comando.startsWith("CPU-")) {
                        iniciarMonitorCpu(comando);
                    } else if (comando.equalsIgnoreCase("memoria")) {
                        enviarMemoria();
                    } else if (comando.equals("Quit")) {
                        if (monitorRodando) {
                            pararMonitor();
                        } else {
                            out.println("Nenhum monitor em execucao.");
                        }
                    } else if (comando.equals("Exit")) {
                        break;
                    } else {
                        out.println("Comando desconhecido. Menu: CPU-<segundos>, memoria, Quit, Exit");
                    }
                }
            } catch (IOException e) {
                // Cliente caiu sem Exit (reset, terminal fechado, etc.)
                System.out.println("Cliente " + id + " desconectou de forma abrupta.");
            } catch (RuntimeException e) {
                System.out.println("Erro inesperado com " + id + ": " + e.getMessage());
            } finally {
                // Garante que a vaga é liberada, aconteça o que acontecer
                pararMonitor();
                fechar(socket);
                int restantes = clientesAtivos.decrementAndGet();
                System.out.println("Cliente desligado: " + id + " | ativos: " + restantes);
            }
        }

        private void iniciarMonitorCpu(String comando) {
            if (monitorRodando) {
                out.println("Ja existe um monitor CPU ativo. Use Quit para o parar.");
                return;
            }
            final int tempoSegundos;
            try {
                tempoSegundos = Integer.parseInt(comando.split("-", 2)[1].trim());
                if (tempoSegundos < 1) throw new NumberFormatException();
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                out.println("Formato invalido. Use CPU-<segundos> com segundos >= 1 (ex.: CPU-5).");
                return;
            }

            monitorRodando = true;
            monitorThread = new Thread(() -> {
                try {
                    while (monitorRodando) {
                        double cpu = osBean.getCpuLoad() * 100;
                        String valor = cpu < 0 || Double.isNaN(cpu) ? "N/D" : String.format("%.2f%%", cpu);
                        out.println("MONITOR CPU: " + valor);
                        // Cliente sumiu: não vale a pena continuar a enviar
                        if (out.checkError()) break;
                        Thread.sleep(tempoSegundos * 1000L);
                    }
                } catch (InterruptedException e) {
                    // interrompida por pararMonitor()
                }
                monitorRodando = false;
                out.println("Monitor CPU encerrado.");
            });
            monitorThread.setDaemon(true);
            monitorThread.start();
        }

        private void pararMonitor() {
            monitorRodando = false;
            if (monitorThread != null) {
                monitorThread.interrupt();
                monitorThread = null;
            }
        }

        // Envia informações de memória para o cliente
        private void enviarMemoria() {
            long total = osBean.getTotalPhysicalMemorySize();
            long livre = osBean.getFreePhysicalMemorySize();
            long usada = total - livre;
            double pct = total > 0 ? (usada * 100.0) / total : 0;
            out.println(String.format("MEMORIA: usada %d MB / total %d MB (livre %d MB) - %.2f%% em uso",
                    usada / (1024 * 1024), total / (1024 * 1024), livre / (1024 * 1024), pct));
        }
    }
}