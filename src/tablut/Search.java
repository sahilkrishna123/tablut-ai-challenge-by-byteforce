package tablut;

import static tablut.Board.*;

/** Iterative-deepening negamax with alpha-beta, transposition table, killers, history, threat extensions. */
public final class Search {
    public static final int MATE = 30000;
    private static final int TT_BITS = 21;
    private static final int TT_SIZE = 1 << TT_BITS;
    private static final int EXACT = 0, LOWER = 1, UPPER = 2;
    private static final int MAX_PLY = 64;

    private final long[] ttKey = new long[TT_SIZE];
    private final int[] ttMove = new int[TT_SIZE];
    private final short[] ttScore = new short[TT_SIZE];
    private final byte[] ttDepth = new byte[TT_SIZE];
    private final byte[] ttFlag = new byte[TT_SIZE];

    private final int[][] moves = new int[MAX_PLY + 16][256];
    private final int[][] scores = new int[MAX_PLY + 16][256];
    private final int[][] killers = new int[MAX_PLY + 16][2];
    private final int[][] history = new int[2][SQ * 128];
    private final long[] pathHash = new long[MAX_PLY + 16];

    private Board bd;
    private java.util.Set<Long> gameHistory = new java.util.HashSet<Long>();
    private long deadline;
    private boolean timeUp;
    private long nodes;
    private int rootMaxPly;
    private int capPly; // ply of the last capture on the current path (-1: none)
    public boolean verbose = false;
    public int maxDepthLimit = 64;     // for testing / weaker opponents
    public int lastDepth, lastScore;
    public long lastNodes;

    public void setHistory(java.util.Set<Long> h) { gameHistory = h; }

    private void clearTT() {
        java.util.Arrays.fill(ttKey, 0L);
    }

    /** Returns best move (from<<7|to) for the side to move of {@code root}. */
    public int bestMove(Board root, double seconds) {
        this.bd = root.copy();
        long start = System.nanoTime();
        deadline = start + (long) (seconds * 1e9);
        timeUp = false;
        nodes = 0;
        for (int[] k : killers) { k[0] = k[1] = 0; }
        for (int[] h : history) java.util.Arrays.fill(h, 0);
        // keep TT between moves (still valid), but if it was never used nothing to do

        int[] rootMoves = new int[256];
        int n = bd.gen(rootMoves);
        if (n == 0) return -1;
        if (n == 1) return rootMoves[0];

        // immediate wins first (cheap and certain)
        for (int i = 0; i < n; i++) {
            bd.doMove(rootMoves[i] >> 7, rootMoves[i] & 127);
            boolean win = bd.result != ONGOING;
            bd.undo();
            if (win) { lastScore = MATE; lastDepth = 1; return rootMoves[i]; }
        }

        int[] rootScore = new int[n];
        int bestMove = rootMoves[0];
        int bestScore = -MATE;
        capPly = -1;
        for (int depth = 1; depth <= maxDepthLimit; depth++) {
            rootMaxPly = depth + 6;
            int iterBest = -1, iterBestScore = -MATE - 1;
            int alpha = -MATE - 1, beta = MATE + 1;
            boolean completedFirst = false;
            // order: previous best first, then by previous scores
            sortRoot(rootMoves, rootScore, n, bestMove);
            for (int i = 0; i < n; i++) {
                int mv = rootMoves[i];
                int from = mv >> 7, to = mv & 127;
                bd.doMove(from, to);
                int sc;
                if (bd.result != ONGOING) sc = MATE - 1;
                else {
                    pathHash[0] = root.hash;
                    int savedCap = capPly;
                    if (bd.lastCaps > 0) capPly = 1;
                    sc = -negamax(depth - 1, -beta, -alpha, 1);
                    capPly = savedCap;
                }
                bd.undo();
                if (timeUp && depth > 1) break;
                rootScore[i] = sc;
                if (i == 0) completedFirst = true;
                if (sc > iterBestScore) {
                    iterBestScore = sc; iterBest = mv;
                    if (sc > alpha) alpha = sc;
                }
            }
            if (timeUp && depth > 1) {
                // accept a partial iteration only if the (previous best) first move finished
                if (completedFirst && iterBest != -1 && iterBestScore >= bestScore) {
                    bestMove = iterBest; bestScore = iterBestScore;
                }
                break;
            }
            bestMove = iterBest; bestScore = iterBestScore;
            lastDepth = depth; lastScore = bestScore;
            if (verbose)
                System.out.printf("  depth %d score %d best %s%s nodes %d t=%.2fs%n", depth, bestScore,
                        sqName(bestMove >> 7), sqName(bestMove & 127), nodes, (System.nanoTime() - start) / 1e9);
            if (Math.abs(bestScore) >= MATE - 200) break;   // forced result found
            if (System.nanoTime() > deadline) break;
        }
        lastNodes = nodes;
        return bestMove;
    }

