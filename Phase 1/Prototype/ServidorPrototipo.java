import java.io.*;
import java.net.*;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;

public class ServidorPrototipo {
    static volatile boolean monitorRodando = false; 

    public static void main(String[] args) throws Exception {
        // Inicia o servidor escutando a porta 12345
        ServerSocket server = new ServerSocket(12345);
        System.out.println("Servidor aguardando conexao na porta 12345...");
        
        // Aguarda a conexão do cliente
        Socket socket = server.accept();
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        
        // consulta o uso de hardware (CPU e Memória)
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        //instrucão
        out.println("<12:00>: CONECTADO!! Menu: CPU-segundos, MEM-segundos, Quit, Exit");


        String comando;
        while ((comando = in.readLine()) != null) {
            
            // Divide o comando pelo "-"
            String[] partesComando = comando.split("-", -1);

            //ve se o comando é CPU-segundos ou MEM-segundos
            if (partesComando.length == 2
                    && (partesComando[0].equals("CPU") || partesComando[0].equals("MEM"))) {
                try {
                    int tempoSegundos = Integer.parseInt(partesComando[1]);
                    if (tempoSegundos <= 0) {
                        out.println("O intervalo deve ser maior que zero.");
                        continue;
                    }

                    monitorRodando = false;
                    boolean monitorarCpu = partesComando[0].equals("CPU");
                    monitorRodando = true;

                    // envia as medições periodicamente
                    new Thread(() -> {
                        try {
                            while (monitorRodando) {
                                if (monitorarCpu) {
                                    double cpu = osBean.getCpuLoad() * 100;
                                    out.println("MONITOR CPU: " + String.format("%.2f", cpu) + "%");
                                } else {
                                    long totalMemoria = osBean.getTotalMemorySize();
                                    long memoriaLivre = osBean.getFreeMemorySize();
                                    double memoriaUsada = (double) (totalMemoria - memoriaLivre) / totalMemoria * 100;
                                    out.println("MONITOR MEMORIA: " + String.format("%.2f", memoriaUsada) + "%");
                                }
                                Thread.sleep(tempoSegundos * 1000L);
                            }
                            out.println("Monitor encerrado.");
                        } catch (Exception e) {
                            out.println("Erro no monitoramento.");
                        }
                    }).start();
                } catch (NumberFormatException e) {
                    out.println("Intervalo invalido. Use CPU-segundos ou MEM-segundos.");
                }
            } else if (comando.equals("Quit")) {
                // Para o monitoramento
                monitorRodando = false;
            } else if (comando.equals("Exit")) {
                // Desconecta e encerra o servidor
                break;
            } else {
                out.println("Comando invalido. Use CPU-segundos, MEM-segundos, Quit ou Exit.");
            }
        }
        
        System.out.println("Encerrando servidor...");
        socket.close();
        server.close();
    }
}