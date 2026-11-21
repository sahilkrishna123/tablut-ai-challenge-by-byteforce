package tablut;

import static tablut.Board.*;

/** Static evaluation, always from WHITE's point of view (positive = good for white). */
public final class Eval {
    /** Tunable weights . */
    public static int[] W = {
        150,   // 0 white pawn value
        100,   // 1 black pawn value
        25,    // 2 black pawn adjacent to king (penalty)
        15,    // 3 camp adjacent to king (penalty)
        12,    // 4 king closeness to edge
        4,     // 5 king mobility per square
        30,    // 6 per king-reachable square that gives one escape line
        150,   // 7 per king-reachable square that gives a double escape line (black to move)
        2500,  // 8 white to move and king can reach a double-threat square
        500,   // 9 black to move and king has exactly one open line
        4500,  // 10 black to move and king has >=2 open lines
        6,     // 11 white pawns near king (bonus)
        120,   // 12 king hostile-axis threat (penalty per axis)
        8,     // 13 black pawns on the 'guard ring' (bonus for black)
    };

    private static final int[] GUARD = new int[SQ];
    static {
        // squares that black likes to occupy to seal the escape corridors (diagonal ring)
        int[][] g = {{1,2},{2,1},{1,6},{2,7},{6,1},{7,2},{6,7},{7,6},{2,2},{2,6},{6,2},{6,6}};
        for (int[] rc : g) GUARD[rc[0] * 9 + rc[1]] = 1;
    }

    private Eval() {}

    public static int eval(Board bd) {
        final byte[] b = bd.b;
        final int k = bd.kingSq;
        int s = W[0] * bd.nWhite - W[1] * bd.nBlack;
        if (k < 0) return -20000;
        final boolean whiteToMove = bd.stm == WHITE_TURN;
        final int kr = k / 9, kc = k % 9;

        int lines = bd.escapeLines(k, -1);
        if (lines >= 2) s += whiteToMove ? 12000 : W[10];
        else if (lines == 1) s += whiteToMove ? 12000 : W[9];

        int t1 = 0, t2 = 0, mob = 0;
        for (int d = 0; d < 4; d++) {
            int c = NB[k][d];
            while (c >= 0 && b[c] == EMPTY && c != THRONE && !CAMP[c]) {
                mob++;
                if (!EDGE[c]) {
                    int l = bd.escapeLines(c, k);
                    if (l >= 2) t2++; else if (l == 1) t1++;
                }
                c = NB[c][d];
            }
        }
        s += W[5] * mob;
        s += W[6] * Math.min(t1, 6);
        if (t2 > 0) s += whiteToMove ? W[8] : W[7] * Math.min(t2, 4);

        int kd = Math.min(Math.min(kr, 8 - kr), Math.min(kc, 8 - kc));
        s += W[4] * (4 - kd);

        // neighbourhood of the king
        int hostileAxes = 0;
        for (int d = 0; d < 4; d++) {
            int m = NB[k][d];
            if (m < 0) continue;
            if (b[m] == BLACK) s -= W[2];
            else if (CAMP[m]) s -= W[3];
            else if (b[m] == WHITE) s += W[11];
        }
        if (k != THRONE && !ADJ_THRONE[k]) {
            // two-sided capture danger: one side hostile, other side reachable by a black pawn
            for (int ax = 0; ax < 2; ax++) {
                int a = NB[k][ax * 2], c = NB[k][ax * 2 + 1];
                if (a < 0 || c < 0) continue;
                boolean ha = b[a] == BLACK || CAMP[a];
                boolean hc = b[c] == BLACK || CAMP[c];
                if (ha && b[c] == EMPTY && c != THRONE && bd.blackCanReach(c)) hostileAxes++;
                else if (hc && b[a] == EMPTY && a != THRONE && bd.blackCanReach(a)) hostileAxes++;
            }
        }
        s -= W[12] * hostileAxes * (whiteToMove ? 1 : 6);

        // black guard ring
        int guard = 0;
        for (int sq = 0; sq < SQ; sq++) if (GUARD[sq] == 1 && b[sq] == BLACK) guard++;
        s -= W[13] * guard;
        return s;
    }

    /** True when the king is in immediate tactical danger/threat and black is to move (extend search). */
    public static boolean blackTactical(Board bd) {
        int k = bd.kingSq;
        if (k < 0) return false;
        if (bd.escapeLines(k, -1) > 0) return true;
        if (k == THRONE || ADJ_THRONE[k]) return false;
        final byte[] b = bd.b;
        for (int ax = 0; ax < 2; ax++) {
            int a = NB[k][ax * 2], c = NB[k][ax * 2 + 1];
            if (a < 0 || c < 0) continue;
            boolean ha = b[a] == BLACK || CAMP[a];
            boolean hc = b[c] == BLACK || CAMP[c];
            if (ha && b[c] == EMPTY && bd.blackCanReach(c)) return true;
            if (hc && b[a] == EMPTY && bd.blackCanReach(a)) return true;
        }
        return false;
    }
}
