import java.io.*;
import java.net.*;
import java.util.Scanner;

public class ClientePrototipo {
    public static void main(String[] args) throws Exception {
        // Conecta ao servidor local no caso 127.0.0.1 na porta 12345
        Socket socket = new Socket("127.0.0.1", 12345);
        
        // Cria leitor de mensagens do servidor e emissor de mensagens do cliente
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        Scanner teclado = new Scanner(System.in);

        //exibi o que o servidor enviar
        new Thread(() -> {
            try {
                String mensagemServidor;
                while ((mensagemServidor = in.readLine()) != null) {
                    System.out.println(mensagemServidor);
                }
            } catch (Exception e) {
                System.out.println("Conexao fechada.");
            }
        }).start();

        // Envia os comandos digitados para o servidor
        while (true) {
            String comando = teclado.nextLine();
            out.println(comando);
            
            if (comando.equals("Exit")) {
                break;
            }
        }
        
        socket.close();
        teclado.close();
    }
}