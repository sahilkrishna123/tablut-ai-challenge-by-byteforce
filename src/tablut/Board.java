package tablut;

/**
 * Fast Tablut (Ashton rules) board.  The rules are a faithful re-implementation of the
 * competition server (GameAshtonTablut.java), including its quirks:
 *  - the throne (e5) can never be entered or crossed, not even by the king;
 *  - camps (citadels) can only be entered/crossed by a pawn that STARTS the move inside a camp;
 *  - black pawn is captured against: white pawn, king, throne, or a camp cell (except a5,e1,i5,e9);
 *  - white pawn is captured against: black pawn, throne, or ANY camp cell;
 *  - king: on throne 4 blacks, next to throne 3 blacks, elsewhere 2 blacks / black+camp;
 *  - king wins on ANY border square (camps are unreachable anyway, corners count).
 * Squares are indexed row*9+col, row 0 = rank "1", col 0 = file "a" (e.g. "e3" = row 2, col 4).
 */
public final class Board {
    public static final int EMPTY = 0, WHITE = 1, BLACK = 2, KING = 3;
    public static final int SQ = 81, THRONE = 40;
    public static final int WHITE_TURN = 0, BLACK_TURN = 1;
    public static final int ONGOING = 0, WHITE_WIN = 1, BLACK_WIN = 2;

    public static final boolean[] CAMP = new boolean[SQ];
    public static final boolean[] MIDCAMP = new boolean[SQ];
    public static final boolean[] EDGE = new boolean[SQ];
    public static final boolean[] ADJ_THRONE = new boolean[SQ];
    /** NB[sq][d]: neighbour in direction d (0 up(row-1), 1 down, 2 left, 3 right) or -1. */
    public static final int[][] NB = new int[SQ][4];
    private static final long[][] Z = new long[SQ][4];
    private static final long Z_SIDE;

    static {
        int[] camps = {3, 4, 5, 13, 27, 36, 45, 37, 35, 44, 53, 43, 75, 76, 77, 67};
        for (int c : camps) CAMP[c] = true;
        MIDCAMP[4] = MIDCAMP[36] = MIDCAMP[44] = MIDCAMP[76] = true;
        for (int sq = 0; sq < SQ; sq++) {
            int r = sq / 9, c = sq % 9;
            EDGE[sq] = r == 0 || r == 8 || c == 0 || c == 8;
            NB[sq][0] = r > 0 ? sq - 9 : -1;
            NB[sq][1] = r < 8 ? sq + 9 : -1;
            NB[sq][2] = c > 0 ? sq - 1 : -1;
            NB[sq][3] = c < 8 ? sq + 1 : -1;
        }
        ADJ_THRONE[31] = ADJ_THRONE[49] = ADJ_THRONE[39] = ADJ_THRONE[41] = true;
        java.util.Random rnd = new java.util.Random(0x7AB1A7L);
        for (int i = 0; i < SQ; i++) for (int p = 0; p < 4; p++) Z[i][p] = rnd.nextLong();
        Z_SIDE = rnd.nextLong();
    }

    public final byte[] b = new byte[SQ];
    public int stm = WHITE_TURN;
    public int kingSq = THRONE;
    public int nWhite = 8, nBlack = 16;   // pawns only (king excluded)
    public long hash;
    public int result = ONGOING;

    // ---- undo stack ----
    private static final int MAXP = 256;
    private final int[] uFrom = new int[MAXP], uTo = new int[MAXP], uPiece = new int[MAXP];
    private final int[] uNCap = new int[MAXP], uKing = new int[MAXP], uRes = new int[MAXP];
    private final long[] uHash = new long[MAXP];
    private final int[] uCapSq = new int[MAXP * 4], uCapPc = new int[MAXP * 4];
    private int sp = 0;
    /** number of pawns captured by the last doMove */
    public int lastCaps = 0;

    public Board() { setInitial(); }

    public void setInitial() {
        java.util.Arrays.fill(b, (byte) EMPTY);
        b[THRONE] = KING;
        int[] w = {22, 31, 49, 58, 38, 39, 41, 42};
        for (int s : w) b[s] = WHITE;
        for (int c : new int[]{3, 4, 5, 13, 27, 36, 45, 37, 35, 44, 53, 43, 75, 76, 77, 67}) b[c] = BLACK;
        stm = WHITE_TURN;
        sp = 0;
        recount();
    }

