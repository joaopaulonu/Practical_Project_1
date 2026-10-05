// ServidorPrototipo.java
import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public class ServidorPrototipo {
    // Porta onde o servidor escuta
    static final int PORTA = 12345;
    // Formato de hora para mensagens
    static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    // Contador de clientes ativos
    static final AtomicInteger clientesAtivos = new AtomicInteger(0);
    // Registro compartilhado: main adiciona handlers e workers removem ao terminar.
    static final List<ClienteHandler> handlers = Collections.synchronizedList(new ArrayList<>());
    // Bean para obter informações do sistema operacional
    static final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    // O método main do servidor recebe como argumento o número máximo de clientes simultâneos.
    public static void main(String[] args) {
        // Verifica se o argumento(<max_clientes>) foi fornecido corretamente
        if (args.length != 1) {
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
        // Reserva na main para que workers concorrentes nunca ultrapassem o limite.
        boolean vagaReservada = clientesAtivos.incrementAndGet() <= limite;
        if (!vagaReservada) clientesAtivos.decrementAndGet();
        ClienteHandler handler = null;
        try {
            handler = new ClienteHandler(socket, limite, vagaReservada);
            handlers.add(handler);
            new Thread(handler, "cliente-" + socket.getPort()).start();
        } catch (RuntimeException | OutOfMemoryError e) {
            // Worker não arrancou: a main desfaz o registro e a reserva.
            if (handler != null) handlers.remove(handler);
            if (vagaReservada) clientesAtivos.decrementAndGet();
            fechar(socket);
            System.out.println("Nao foi possivel atender " + socket.getRemoteSocketAddress());
        }
    }

    private static void rejeitarCliente(Socket socket, int limite) {
        System.out.println("Ligacao recusada (limite atingido): " + socket.getRemoteSocketAddress());
        try (Socket s = socket;
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
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
        private final int limite;
        private final boolean vagaReservada;
        private PrintWriter out;

        // Apenas a worker altera o mapa. Cada tarefa possui sua própria flag.
        private final Map<String, MonitorTask> monitores = new HashMap<>();
        private static final String MENU = "Menu: CPU-<segundos>, memoria-<segundos>, memoria, Quit, Exit";

        ClienteHandler(Socket socket, int limite, boolean vagaReservada) {
            this.socket = socket;
            this.id = String.valueOf(socket.getRemoteSocketAddress());
            this.limite = limite;
            this.vagaReservada = vagaReservada;
        }

        @Override
        public void run() {
            try {
                // A recusa também ocorre na worker, mantendo accept livre de envios.
                if (!vagaReservada) {
                    rejeitarCliente(socket, limite);
                    return;
                }
                System.out.println("Cliente ligado: " + id + " | ativos: " + clientesAtivos.get());
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

                out.println("<" + LocalTime.now().format(HORA)
                        + ">: CONECTADO!! " + MENU);
                if (out.checkError()) return;

                String comando;
                // Loop de leitura de comandos do cliente
                while ((comando = in.readLine()) != null) {
                    comando = comando.trim();

                    if (comando.regionMatches(true, 0, "CPU-", 0, 4)
                            || comando.regionMatches(true, 0, "memoria-", 0, 8)) {
                        iniciarMonitor(comando);
                    } else if (comando.equalsIgnoreCase("memoria")) {
                        enviarMemoria();
                    } else if (comando.equalsIgnoreCase("Quit")) {
                        boolean haviaMonitores = !monitores.isEmpty();
                        pararMonitores();
                        enviar(haviaMonitores ? "Todos os monitores foram interrompidos." : "Nenhum monitor em execucao.");
                    } else if (comando.equalsIgnoreCase("Exit")) {
                        pararMonitores();
                        enviar("Conexao encerrada por solicitacao do cliente.");
                        break;
                    } else {
                        enviar("Comando desconhecido. " + MENU);
                    }
                }
            } catch (IOException e) {
                // Cliente caiu sem Exit (reset, terminal fechado, etc.)
                System.out.println("Cliente " + id + " desconectou de forma abrupta.");
            } catch (RuntimeException e) {
                System.out.println("Erro inesperado com " + id + ": " + e.getMessage());
            } finally {
                // Garante que a vaga é liberada, aconteça o que acontecer
                fechar(socket);
                pararMonitores();
                handlers.remove(this);
                // Recusados não ocuparam vaga e não podem diminuir o contador.
                if (vagaReservada) {
                    int restantes = clientesAtivos.decrementAndGet();
                    System.out.println("Cliente desligado: " + id + " | ativos: " + restantes);
                }
            }
        }

        private void iniciarMonitor(String comando) {
            String[] partes = comando.split("-", 2);
            String tipo = partes[0].toLowerCase(Locale.ROOT);
            final int tempoSegundos;
            try {
                tempoSegundos = Integer.parseInt(partes[1].trim());
                if (tempoSegundos < 1) throw new NumberFormatException();
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                enviar("Formato invalido. Use CPU-<segundos> ou memoria-<segundos> com segundos >= 1.");
                return;
            }

            MonitorTask anterior = monitores.remove(tipo);
            if (anterior != null) anterior.parar();
            MonitorTask tarefa = new MonitorTask(tipo, tempoSegundos);
            monitores.put(tipo, tarefa);
            try {
                enviar("Monitor " + tipo + " iniciado a cada " + tempoSegundos + "s.");
                tarefa.thread.start();
            } catch (RuntimeException | OutOfMemoryError e) {
                monitores.remove(tipo);
                tarefa.parar();
                enviar("Nao foi possivel iniciar o monitor " + tipo + ".");
            }
        }

        private void pararMonitores() {
            for (MonitorTask tarefa : monitores.values()) tarefa.parar();
            monitores.clear();
        }

        // Serializa mensagens de CPU, memoria e comandos no mesmo socket.
        private synchronized boolean enviar(String mensagem) {
            out.println(mensagem);
            if (out.checkError()) {
                fechar(socket); // Desbloqueia readLine e libera a vaga no finally da worker.
                return false;
            }
            return true;
        }

        private final class MonitorTask implements Runnable {
            private final String tipo;
            private final int intervalo;
            private volatile boolean executando = true;
            private final Thread thread;

            MonitorTask(String tipo, int intervalo) {
                this.tipo = tipo;
                this.intervalo = intervalo;
                thread = new Thread(this, "monitor-" + tipo + "-" + id);
                thread.setDaemon(true);
            }

            void parar() {
                executando = false;
                thread.interrupt();
                // Aguarda o termino antes de confirmar Quit ou substituir o monitor.
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void run() {
                try {
                    while (executando && !socket.isClosed()) {
                        if (tipo.equals("cpu")) {
                            double cpu = osBean.getCpuLoad() * 100;
                            String valor = cpu < 0 || !Double.isFinite(cpu) ? "N/D" : String.format("%.2f%%", cpu);
                            if (!enviar("MONITOR CPU: " + valor)) break;
                        } else if (!enviarMemoria()) {
                            break;
                        }
                        Thread.sleep(intervalo * 1000L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    enviar("Nao foi possivel coletar a metrica de " + tipo + ".");
                } finally {
                    executando = false;
                }
            }
        }

        // Envia informações de memória para o cliente
        private boolean enviarMemoria() {
            long total = osBean.getTotalMemorySize();
            long livre = osBean.getFreeMemorySize();
            if (total <= 0 || livre < 0) return enviar("MEMORIA: N/D");
            long usada = total - livre;
            double pct = total > 0 ? (usada * 100.0) / total : 0;
            return enviar(String.format("MEMORIA: usada %d MB / total %d MB (livre %d MB) - %.2f%% em uso",
                    usada / (1024 * 1024), total / (1024 * 1024), livre / (1024 * 1024), pct));
        }
    }
}
