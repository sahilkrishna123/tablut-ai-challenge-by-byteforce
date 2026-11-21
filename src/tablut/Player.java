package tablut;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Competition client.  Usage:  java -jar TablutAI.jar <WHITE|BLACK> <timeoutSeconds> <serverIP> [playerName]
 * Protocol (Java DataStreams): [int32 big-endian length][UTF-8 JSON]. White port 5800, black port 5801.
 */
public final class Player {
    static final int WHITE_PORT = 5800, BLACK_PORT = 5801;
    static final String DEFAULT_NAME = "Byte_Force";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: <WHITE|BLACK> [timeoutSeconds=60] [serverIP=localhost] [name]");
            System.exit(1);
        }
        String role = args[0].trim().toUpperCase();
        if (!role.equals("WHITE") && !role.equals("BLACK")) {
            System.err.println("Role must be WHITE or BLACK");
            System.exit(1);
        }
        int timeout = 60;
        if (args.length > 1) {
            try { timeout = Integer.parseInt(args[1].trim()); } catch (NumberFormatException e) { timeout = 60; }
        }
        String ip = args.length > 2 ? args[2].trim() : "localhost";
        String name = args.length > 3 ? args[3] : DEFAULT_NAME;
        boolean verbose = System.getenv("TABLUT_VERBOSE") != null;
        int depthLimit = 64;
        String dl = System.getenv("TABLUT_MAXDEPTH");
        if (dl != null) try { depthLimit = Integer.parseInt(dl); } catch (NumberFormatException ignored) {}
        new Player(role.equals("WHITE"), timeout, ip, name, verbose, depthLimit).run();
    }

    private final boolean white;
    private final int timeout;
    private final String ip, name;
    private final boolean verbose;
    private final int depthLimit;
    private DataInputStream in;
    private OutputStream out;

    Player(boolean white, int timeout, String ip, String name, boolean verbose, int depthLimit) {
        this.white = white; this.timeout = timeout; this.ip = ip; this.name = name;
        this.verbose = verbose; this.depthLimit = depthLimit;
    }

    /** Seconds we allow ourselves per move: safely below the server's timeout. */
    static double budget(int timeout) {
        double b = timeout * 0.78 - 1.5;
        if (b < 0.2) b = 0.2;
        if (b > 55) b = 55;
        return b;
    }

    private void send(String s) throws IOException {
        byte[] payload = s.getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[4 + payload.length];
        msg[0] = (byte) (payload.length >>> 24); msg[1] = (byte) (payload.length >>> 16);
        msg[2] = (byte) (payload.length >>> 8);  msg[3] = (byte) payload.length;
        System.arraycopy(payload, 0, msg, 4, payload.length);
        out.write(msg);
        out.flush();
    }

    private String receive() throws IOException {
        int len = in.readInt();
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private Socket connect() throws IOException, InterruptedException {
        int port = white ? WHITE_PORT : BLACK_PORT;
        IOException last = null;
        for (int i = 0; i < 300; i++) {           // wait up to 5 minutes for the server
            try {
                Socket s = new Socket(ip, port);
                s.setTcpNoDelay(true);
                return s;
            } catch (IOException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw last;
    }

    void run() throws Exception {
        Socket sock = connect();
        in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        out = sock.getOutputStream();
        send("\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        System.out.println("Connected as " + (white ? "WHITE" : "BLACK") + " (" + name + "), timeout=" + timeout
                + "s, budget=" + String.format("%.1f", budget(timeout)) + "s");

        Search search = new Search();
        search.verbose = verbose;
        search.maxDepthLimit = depthLimit;
        Set<Long> history = new HashSet<Long>();
        int prevPieces = -1;
        boolean firstState = true;
        int myTurn = white ? 0 : 1;
        int[] turn = new int[1];

        while (true) {
            String js;
            try {
                js = receive();
            } catch (EOFException | java.net.SocketException e) {
                System.out.println("Server closed the connection.");
                break;
            }
            Board bd = Board.fromJson(js, turn);
            if (turn[0] >= 2) {
                String r = turn[0] == 2 ? "WHITE WINS" : turn[0] == 3 ? "BLACK WINS" : "DRAW";
                System.out.println("Game over: " + r);
                break;
            }
            int pieces = bd.nWhite + bd.nBlack + 1;
            if (!firstState) {
                if (pieces != prevPieces) history.clear();   // capture => no repetition possible
                history.add(bd.hash);
            }
            prevPieces = pieces;
            firstState = false;

            if (turn[0] != myTurn) continue;

            long t0 = System.nanoTime();
            int mv = -1;
            try {
                search.setHistory(history);
                mv = search.bestMove(bd, budget(timeout));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            if (mv < 0 || !bd.isLegal(mv >> 7, mv & 127)) {      // safety net: never send an illegal move
                int[] list = new int[256];
                int n = bd.gen(list);
                if (n > 0) mv = list[new java.util.Random().nextInt(n)];
                else mv = (4 << 7) | 4;
            }
            String from = Board.sqName(mv >> 7), to = Board.sqName(mv & 127);
            String json = "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"turn\":\"" + (white ? "WHITE" : "BLACK") + "\"}";
            send(json);
            System.out.printf("%s %s->%s  depth=%d score=%d nodes=%d  %.1fs%n", white ? "W" : "B", from, to,
                    search.lastDepth, search.lastScore, search.lastNodes, (System.nanoTime() - t0) / 1e9);
        }
        try { sock.close(); } catch (IOException ignored) {}
    }
}