    /** Recompute counters, king square and hash from the byte array. */
    public void recount() {
        nWhite = nBlack = 0;
        kingSq = -1;
        long h = 0;
        for (int i = 0; i < SQ; i++) {
            int p = b[i];
            if (p == WHITE) nWhite++;
            else if (p == BLACK) nBlack++;
            else if (p == KING) kingSq = i;
            if (p != EMPTY) h ^= Z[i][p];
        }
        if (stm == BLACK_TURN) h ^= Z_SIDE;
        hash = h;
        result = ONGOING;
        sp = 0;
    }

    public Board copy() {
        Board c = new Board();
        System.arraycopy(b, 0, c.b, 0, SQ);
        c.stm = stm;
        c.recount();
        return c;
    }

    /** Hash of the current position as seen by the server's draw detection (board + turn). */
    public long positionHash() { return hash; }

    // ------------------------------------------------------------------ move generation
    /** Generates all legal moves of the side to move. Move = (from<<7)|to. Returns count. */
    public int gen(int[] out) {
        int n = 0;
        final boolean white = stm == WHITE_TURN;
        for (int sq = 0; sq < SQ; sq++) {
            int p = b[sq];
            if (white ? (p != WHITE && p != KING) : (p != BLACK)) continue;
            final boolean fromCamp = CAMP[sq];
            final int[] nb = NB[sq];
            for (int d = 0; d < 4; d++) {
                int c = nb[d];
                int steps = 1;
                while (c >= 0) {
                    if (b[c] != EMPTY || c == THRONE) break;
                    if (CAMP[c]) {
                        if (!fromCamp) break;
                        // server quirk: camp-to-camp moves longer than 5 squares are rejected
                        if (steps > 5) { c = NB[c][d]; steps++; continue; }
                    }
                    out[n++] = (sq << 7) | c;
                    c = NB[c][d];
                    steps++;
                }
            }
        }
        return n;
    }

    public boolean isLegal(int from, int to) {
        int[] mv = new int[256];
        int n = gen(mv);
        int m = (from << 7) | to;
        for (int i = 0; i < n; i++) if (mv[i] == m) return true;
        return false;
    }

    // ------------------------------------------------------------------ make / unmake
    public void doMove(int from, int to) {
        final int piece = b[from];
        uFrom[sp] = from; uTo[sp] = to; uPiece[sp] = piece;
        uKing[sp] = kingSq; uHash[sp] = hash; uRes[sp] = result;
        int ncap = 0;
        final int base = sp * 4;

        b[from] = EMPTY;
        b[to] = (byte) piece;
        hash ^= Z[from][piece] ^ Z[to][piece];
        if (piece == KING) kingSq = to;

        final boolean blackMoves = piece == BLACK;
        final int[] nbTo = NB[to];
        for (int d = 0; d < 4; d++) {
            int n = nbTo[d];
            if (n < 0) continue;
            int pn = b[n];
            if (pn == EMPTY) continue;
            int f = NB[n][d];
            if (blackMoves) {
                if (pn == WHITE) {
                    if (f >= 0 && (b[f] == BLACK || f == THRONE || CAMP[f])) {
                        uCapSq[base + ncap] = n; uCapPc[base + ncap] = WHITE; ncap++;
                        b[n] = EMPTY; hash ^= Z[n][WHITE]; nWhite--;
                    }
                } else if (pn == KING) {
                    if (kingCaptured(n, d)) result = BLACK_WIN;
                }
            } else {
                if (pn == BLACK) {
                    if (f >= 0 && (b[f] == WHITE || b[f] == KING || f == THRONE
                            || (CAMP[f] && !MIDCAMP[f]))) {
                        uCapSq[base + ncap] = n; uCapPc[base + ncap] = BLACK; ncap++;
                        b[n] = EMPTY; hash ^= Z[n][BLACK]; nBlack--;
                    }
                }
            }
        }
        if (!blackMoves && piece == KING && EDGE[to]) result = WHITE_WIN;
        uNCap[sp] = ncap;
        lastCaps = ncap;
        sp++;
        stm ^= 1;
        hash ^= Z_SIDE;
    }

    private boolean kingCaptured(int n, int d) {
        if (n == THRONE) {
            for (int e = 0; e < 4; e++) if (b[NB[n][e]] != BLACK) return false;
            return true;
        }
        if (ADJ_THRONE[n]) {
            for (int e = 0; e < 4; e++) {
                int m = NB[n][e];
                if (m == THRONE) continue;
                if (b[m] != BLACK) return false;
            }
            return true;
        }
        int f = NB[n][d];
        return f >= 0 && (b[f] == BLACK || CAMP[f]);
    }

