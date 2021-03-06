package com.cannon.engine.AI;


import com.cannon.engine.AI.support.BoardEvaluator;
import com.cannon.engine.AI.support.MoveStrategy;
import com.cannon.engine.AI.support.StandardBoardEvaluator;
import com.cannon.engine.player.Alliance;
import com.cannon.engine.board.Board;
import com.cannon.engine.board.BoardUtils;
import com.cannon.engine.board.Move;
import com.cannon.engine.player.MoveTransition;
import com.cannon.engine.player.Player;
import com.cannon.pgn.ZobristHashing;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Ordering;

import java.util.*;

import static com.cannon.engine.board.Move.*;

public class AlphaOne extends Observable implements MoveStrategy {

    private final BoardEvaluator evaluator;
    private final int searchDepth;
    private final MoveSorter moveSorter;
    private final int quiescenceFactor;
    private long boardsEvaluated;
    private long executionTime;
    private int quiescenceCount;
    private static final int MAX_QUIESCENCE = 5000 * 5;
    private int cutOffsProduced;
    private int highestSeenValue = Integer.MIN_VALUE;
    private int lowestSeenValue = Integer.MAX_VALUE;
    private int nodesExplored = 0;
    private int depthExplored = 0;
    private Map<Long,tableNode> transposition = new HashMap<>();
    public ZobristHashing zobrist;

    private class tableNode{
        protected int score;
        protected int depth;
        protected int flag;
        public tableNode(Move bestMove, int score, int depth, int flag){
            this.score = score;
            this.depth = depth;
            this.flag = flag;
        }
    }

    private enum MoveSorter {

        SORT {
            @Override
            Collection<Move> sort(final Collection<Move> moves) {
                return Ordering.from(SMART_SORT).immutableSortedCopy(moves);
            }
        };

        public static Comparator<Move> SMART_SORT = new Comparator<Move>() {
            @Override
            public int compare(final Move move1, final Move move2) {
                return ComparisonChain.start()
                        .compareTrueFirst(BoardUtils.isThreatenedBoardImmediate(move1.getBoard()), BoardUtils.isThreatenedBoardImmediate(move2.getBoard()))
                        .compareTrueFirst(move1.isAttack(), move2.isAttack())
                        .compare(move2.getMovedPiece().getPieceValue(), move1.getMovedPiece().getPieceValue())
                        .result();
            }
        };

        abstract Collection<Move> sort(Collection<Move> moves);
    }

    public AlphaOne(final int searchDepth,
                    final int quiescenceFactor) {
        this.evaluator = StandardBoardEvaluator.get();
        this.searchDepth = Math.min(searchDepth, 6);
        this.quiescenceFactor = quiescenceFactor;
        this.moveSorter = MoveSorter.SORT;
        this.boardsEvaluated = 0;
        this.quiescenceCount = 0;
        this.cutOffsProduced = 0;
        this.zobrist = new ZobristHashing();
    }

    @Override
    public String toString() {
        return "AlphaOne";
    }


    @Override
    public Move execute(final Board board) {
        final long startTime = System.currentTimeMillis();
        final Player currentPlayer = board.currentPlayer();
        final Alliance alliance = currentPlayer.getAlliance();
        Move bestMove = MoveFactory.getNullMove();
        int currentValue;
        int moveCounter = 1;
        final int numMoves = this.moveSorter.sort(board.currentPlayer().getLegalMoves()).size();
        System.out.println(board.currentPlayer() + " THINKING with depth = " + this.searchDepth);
        System.out.println("\tOrdered moves! : " + this.moveSorter.sort(board.currentPlayer().getLegalMoves()));
        for (final Move move : this.moveSorter.sort(board.currentPlayer().getLegalMoves())) {
            final MoveTransition moveTransition = board.currentPlayer().makeMove(move);
            this.quiescenceCount = 0;
            final String s;
            if (moveTransition.getMoveStatus().isDone()) {
                final long candidateMoveStartTime = System.nanoTime();
                currentValue = alliance.isLight() ?
                        min(moveTransition.getToBoard(), this.searchDepth - 1, highestSeenValue, lowestSeenValue) :
                        max(moveTransition.getToBoard(), this.searchDepth - 1, highestSeenValue, lowestSeenValue);
                if (alliance.isLight() && currentValue > highestSeenValue) {
                    highestSeenValue = currentValue;
                    bestMove = move;
                }
                else if (alliance.isDark() && currentValue < lowestSeenValue) {
                    lowestSeenValue = currentValue;
                    bestMove = move;
                }
                final String quiescenceInfo = " [h: " +highestSeenValue+ " l: " +lowestSeenValue+ "] q: " +this.quiescenceCount;
                s = "\t" + toString() + "(" +this.searchDepth+ "), m: (" +moveCounter+ "/" +numMoves+ ") " + move + ", best:  " + bestMove

                        + quiescenceInfo + ", t: " +calculateTimeTaken(candidateMoveStartTime, System.nanoTime());
            } else {
                s = "\t" + toString() + ", m: (" +moveCounter+ "/" +numMoves+ ") " + move + " is illegal, best: " +bestMove;
            }
            System.out.println(s);
            setChanged();
            notifyObservers(s);
            moveCounter++;
        }
        this.executionTime = System.currentTimeMillis() - startTime;
        System.out.printf("%s SELECTS %s [#boards evaluated = %d, time taken = %d ms, eval rate = %.1f cutoffCount = %d prune percent = %.2f\n", board.currentPlayer(),
                bestMove, this.boardsEvaluated, this.executionTime, (1000 * ((double)this.boardsEvaluated/this.executionTime)), this.cutOffsProduced, 100 * ((double)this.cutOffsProduced/this.boardsEvaluated));
        return bestMove;
    }

