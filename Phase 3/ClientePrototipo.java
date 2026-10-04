// ClientePrototipo.java
import java.io.*;
import java.net.*;
import java.util.Scanner;

public class ClientePrototipo {

    static final int PORTA = 12345;
    static final int TIMEOUT_CONEXAO_MS = 5000;

    // Distingue "saí eu (Exit)" de "o servidor fechou a ligação"
    static volatile boolean saidaVoluntaria = false;

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";

        Socket socket = conectar(host);
        if (socket == null) return;

        try (socket; Scanner teclado = new Scanner(System.in)) {
            executar(socket, teclado);
        } catch (IOException e) {
            if (!saidaVoluntaria) System.out.println("Erro de comunicacao com o servidor.");
        }
    }

    private static Socket conectar(String host) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, PORTA), TIMEOUT_CONEXAO_MS);
            return socket;
        } catch (UnknownHostException e) {
            System.out.println("Endereco do servidor invalido: " + host);
        } catch (SocketTimeoutException e) {
            System.out.println("Servidor nao respondeu a tempo.");
        } catch (ConnectException e) {
            System.out.println("Servidor indisponivel no momento.");
        } catch (IOException e) {
            System.out.println("Nao foi possivel conectar ao servidor.");
        } catch (IllegalArgumentException e) {
            System.out.println("Endereco do servidor invalido: " + host);
        }
        try { socket.close(); } catch (IOException ignored) { }
        return null;
    }

    private static void executar(Socket socket, Scanner teclado) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

        Thread leitor = new Thread(() -> {
            try {
                String mensagemServidor;
                while ((mensagemServidor = in.readLine()) != null) {
                    System.out.println(mensagemServidor);
                }
            } catch (IOException e) {
                // cai abaixo; só avisa se a saída não foi pedida por nós
            }
            // Servidor fechou (recusa, queda ou erro): termina, senão o teclado prende a main
            if (!saidaVoluntaria) {
                System.out.println("Ligacao terminada pelo servidor.");
                System.exit(0);
            }
        });
        leitor.setDaemon(true);
        leitor.start();

        while (teclado.hasNextLine()) {
            String comando = teclado.nextLine();
            out.println(comando);

            if (comando.trim().equals("Exit")) {
                saidaVoluntaria = true;
                return;
            }
            // PrintWriter engole IOException; checkError detecta a ligação caída
            if (out.checkError()) {
                if (!saidaVoluntaria) System.out.println("Conexao perdida com o servidor.");
                saidaVoluntaria = true;
                return;
            }
        }
        // EOF no teclado (Ctrl+D / Ctrl+Z)
        saidaVoluntaria = true;
    }
}