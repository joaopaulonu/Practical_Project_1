"""Testes de integracao com Java e sockets reais. Requer Python 3 e JDK 14+."""
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
CLIENTES = []


class Conexao:
    def __init__(self):
        self.socket = socket.create_connection(("127.0.0.1", 12345), timeout=2)
        self.socket.settimeout(0.1)
        self.buffer = b""
        CLIENTES.append(self)

    def enviar(self, comando):
        self.socket.sendall((comando + "\n").encode("utf-8"))

    def ler(self, segundos=0.3):
        fim = time.monotonic() + segundos
        linhas = []
        while time.monotonic() < fim:
            try:
                dados = self.socket.recv(4096)
                if not dados:
                    break
                self.buffer += dados
                while b"\n" in self.buffer:
                    linha, self.buffer = self.buffer.split(b"\n", 1)
                    linhas.append(linha.decode("utf-8").strip())
            except socket.timeout:
                pass
        return linhas

    def fechar(self, abrupto=False):
        if abrupto:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("hh", 1, 0))
        self.socket.close()


def exigir(condicao, nome):
    if not condicao:
        raise AssertionError(nome)
    print("OK:", nome, flush=True)


def contem(linhas, texto):
    return any(texto in linha for linha in linhas)


def executar():
    # Evita confundir outro servidor aberto com o processo deste teste.
    with socket.socket() as porta:
        porta.bind(("127.0.0.1", 12345))
    with tempfile.TemporaryDirectory(prefix="redes-testes-") as pasta:
        subprocess.run([
            "javac", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", pasta,
            str(ROOT / "Phase 3" / "ServidorPrototipo.java"),
            str(ROOT / "Phase 3" / "ClientePrototipo.java"),
        ], check=True)
        exigir(True, "compilacao sem erros nem avisos")

        def java(classe, *args):
            return ["java", "-cp", pasta, classe, *args]

        for args in [[], ["0"], ["abc"], ["2", "extra"]]:
            r = subprocess.run(java("ServidorPrototipo", *args), capture_output=True, timeout=5)
            exigir(r.returncode == 0 and b"Exception" not in r.stderr and r.stderr,
                   "argumentos invalidos: " + str(args))

        with tempfile.TemporaryFile() as log:
            servidor = subprocess.Popen(java("ServidorPrototipo", "2"), stdout=log, stderr=log)
            processos = []
            try:
                time.sleep(0.6)
                exigir(servidor.poll() is None, "servidor inicia")
                a, b = Conexao(), Conexao()
                exigir(contem(a.ler(), "CONECTADO!!") and contem(b.ler(), "CONECTADO!!"),
                       "dois clientes recebem boas-vindas")
                c = Conexao()
                exigir(contem(c.ler(), "RECUSADO!"), "terceiro cliente recusado")
                c.fechar()
                r = subprocess.run(java("ServidorPrototipo", "2"), capture_output=True, timeout=5)
                exigir(b"Nao foi possivel iniciar" in r.stderr and b"Exception" not in r.stderr,
                       "porta ocupada sem stack trace")

                a.enviar("CPU-1")
                a.enviar("memoria-1")
                dados = a.ler(2.2)
                exigir(sum("MONITOR CPU:" in l for l in dados) >= 2
                       and sum("MEMORIA:" in l for l in dados) >= 2,
                       "CPU e memoria periodicas simultaneas")
                b.enviar("CPU-1")
                exigir(contem(b.ler(1.2), "MONITOR CPU:"), "monitor do segundo cliente")
                a.enviar("Quit")
                exigir(contem(a.ler(), "Todos os monitores foram interrompidos."), "Quit confirmado")
                exigir(not contem(a.ler(1.2), "MONITOR CPU:")
                       and not contem(a.ler(), "MEMORIA:"), "Quit interrompe todos os monitores")
                exigir(contem(b.ler(), "MONITOR CPU:"), "Quit de A nao interrompe B")
                a.enviar("memoria")
                exigir(contem(a.ler(), "MEMORIA:"), "consulta unica de memoria")
                for comando in ["CPU-", "CPU-abc", "CPU-0", "CPU--1", "memoria-0", "CPU-2147483648"]:
                    a.enviar(comando)
                    exigir(contem(a.ler(0.15), "Formato invalido"), "entrada invalida: " + comando)
                a.enviar("aleatorio")
                exigir(contem(a.ler(), "Comando desconhecido"), "comando desconhecido")
                a.enviar("Quit")
                exigir(contem(a.ler(), "Nenhum monitor"), "Quit sem monitor")

                # Comandos consecutivos exercitam a corrida entre a tarefa antiga e a nova.
                a.enviar("CPU-1")
                for _ in range(30):
                    a.enviar("Quit")
                    a.enviar("CPU-1")
                dados = a.ler(2.2)
                exigir(sum("MONITOR CPU:" in l for l in dados[-6:]) >= 2,
                       "reinicio rapido de monitor continua ativo")
                a.enviar("CPU-2")
                a.ler(0.4)
                exigir(not contem(a.ler(1.2), "MONITOR CPU:"), "novo intervalo substitui monitor antigo")
                a.enviar("Exit")
                exigir(contem(a.ler(), "Conexao encerrada por solicitacao"), "Exit confirmado pelo servidor")
                a.fechar()
                time.sleep(0.2)
                d = Conexao()
                exigir(contem(d.ler(), "CONECTADO!!"), "vaga liberada apos Exit")
                b.fechar(abrupto=True)
                time.sleep(0.3)
                e = Conexao()
                exigir(contem(e.ler(), "CONECTADO!!"), "vaga liberada apos queda abrupta")
                d.fechar()
                e.fechar()
                time.sleep(0.3)

                r = subprocess.run(java("ClientePrototipo"), input=b"Exit\n", capture_output=True, timeout=8)
                exigir(b"Conexao encerrada por solicitacao" in r.stdout
                       and b"Ligacao terminada pelo servidor" not in r.stdout and not r.stderr,
                       "cliente aguarda Exit sem falso aviso de queda")
                for _ in range(2):
                    p = subprocess.Popen(java("ClientePrototipo"), stdin=subprocess.PIPE,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    processos.append(p)
                time.sleep(0.6)
                recusado = subprocess.Popen(java("ClientePrototipo"), stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                processos.append(recusado)
                recusado.wait(timeout=5)
                out, err = recusado.communicate(timeout=2)
                exigir(b"RECUSADO!" in out and not err, "cliente recusado termina sem esperar teclado")
                servidor.terminate()
                servidor.wait(timeout=5)
                for p in processos[:2]:
                    p.wait(timeout=5)
                    out, err = p.communicate(timeout=2)
                    exigir(b"Ligacao terminada pelo servidor" in out and not err,
                           "cliente termina apos queda do servidor sem esperar teclado")
                log.seek(0)
                dados = log.read()
                exigir(b"Exception in thread" not in dados and b"warning:" not in dados,
                       "servidor sem erros de runtime nos cenarios testados")
            finally:
                for cliente in CLIENTES:
                    cliente.fechar()
                for p in processos:
                    if p.poll() is None:
                        p.kill()
                    p.communicate(timeout=5)
                if servidor.poll() is None:
                    servidor.terminate()
                servidor.wait(timeout=5)

        r = subprocess.run(java("ClientePrototipo"), capture_output=True, timeout=8)
        exigir(b"Servidor indisponivel" in r.stdout and not r.stderr, "servidor offline")
        r = subprocess.run(java("ClientePrototipo", "host-inexistente.invalid"), capture_output=True, timeout=10)
        exigir(b"Endereco do servidor invalido" in r.stdout and not r.stderr, "host invalido")
    print("Todos os testes passaram. Validacao em duas maquinas deve ser feita separadamente.")


if __name__ == "__main__":
    executar()
