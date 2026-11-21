package tablut;

import it.unibo.ai.didattica.competition.tablut.domain.*;
import java.io.*;
import java.util.*;

/**
 * Differential test: our Board vs. the official server engine (GameAshtonTablut).
 * For every position and EVERY (from,to) pair of squares it compares legality and the resulting state.
 */
public class RulesDiffTest {
    static final State.Pawn[] PMAP = {State.Pawn.EMPTY, State.Pawn.WHITE, State.Pawn.BLACK, State.Pawn.KING};

    static StateTablut toState(Board bd) {
        StateTablut st = new StateTablut();
        State.Pawn[][] g = st.getBoard();
        for (int sq = 0; sq < 81; sq++) {
            int p = bd.b[sq];
            g[sq / 9][sq % 9] = (sq == 40 && p == 0) ? State.Pawn.THRONE : PMAP[p];
        }
        st.setTurn(bd.stm == Board.WHITE_TURN ? State.Turn.WHITE : State.Turn.BLACK);
        return st;
    }

    static int pawnToInt(State.Pawn p) {
        switch (p) { case WHITE: return 1; case BLACK: return 2; case KING: return 3; default: return 0; }
    }

    static long positions = 0, pairs = 0, legalBoth = 0;
    static long myIllegalServerLegal = 0, myLegalServerIllegal = 0, resultMismatch = 0;
    static int shown = 0;
    static long cntBW=0,cntWW=0,cntCapW=0,cntCapB=0,cntKingThroneBW=0,cntKingAdjBW=0,cntKingFarBW=0;

    public static void main(String[] args) throws Exception {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int randomBoards = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        java.util.logging.Logger.getLogger("GameLog").setUseParentHandlers(false);
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.OFF);
        PrintStream realOut = System.out;
        PrintStream nul = new PrintStream(new OutputStream() { public void write(int b) {} public void write(byte[] b, int o, int l) {} });
        System.setOut(nul);
        GameAshtonTablut game = new GameAshtonTablut(1_000_000, 0, "/tmp/tablutlogs", "t1", "t2");
        java.util.logging.Logger gl = java.util.logging.Logger.getLogger("GameLog");
        gl.setLevel(java.util.logging.Level.OFF);
        for (java.util.logging.Handler hd : gl.getHandlers()) { hd.setLevel(java.util.logging.Level.OFF); }
        new File("/tmp/tablutlogs").deleteOnExit();
        Random rnd = new Random(12345);
        int[] mv = new int[256];