    private void sortRoot(int[] mv, int[] sc, int n, int first) {
        // insertion sort by score descending, forcing 'first' to the front
        for (int i = 1; i < n; i++) {
            int m = mv[i], s = sc[i], j = i - 1;
            while (j >= 0 && sc[j] < s) { mv[j + 1] = mv[j]; sc[j + 1] = sc[j]; j--; }
            mv[j + 1] = m; sc[j + 1] = s;
        }
        for (int i = 0; i < n; i++) {
            if (mv[i] == first) {
                int m = mv[i], s = sc[i];
                for (int j = i; j > 0; j--) { mv[j] = mv[j - 1]; sc[j] = sc[j - 1]; }
                mv[0] = m; sc[0] = s;
                break;
            }
        }
    }

    private boolean repeated(long h, int ply) {
        int lo = capPly < 0 ? 0 : capPly;
        for (int i = ply - 2; i >= lo; i -= 2) if (pathHash[i] == h) return true;
        if (capPly < 0 && gameHistory.contains(h)) return true;
        return false;
    }

    private int negamax(int depth, int alpha, int beta, int ply) {
        if ((++nodes & 2047) == 0 && System.nanoTime() > deadline) timeUp = true;
        if (timeUp) return 0;
        final long h = bd.hash;
        pathHash[ply] = h;
        if (repeated(h, ply)) return 0;

        // white to move with a free run to the border: forced win
        if (bd.stm == WHITE_TURN) {
            if (bd.escapeLines(bd.kingSq, -1) > 0) return MATE - ply;
        }
        if (depth <= 0) {
            if (bd.stm == BLACK_TURN && ply < rootMaxPly && Eval.blackTactical(bd)) depth = 1;
            else {
                int e = Eval.eval(bd);
                return bd.stm == WHITE_TURN ? e : -e;
            }
        }
        if (ply >= MAX_PLY - 1) {
            int e = Eval.eval(bd);
            return bd.stm == WHITE_TURN ? e : -e;
        }

        final int origAlpha = alpha;
        int ttm = 0;
        int idx = (int) (h ^ (h >>> 32)) & (TT_SIZE - 1);
        if (ttKey[idx] == h) {
            ttm = ttMove[idx];
            if (ttDepth[idx] >= depth) {
                int sc = ttScore[idx];
                if (sc > MATE - 500) sc -= ply; else if (sc < -MATE + 500) sc += ply;
                int fl = ttFlag[idx];
                if (fl == EXACT) return sc;
                if (fl == LOWER && sc >= beta) return sc;
                if (fl == UPPER && sc <= alpha) return sc;
            }
        }

        final int[] mv = moves[ply];
        final int[] sco = scores[ply];
        int n = bd.gen(mv);
        if (n == 0) return -(MATE - ply);
        final int side = bd.stm;
        // ---- move ordering
        for (int i = 0; i < n; i++) {
            int m = mv[i];
            int from = m >> 7, to = m & 127;
            int s;
            if (m == ttm) s = 1_000_000;
            else {
                boolean isKing = bd.b[from] == KING;
                bd.doMove(from, to);
                if (bd.result != ONGOING) s = 900_000;
                else {
                    s = bd.lastCaps * 20_000;
                    if (isKing) {
                        int l = bd.escapeLines(to, -1);
                        s += l * 40_000;
                    }
                    if (m == killers[ply][0]) s += 9_000;
                    else if (m == killers[ply][1]) s += 8_000;
                    s += Math.min(history[side][m], 7_000);
                }
                bd.undo();
            }
            sco[i] = s;
        }

        int best = -MATE - 1, bestMv = 0;
        int savedCap = capPly;
        for (int i = 0; i < n; i++) {
            // selection sort step
            int bi = i;
            for (int j = i + 1; j < n; j++) if (sco[j] > sco[bi]) bi = j;
            if (bi != i) {
                int t = mv[i]; mv[i] = mv[bi]; mv[bi] = t;
                t = sco[i]; sco[i] = sco[bi]; sco[bi] = t;
            }
            int m = mv[i];
            bd.doMove(m >> 7, m & 127);
            int sc;
            final boolean capMove = bd.lastCaps > 0;
            if (bd.result != ONGOING) sc = MATE - ply - 1;
            else {
                if (capMove) capPly = ply + 1;
                sc = -negamax(depth - 1, -beta, -alpha, ply + 1);
                capPly = savedCap;
            }
            bd.undo();
            if (timeUp) return 0;
            if (sc > best) {
                best = sc; bestMv = m;
                if (sc > alpha) {
                    alpha = sc;
                    if (alpha >= beta) {
                        if (!capMove) { // quiet-move heuristics
                            if (killers[ply][0] != m) { killers[ply][1] = killers[ply][0]; killers[ply][0] = m; }
                            history[side][m] += depth * depth;
                        }
                        break;
                    }
                }
            }
        }
        // store
        int fl = best <= origAlpha ? UPPER : (best >= beta ? LOWER : EXACT);
        int st = best;
        if (st > MATE - 500) st += ply; else if (st < -MATE + 500) st -= ply;
        if (ttKey[idx] != h || ttDepth[idx] <= depth) {
            ttKey[idx] = h; ttMove[idx] = bestMv; ttScore[idx] = (short) st;
            ttDepth[idx] = (byte) Math.min(depth, 100); ttFlag[idx] = (byte) fl;
        }
        return best;
    }
}