    public int max(final Board board,
                   int depth,
                   int highest,
                   int lowest) {
        int olda = lowest;
        incrementNodeCount();
        updateDepth(depth);
        long state = zobrist.getHash(board);

        if(transposition.containsKey(state)) {
            if(transposition.get(state).depth >= depth) {
                int value = transposition.get(state).score;
                if(transposition.get(state).flag == 0) {
                    return value;
                } else if(transposition.get(state).flag == -1) {
                    lowest = Math.max(lowest, value);
                } else if(transposition.get(state).flag == 1) {
                    highest = Math.min(highest, value);
                }
                if(lowest >= highest) {
                    return value;
                }
            }
        }
        if (depth == 0 || BoardUtils.isEndGame(board)) {
            this.boardsEvaluated++;
            return this.evaluator.evaluate(board, depth);
        }
        int currentHighest = highest;
        Move bestMove = null;
        for (final Move move : this.moveSorter.sort((board.currentPlayer().getLegalMoves()))) {
            final MoveTransition moveTransition = board.currentPlayer().makeMove(move);
            if (moveTransition.getMoveStatus().isDone()) {
                final Board toBoard = moveTransition.getToBoard();
                currentHighest = Math.max(currentHighest, min(moveTransition.getToBoard(),
                        calculateQuiescenceDepth(toBoard, depth), currentHighest, lowest));
                bestMove = move;
                if (lowest <= currentHighest) {
                    this.cutOffsProduced++;
                    break;
                }
            }
        }
        int flag = 0;
        if(currentHighest <= olda) {
            flag = 1;
        } else if(currentHighest >= highest) {
            flag = -1;
        } else if(flag > lowest && flag < highest) {
            flag = 0;
        }
        transposition.put(state, new tableNode(bestMove, currentHighest, depth, flag));
        return currentHighest;
    }

    public int min(final Board board,
                   final int depth,
                   int highest,
                   int lowest) {
        int olda = lowest;
        incrementNodeCount();
        updateDepth(depth);
        long state = zobrist.getHash(board);

        if(transposition.containsKey(state)) {
            if(transposition.get(state).depth >= depth) {
                int value = transposition.get(state).score;
                if(transposition.get(state).flag == 0) {
                    return value;
                } else if(transposition.get(state).flag == -1) {
                    lowest = Math.max(lowest, value);
                } else if(transposition.get(state).flag == 1) {
                    highest = Math.min(highest, value);
                }
                if(lowest >= highest) {
                    return value;
                }
            }
        }
        if (depth == 0 || BoardUtils.isEndGame(board)) {
            this.boardsEvaluated++;
            return this.evaluator.evaluate(board, depth);
        }
        int currentLowest = lowest;
        Move bestMove = null;
        for (final Move move : this.moveSorter.sort((board.currentPlayer().getLegalMoves()))) {
            final MoveTransition moveTransition = board.currentPlayer().makeMove(move);
            if (moveTransition.getMoveStatus().isDone()) {
                final Board toBoard = moveTransition.getToBoard();
                currentLowest = Math.min(currentLowest, max(moveTransition.getToBoard(),
                        calculateQuiescenceDepth(toBoard, depth), highest, currentLowest));
                bestMove = move;
                if (currentLowest <= highest) {
                    this.cutOffsProduced++;
                    break;
                }
            }
        }
        int flag = 0;
        if(currentLowest <= olda) {
            flag = 1;
        } else if(currentLowest >= highest) {
            flag = -1;
        } else if(flag > lowest && flag < highest) {
            flag = 0;
        }
        transposition.put(state, new tableNode(bestMove, currentLowest, depth, flag));
        return currentLowest;
    }

    private int calculateQuiescenceDepth(final Board toBoard,
                                         final int depth) {
        if(depth == 1 && this.quiescenceCount < MAX_QUIESCENCE) {
            int activityMeasure = 0;
            if (toBoard.currentPlayer().isInCheck()) {
                activityMeasure += 1;
            }
            for(final Move move: BoardUtils.lastNMoves(toBoard, 2)) {
                if(move.isAttack()) {
                    activityMeasure += 1;
                }
            }
            if(activityMeasure >= 2) {
                this.quiescenceCount++;
                return 2;
            }
        }
        return depth - 1;
    }


    private static String calculateTimeTaken(final long start, final long end) {
        final long timeTaken = (end - start) / 1000000;
        return timeTaken + " ms";
    }

    protected void updateDepth(int depth) {
        depthExplored = Math.max(depth, depthExplored);
    }

    protected void incrementNodeCount() {
        nodesExplored++;
    }

}