        // 1) random playouts from the initial position
        for (int g = 0; g < games; g++) {
            Board bd = new Board();
            for (int ply = 0; ply < 300 && bd.result == Board.ONGOING; ply++) {
                check(bd, game);
                int n = bd.gen(mv);
                if (n == 0) break;
                // bias: prefer capturing/escaping moves sometimes to reach interesting positions
                int pick = rnd.nextInt(n);
                if (rnd.nextInt(3) == 0) {
                    for (int tries = 0; tries < 12; tries++) {
                        int c = mv[rnd.nextInt(n)];
                        bd.doMove(c >> 7, c & 127);
                        boolean interesting = bd.lastCaps > 0 || bd.result != Board.ONGOING;
                        bd.undo();
                        if (interesting) { pick = -1; mv[0] = mv[0]; pickMove = c; break; }
                    }
                }
                int m = pick == -1 ? pickMove : mv[pick];
                bd.doMove(m >> 7, m & 127);
            }
        }
        // 2) random boards (stress special captures near throne / camps / edges)
        for (int i = 0; i < randomBoards; i++) {
            Board bd = randomBoard(rnd);
            check(bd, game);
        }
        // 3) targeted: king on / next to the throne, blacks around it, black to move
        int[] kpos = {40, 31, 49, 39, 41, 30, 32, 48, 50, 22, 58, 38, 42, 21, 23, 13 + 9, 57, 59};
        for (int i = 0; i < 2500; i++) {
            Board bd = new Board();
            Arrays.fill(bd.b, (byte) 0);
            int k = kpos[rnd.nextInt(kpos.length)];
            bd.b[k] = Board.KING;
            for (int sq = 0; sq < 81; sq++) {
                if (sq == k || sq == 40) continue;
                int r = Math.abs(sq / 9 - k / 9) + Math.abs(sq % 9 - k % 9);
                if (r <= 2 && rnd.nextInt(100) < (r == 1 ? 65 : 30)) {
                    if (Board.CAMP[sq] ) continue;
                    bd.b[sq] = (byte) (rnd.nextInt(4) == 0 ? Board.WHITE : Board.BLACK);
                } else if (r > 2 && rnd.nextInt(100) < 6 && !Board.CAMP[sq]) bd.b[sq] = Board.BLACK;
            }
            bd.stm = rnd.nextInt(5) == 0 ? Board.WHITE_TURN : Board.BLACK_TURN;
            bd.recount();
            check(bd, game);
        }
        System.setOut(realOut);
        System.out.println("positions=" + positions + " pairs=" + pairs + " legalInBoth=" + legalBoth);
        System.out.println("coverage: kingCaptures="+cntBW+" (throne="+cntKingThroneBW+", adjThrone="+cntKingAdjBW+", elsewhere="+cntKingFarBW+") kingEscapes="+cntWW+" whiteCapturesBlack="+cntCapB+" blackCapturesWhite="+cntCapW);
        System.out.println("MY-LEGAL/SERVER-ILLEGAL (critical) = " + myLegalServerIllegal);
        System.out.println("MY-ILLEGAL/SERVER-LEGAL (harmless) = " + myIllegalServerLegal);
        System.out.println("RESULT MISMATCHES (critical)       = " + resultMismatch);
        System.exit((myLegalServerIllegal + resultMismatch) == 0 ? 0 : 1);
    }
    static int pickMove;

    static Board randomBoard(Random rnd) {
        Board bd = new Board();
        Arrays.fill(bd.b, (byte) 0);
        int nw = 1 + rnd.nextInt(8), nb = 1 + rnd.nextInt(16);
        List<Integer> free = new ArrayList<Integer>();
        for (int i = 0; i < 81; i++) if (i != 40) free.add(i);
        Collections.shuffle(free, rnd);
        int idx = 0;
        // king: anywhere (including throne sometimes)
        int ksq = rnd.nextInt(4) == 0 ? 40 : free.get(idx++);
        while (Board.CAMP[ksq] || Board.EDGE[ksq]) { ksq = rnd.nextInt(4) == 0 ? 40 : free.get(idx++ % free.size()); }
        bd.b[ksq] = Board.KING;
        for (int i = 0; i < nw && idx < free.size(); i++) {
            int s = free.get(idx++);
            if (Board.CAMP[s] || bd.b[s] != 0) continue;
            bd.b[s] = Board.WHITE;
        }
        for (int i = 0; i < nb && idx < free.size(); i++) {
            int s = free.get(idx++);
            if (bd.b[s] != 0) continue;
            bd.b[s] = Board.BLACK;
        }
        // cluster pieces around throne / camps to hit the special rules
        if (rnd.nextBoolean()) {
            int[] around = {31, 49, 39, 41, 30, 32, 48, 50, 22, 58, 38, 42};
            for (int s : around) if (bd.b[s] == 0 && rnd.nextInt(3) == 0) bd.b[s] = (byte) (rnd.nextBoolean() ? Board.BLACK : Board.WHITE);
        }
        bd.stm = rnd.nextBoolean() ? Board.WHITE_TURN : Board.BLACK_TURN;
        bd.recount();
        if (bd.kingSq < 0) { bd.b[ksq] = Board.KING; bd.recount(); }
        return bd;
    }

    static void check(Board bd, GameAshtonTablut game) {
        positions++;
        int[] mv = new int[256];
        int n = bd.gen(mv);
        boolean[][] mine = new boolean[81][81];
        for (int i = 0; i < n; i++) mine[mv[i] >> 7][mv[i] & 127] = true;
        StateTablut base = toState(bd);
        for (int from = 0; from < 81; from++) {
            if (bd.b[from] == 0) continue;
            for (int to = 0; to < 81; to++) {
                if (from == to) continue;
                int dr = Math.abs(from / 9 - to / 9), dc = Math.abs(from % 9 - to % 9);
                boolean line = dr == 0 || dc == 0;
                if (!line && ((from * 31 + to) % 7 != 0)) continue; // sample diagonals only
                pairs++;
                StateTablut st = (StateTablut) base.clone();
                boolean serverLegal;
                State res = null;
                try {
                    Action a = new Action(Board.sqName(from), Board.sqName(to), st.getTurn());
                    res = game.checkMove(st, a);
                    serverLegal = true;
                } catch (Exception e) {
                    serverLegal = false;
                }
                boolean myLegal = mine[from][to];
                if (myLegal && !serverLegal) {
                    myLegalServerIllegal++;
                    report("MY-LEGAL but SERVER-ILLEGAL", bd, from, to);
                } else if (!myLegal && serverLegal) {
                    myIllegalServerLegal++;
                    report("my-illegal but server-legal", bd, from, to);
                } else if (myLegal) {
                    legalBoth++;
                    Board c = bd.copy();
                    c.doMove(from, to);
                    // compare board
                    boolean same = true;
                    for (int sq = 0; sq < 81 && same; sq++) {
                        int sp = pawnToInt(res.getBoard()[sq / 9][sq % 9]);
                        if (sp != c.b[sq]) same = false;
                    }
                    String turn = res.getTurn().toString();
                    String myTurn = c.result == Board.WHITE_WIN ? "WW" : c.result == Board.BLACK_WIN ? "BW"
                            : (c.stm == Board.WHITE_TURN ? "W" : "B");
                    if (c.result==Board.BLACK_WIN){cntBW++; int k=bd.kingSq; if(k==40)cntKingThroneBW++; else if(Board.ADJ_THRONE[k])cntKingAdjBW++; else cntKingFarBW++;}
                    if (c.result==Board.WHITE_WIN) cntWW++;
                    if (c.lastCaps>0){ if (bd.stm==Board.WHITE_TURN) cntCapB++; else cntCapW++; }
                    if (!same || !turn.equals(myTurn)) {
                        resultMismatch++;
                        report("RESULT MISMATCH server=" + turn + " mine=" + myTurn + " boardSame=" + same, bd, from, to);
                    }
                }
            }
        }
    }

    static void report(String what, Board bd, int from, int to) {
        if (shown++ < 12 && !what.startsWith("my-illegal")) {
            PrintStream o = System.out;
            System.err.println(what + " : " + Board.sqName(from) + "->" + Board.sqName(to));
            System.err.println(bd);
        } else if (what.startsWith("my-illegal") && shown++ < 12) {
            System.err.println(what + " : " + Board.sqName(from) + "->" + Board.sqName(to));
            System.err.println(bd);
        }
    }
}
