package com.tomk99.gamebasebackend.service; // Használd a saját package neved!

import org.springframework.beans.factory.annotation.Value; // Fontos: @Value importálása
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AmoebaGameLogicService {

    // Konstansok helyett mezők, amiket a @Value tölt fel
    @Value("${game.amoeba.board-size}") // Kulcs az application.properties-ből
    private int boardSize;

    @Value("${game.amoeba.win-length}") // Kulcs az application.properties-ből
    private int winLength;

    // Getter metódusok, hogy más service-ek is elérhessék ezeket az értékeket
    public int getBoardSize() {
        return boardSize;
    }

    public int getWinLength() {
        return winLength;
    }

    // A metódusok most már a beinjektált mezőket használják a konstansok helyett
    public boolean isValidMove(String[][] board, int row, int col) {
        if (board == null) return false;
        if (row < 0 || row >= boardSize || col < 0 || col >= boardSize) { // boardSize használata
            return false;
        }
        return board[row][col] == null;
    }

    public List<Map<String, Integer>> checkWinner(String[][] board, int row, int col, String player) {
        if (board == null) return null;
        int size = this.boardSize; // boardSize használata
        int requiredLength = this.winLength; // winLength használata

        final int[] dr = {0, 1, 1, 1};
        final int[] dc = {1, 0, 1, -1};

        for (int i = 0; i < 4; i++) {
            List<Map<String, Integer>> line = new ArrayList<>();
            line.add(Map.of("row", row, "col", col));
            int count = 1;
            // requiredLength használata
            for (int j = 1; j < requiredLength; j++) {
                int r = row + dr[i] * j; int c = col + dc[i] * j;
                if (r >= 0 && r < size && c >= 0 && c < size && board[r][c] != null && board[r][c].equals(player)) { count++; line.add(Map.of("row", r, "col", c)); } else break;
            }
            // requiredLength használata
            for (int j = 1; j < requiredLength; j++) {
                int r = row - dr[i] * j; int c = col - dc[i] * j;
                if (r >= 0 && r < size && c >= 0 && c < size && board[r][c] != null && board[r][c].equals(player)) { count++; line.add(Map.of("row", r, "col", c)); } else break;
            }
            // requiredLength használata
            if (count >= requiredLength) return line;
        }
        return null;
    }

    public boolean isBoardFull(String[][] board) {
        if (board == null) return false;
        // boardSize használata
        for (int r = 0; r < boardSize; r++) {
            for (int c = 0; c < boardSize; c++) {
                if (board[r][c] == null) return false;
            }
        }
        return true;
    }

    public String[][] createNewBoard() {
        // boardSize használata
        String[][] newBoard = new String[boardSize][boardSize];
        return newBoard;
    }
}