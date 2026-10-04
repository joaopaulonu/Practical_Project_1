import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Servidor {
    private static final int PORTA = 12345;
    private static final AtomicInteger CLIENTES_ATIVOS = new AtomicInteger();
    private static final OperatingSystemMXBean OS_BEAN =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public static void main(String[] args) {
        // Verifica se informou o limite de clientes
        if (args.length != 1) {
            System.err.println("Uso: java Servidor <max_clientes>");
            return;
        }

        int limite;
        try {
            limite = Integer.parseInt(args[0]);
            if (limite < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("O limite deve ser um inteiro maior que zero.");
            return;
        }

        System.out.println("[SERVER] Iniciando Servidor de Monitoramento...");
        // Inicia o servidor
        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("[SERVER] Aguardando conexoes na porta " + PORTA
                    + " (limite: " + limite + ")...");
            
            while (true) {
                // Aguarda a conexão do cliente
                Socket clientSocket = serverSocket.accept();

                // Se passou do limite, recusa o cliente
                if (CLIENTES_ATIVOS.incrementAndGet() > limite) {
                    CLIENTES_ATIVOS.decrementAndGet();
                    rejeitarCliente(clientSocket, limite);
                    continue;
                }

                System.out.println("[SERVER] Cliente conectado de: " + clientSocket.getRemoteSocketAddress());
                
                // Atende o cliente em uma nova thread
                new Thread(new GerenciadorCliente(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("[SERVER-ERRO] Falha crítica no servidor: " + e.getMessage());
        }
    }

    // Recusa a conexão quando o limite é atingido
    private static void rejeitarCliente(Socket socket, int limite) {
        try (Socket recusado = socket;
             PrintWriter out = new PrintWriter(recusado.getOutputStream(), true)) {
            out.println("Limite de " + limite + " clientes atingido. Tente mais tarde.");
        } catch (IOException ignored) {
        }
    }


    private static class GerenciadorCliente implements Runnable {
        private final Socket socket;
        private final Map<String, MonitorTask> monitoresAtivos = new ConcurrentHashMap<>();

        public GerenciadorCliente(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
            ) {
                // Instrução
                String msgBoasVindas = String.format("<%s>: CONECTADO!! Menu: CPU-<seg>, memoria-<seg>, Quit, Exit", 
                        FormatadorData.obterHorarioAtual());
                out.println(msgBoasVindas);

                String comando;
                while ((comando = in.readLine()) != null) {
                    comando = comando.trim();

                    // Verifica se o comando é CPU-segundos ou memoria-segundos
                    if (comando.startsWith("CPU-") || comando.startsWith("memoria-")) {
                        iniciarMonitor(comando, out);
                    } else if (comando.equalsIgnoreCase("Quit")) {
                        pararTodosMonitores(out);
                    } else if (comando.equalsIgnoreCase("Exit")) {
                        out.println("[SERVER] Encerrando conexão por solicitação de Exit.");
                        break;
                    } else {
                        out.println("[SERVER-ERRO] Comando inválido! Use: CPU-<seg>, memoria-<seg>, Quit ou Exit.");
                    }
                }
            } catch (IOException e) {
                System.err.println("[SERVER] Cliente desconectado abruptamente.");
            } finally {
                pararTodosMonitores(null);
                fecharSocket();
                int restantes = CLIENTES_ATIVOS.decrementAndGet();
                System.out.println("[SERVER] Cliente desconectado. Ativos: " + restantes);
            }
        }

        private void iniciarMonitor(String comando, PrintWriter out) {
            // Divide o comando pelo "-"
            String[] partes = comando.split("-", 2);
            if (partes.length != 2) {
                out.println("[SERVER-ERRO] Sintaxe incorreta. Exemplo esperado: CPU-5 ou memoria-2");
                return;
            }

            String tipo = partes[0];
            int intervalo;

            try {
                intervalo = Integer.parseInt(partes[1]);
                if (intervalo <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                out.println("[SERVER-ERRO] O tempo de atualização deve ser um número inteiro positivo.");
                return;
            }

            // Para o monitor antigo do mesmo tipo antes de iniciar o novo
            MonitorTask anterior = monitoresAtivos.remove(tipo);
            if (anterior != null) {
                anterior.parar();
            }

            // Cria e inicia o novo monitoramento
            MonitorTask task = new MonitorTask(tipo, intervalo, out);
            monitoresAtivos.put(tipo, task);
            Thread threadMonitor = new Thread(task);
            task.definirThread(threadMonitor);
            threadMonitor.start();
            
            out.println("[SERVER] Monitor de " + tipo.toUpperCase() + " iniciado a cada " + intervalo + "s.");
        }

        private void pararTodosMonitores(PrintWriter out) {
            if (monitoresAtivos.isEmpty()) {
                if (out != null) out.println("[SERVER] Nenhum monitor ativo no momento.");
                return;
            }

            for (Map.Entry<String, MonitorTask> entry : monitoresAtivos.entrySet()) {
                entry.getValue().parar();
            }
            monitoresAtivos.clear();
            if (out != null) out.println("[SERVER] Todos os monitores foram interrompidos.");
        }

        private void fecharSocket() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                System.err.println("[SERVER] Erro ao fechar socket: " + e.getMessage());
            }
        }
    }

    private static class MonitorTask implements Runnable {
        private final String tipo;
        private final int intervaloSegundos;
        private final PrintWriter out;
        private volatile boolean executando = true;
        private Thread threadMonitor;
        private final OperatingSystemMXBean osBean;

        public MonitorTask(String tipo, int intervaloSegundos, PrintWriter out) {
            this.tipo = tipo;
            this.intervaloSegundos = intervaloSegundos;
            this.out = out;
            this.osBean = OS_BEAN;
        }

        public void parar() {
            this.executando = false;
            if (threadMonitor != null) {
                threadMonitor.interrupt();
            }
        }

        public void definirThread(Thread threadMonitor) {
            this.threadMonitor = threadMonitor;
        }

        // Envia as medições periodicamente
        @Override
        public void run() {
            while (executando) {
                try {
                    String dados = coletarMetrica();
                    out.println(String.format("[%s] %s", FormatadorData.obterHorarioAtual(), dados));
                    Thread.sleep(intervaloSegundos * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    out.println("[MONITOR-ERRO] Falha ao coletar métricas de " + tipo);
                    break;
                }
            }
        }

        // Consulta o uso de hardware (CPU e Memória)
        private String coletarMetrica() {
            if ("CPU".equalsIgnoreCase(tipo)) {
                double cpuLoad = osBean.getCpuLoad() * 100;
                if (cpuLoad < 0) cpuLoad = 0.0;
                return String.format("MÉTRICA CPU: Uso = %.2f%%", cpuLoad);
            } else if ("memoria".equalsIgnoreCase(tipo)) {
                long totalMem = osBean.getTotalMemorySize();
                long freeMem = osBean.getFreeMemorySize();
                long usedMem = totalMem - freeMem;
                double usoPorcentagem = ((double) usedMem / totalMem) * 100;
                
                return String.format("MÉTRICA MEMÓRIA: Uso = %.2f%% (%d MB / %d MB)", 
                        usoPorcentagem, usedMem / (1024 * 1024), totalMem / (1024 * 1024));
            }
            return "Métrica desconhecida.";
        }
    }

    private static class FormatadorData {
        private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

        public static String obterHorarioAtual() {
            return LocalDateTime.now().format(FORMATO_HORA);
        }
    }
}