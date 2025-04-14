package com.tomk99.gamebasebackend.model;

import org.springframework.web.socket.WebSocketSession;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public class GameState {

    private final String gameId;
    private final int boardSize;
    private String[][] board;
    private WebSocketSession playerX;
    private WebSocketSession playerO;
    private String currentPlayer; // 'X' vagy 'O'
    private boolean started;
    private boolean gameOver;
    private String winner; // 'X', 'O', 'draw' vagy null
    private List<Map<String, Integer>> winningLine;

    public GameState(String gameId, int boardSize) {
        this.gameId = gameId;
        this.boardSize = boardSize;
        resetBoardAndStatus(); // Kezdeti inicializálás
    }

    // --- Getterek és Setterek ---
    public String getGameId() {
        return gameId;
    }
    public int getBoardSize() { return boardSize; }
    public String[][] getBoard() { return board; }
    public void setBoard(String[][] board) { this.board = board; }
    public WebSocketSession getPlayerX() { return playerX; }
    public void setPlayerX(WebSocketSession playerX) { this.playerX = playerX; }
    public WebSocketSession getPlayerO() { return playerO; }
    public void setPlayerO(WebSocketSession playerO) { this.playerO = playerO; }
    public String getCurrentPlayer() { return currentPlayer; }
    public void setCurrentPlayer(String currentPlayer) { this.currentPlayer = currentPlayer; }
    public boolean isStarted() { return started; }
    public void setStarted(boolean started) { this.started = started; }
    public boolean isGameOver() { return gameOver; }
    public void setGameOver(boolean gameOver) { this.gameOver = gameOver; }
    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }
    public List<Map<String, Integer>> getWinningLine() { return winningLine; }
    public void setWinningLine(List<Map<String, Integer>> winningLine) { this.winningLine = winningLine; }

    // --- Segédfüggvények ---
    public boolean isFull() {
        return playerX != null && playerO != null;
    }

    public WebSocketSession getOtherPlayer(WebSocketSession player) {
        if (player == null) return null;
        return Objects.equals(player.getId(), playerX != null ? playerX.getId() : null) ? playerO :
                (Objects.equals(player.getId(), playerO != null ? playerO.getId() : null) ? playerX : null);
    }

    public String getPlayerMark(WebSocketSession session) {
        if (session != null && Objects.equals(session.getId(), playerX != null ? playerX.getId() : null)) return "X";
        if (session != null && Objects.equals(session.getId(), playerO != null ? playerO.getId() : null)) return "O";
        return null;
    }

    // Visszaállítja a táblát és a játék állapotát (de a játékosokat nem nullázza)
    public void resetBoardAndStatus() {
        this.board = new String[boardSize][boardSize];
        // Opcionális: null-lal töltés expliciten (Java alapból megteszi)
        // for (int i = 0; i < boardSize; i++) { Arrays.fill(board[i], null); }
        this.currentPlayer = "X"; // X kezd
        this.gameOver = false;
        this.winner = null;
        this.winningLine = null;
        // A 'started' állapotot a GameService kezeli majd
    }
}