    public void undo() {
        sp--;
        int from = uFrom[sp], to = uTo[sp], piece = uPiece[sp];
        b[to] = EMPTY;
        b[from] = (byte) piece;
        int base = sp * 4;
        for (int i = 0; i < uNCap[sp]; i++) {
            int sq = uCapSq[base + i], pc = uCapPc[base + i];
            b[sq] = (byte) pc;
            if (pc == WHITE) nWhite++; else nBlack++;
        }
        kingSq = uKing[sp];
        hash = uHash[sp];
        result = uRes[sp];
        stm ^= 1;
    }

    // ------------------------------------------------------------------ helpers
    /** Number of directions in which the king has a free run to a border square. */
    public int escapeLines(int from, int ignore) {
        int cnt = 0;
        for (int d = 0; d < 4; d++) {
            int cur = from;
            while (true) {
                int nx = NB[cur][d];
                if (nx < 0) break;
                if (b[nx] != EMPTY && nx != ignore) break;
                if (nx == THRONE || CAMP[nx]) break;
                if (EDGE[nx]) { cnt++; break; }
                cur = nx;
            }
        }
        return cnt;
    }

    /** True if some black pawn could legally slide onto square c in one move. */
    public boolean blackCanReach(int c) {
        for (int d = 0; d < 4; d++) {
            int cur = NB[c][d];
            boolean campSeen = false;
            while (cur >= 0 && b[cur] == EMPTY && cur != THRONE) {
                if (CAMP[cur]) campSeen = true;
                cur = NB[cur][d];
            }
            if (cur >= 0 && b[cur] == BLACK) {
                if (!CAMP[c] && !campSeen) return true;
                if (CAMP[cur]) return true; // black starting inside a camp may enter camp cells
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ text / protocol helpers
    public static String sqName(int sq) {
        return "" + (char) ('a' + sq % 9) + (char) ('1' + sq / 9);
    }

    public static int sqFromName(String s) {
        return (s.charAt(1) - '1') * 9 + (Character.toLowerCase(s.charAt(0)) - 'a');
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 8; r >= 0; r--) {
            sb.append(r + 1).append(' ');
            for (int c = 0; c < 9; c++) {
                int sq = r * 9 + c;
                char ch = ".WBK".charAt(b[sq]);
                if (b[sq] == EMPTY && sq == THRONE) ch = 'T';
                else if (b[sq] == EMPTY && CAMP[sq]) ch = '+';
                sb.append(ch).append(' ');
            }
            sb.append('\n');
        }
        sb.append("  a b c d e f g h i  ").append(stm == WHITE_TURN ? "WHITE" : "BLACK").append(" to move");
        return sb.toString();
    }

    /** Parse the server's JSON state: {"board":[[...9 strings...]x9],"turn":"WHITE"}. */
    public static Board fromJson(String json, int[] turnOut) {
        Board bd = new Board();
        java.util.Arrays.fill(bd.b, (byte) EMPTY);
        int bi = json.indexOf("\"board\"");
        int start = json.indexOf('[', bi);
        int depth = 0, end = start;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '[') depth++;
            else if (ch == ']') { depth--; if (depth == 0) { end = i; break; } }
        }
        String arr = json.substring(start, end + 1);
        int idx = 0, pos = 0;
        while (idx < SQ) {
            int q1 = arr.indexOf('"', pos);
            if (q1 < 0) throw new IllegalArgumentException("bad board json");
            int q2 = arr.indexOf('"', q1 + 1);
            String tok = arr.substring(q1 + 1, q2);
            pos = q2 + 1;
            int v;
            switch (tok) {
                case "WHITE": v = WHITE; break;
                case "BLACK": v = BLACK; break;
                case "KING": v = KING; break;
                default: v = EMPTY; // EMPTY, THRONE
            }
            bd.b[idx++] = (byte) v;
        }
        int ti = json.indexOf("\"turn\"");
        int q1 = json.indexOf('"', json.indexOf(':', ti) + 1);
        int q2 = json.indexOf('"', q1 + 1);
        String turn = json.substring(q1 + 1, q2);
        // turnOut[0]: 0 white,1 black,2 whitewin,3 blackwin,4 draw
        switch (turn) {
            case "WHITE": turnOut[0] = 0; bd.stm = WHITE_TURN; break;
            case "BLACK": turnOut[0] = 1; bd.stm = BLACK_TURN; break;
            case "WHITEWIN": turnOut[0] = 2; break;
            case "BLACKWIN": turnOut[0] = 3; break;
            default: turnOut[0] = 4; break;
        }
        bd.recount();
        return bd;
    }
}
