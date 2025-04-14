package com.tomk99.gamebasebackend.service;

import com.tomk99.gamebasebackend.dto.MakeMovePayload;
import com.tomk99.gamebasebackend.model.GameState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {

    private static final Logger logger = LoggerFactory.getLogger(GameService.class);
    private final AmoebaGameLogicService logicService;
    private final Map<String, GameState> activeGames = new ConcurrentHashMap<>();

    @Autowired
    public GameService(AmoebaGameLogicService logicService) {
        this.logicService = logicService;
    }

    public synchronized PlayerAssignment addPlayer(WebSocketSession session) {
        String sessionId = session.getId();
        logger.info("Service: addPlayer kérés: {}", sessionId);

        GameState waitingGame = findWaitingGame();

        if (waitingGame != null) {
            logger.info("Service: addPlayer - Várakozó játék ({}) található. Csatlakozás O-ként: {}", waitingGame.getGameId(), sessionId);
            waitingGame.setPlayerO(session);
            session.getAttributes().put("gameId", waitingGame.getGameId());
            startGameProcedure(waitingGame);
            return new PlayerAssignment("O", true, waitingGame.getGameId());
        } else {
            String newGameId = UUID.randomUUID().toString();
            logger.info("Service: addPlayer - Nincs várakozó játék. Új játék létrehozása ({}) X-nek: {}", newGameId, sessionId);
            GameState newGame = new GameState(newGameId, logicService.getBoardSize());
            newGame.setPlayerX(session);
            session.getAttributes().put("gameId", newGameId);
            activeGames.put(newGameId, newGame); // Tegyük be a Map-be
            logger.info("Service: addPlayer - Új játék létrehozva. Player X ({}) várakozik.", sessionId);
            return new PlayerAssignment("X", false, newGameId);
        }
    }

    private GameState findWaitingGame() {
        for (GameState game : activeGames.values()) {
            if (!game.isStarted() && game.getPlayerX() != null && game.getPlayerO() == null) {
                return game;
            }
        }
        return null;
    }

    private void startGameProcedure(GameState gameState) {
        if (gameState == null || !gameState.isFull()) {
            logger.error("Service: startGameProcedure hívva nem teljes játékra!");
            return;
        }
        gameState.resetBoardAndStatus();
        gameState.setStarted(true);
        logger.info("Service: Játék ({}) elindítva/újraindítva. Kezdő játékos: {}", gameState.getGameId(), gameState.getCurrentPlayer());
    }

    public synchronized GameResult handleMove(WebSocketSession session, MakeMovePayload move) {
        String gameId = getGameIdFromSession(session);
        if (gameId == null) return new GameResult(GameResult.ResultType.ERROR, "Nem található játék ehhez a sessionhöz.");

        GameState game = activeGames.get(gameId);
        if (game == null || !game.isStarted() || game.isGameOver()) {
            return new GameResult(GameResult.ResultType.ERROR, "A játék nem aktív.");
        }

        String playerMark = game.getPlayerMark(session);
        if (playerMark == null || !playerMark.equals(game.getCurrentPlayer())) {
            return new GameResult(GameResult.ResultType.ERROR, "Nem te következel!");
        }

        int row = move.row();
        int col = move.col();

        if (!logicService.isValidMove(game.getBoard(), row, col)) {
            return new GameResult(GameResult.ResultType.ERROR, "Érvénytelen lépés!");
        }

        game.getBoard()[row][col] = playerMark;
        logger.info("Service: Lépés rögzítve (Game: {}): {} -> [{}, {}]", gameId, playerMark, row, col);

        List<Map<String, Integer>> winningCells = logicService.checkWinner(game.getBoard(), row, col, playerMark);
        if (winningCells != null) {
            game.setGameOver(true); game.setWinner(playerMark); game.setWinningLine(winningCells);
            logger.info("Service: Játék vége (Game: {})! Nyertes: {}", gameId, playerMark);
            return new GameResult(GameResult.ResultType.GAMEOVER, game);
        }

        if (logicService.isBoardFull(game.getBoard())) {
            game.setGameOver(true); game.setWinner("draw"); game.setWinningLine(List.of());
            logger.info("Service: Játék vége (Game: {})! Döntetlen.", gameId);
            return new GameResult(GameResult.ResultType.GAMEOVER, game);
        }

        game.setCurrentPlayer("X".equals(playerMark) ? "O" : "X");
        logger.info("Service: Játékos váltás (Game: {}) -> {}", gameId, game.getCurrentPlayer());
        return new GameResult(GameResult.ResultType.UPDATE, game);
    }

    public synchronized WebSocketSession removePlayer(WebSocketSession session) {
        String sessionId = session.getId();
        String gameId = getGameIdFromSession(session);
        logger.info("Service: removePlayer kérés: Session={}, GameId={}", sessionId, gameId);

        if (gameId == null) {
            logger.warn("Service: removePlayer - Nem található gameId ehhez a sessionhöz: {}", sessionId);
            return null;
        }
        GameState game = activeGames.get(gameId);
        if (game == null) {
            logger.warn("Service: removePlayer - Nem található aktív játék ehhez a gameId-hoz: {}", gameId);
            session.getAttributes().remove("gameId");
            return null;
        }

        WebSocketSession otherPlayer = game.getOtherPlayer(session);
        String leftPlayerMark = game.getPlayerMark(session);

        if ("X".equals(leftPlayerMark)) { game.setPlayerX(null); logger.info("Service: Player X ({}) eltávolítva a játékból ({})", sessionId, gameId); }
        else if ("O".equals(leftPlayerMark)) { game.setPlayerO(null); logger.info("Service: Player O ({}) eltávolítva a játékból ({})", sessionId, gameId); }
        else { logger.warn("Service: removePlayer - A session ({}) nem volt X vagy O a játékban ({})", sessionId, gameId); }

        session.getAttributes().remove("gameId");

        if (game.isStarted() && !game.isGameOver()) {
            game.setGameOver(true); game.setStarted(false);
            logger.info("Service: Játék ({}) leállítva ({}) kilépése miatt.", gameId, leftPlayerMark != null ? leftPlayerMark : "?");
        }

        if (game.getPlayerX() == null && game.getPlayerO() == null) {
            logger.info("Service: Játék ({}) kiürült, eltávolítás az aktív játékok közül.", gameId);
            activeGames.remove(gameId);
        }

        return otherPlayer;
    }

    public synchronized GameState resetGame(WebSocketSession session) {
        String gameId = getGameIdFromSession(session);
        if (gameId == null) {
            logger.warn("Service: Reset kérés, de nincs gameId a sessionben: {}", session.getId());
            return null;
        }
        GameState game = activeGames.get(gameId);
        if (game == null) {
            logger.warn("Service: Reset kérés, de nincs aktív játék ezzel a gameId-val: {}", gameId);
            return null;
        }
        if (game.isFull() || game.isGameOver()) {
            logger.info("Service: Játék ({}) resetelése kérésre: {}", gameId, session.getId());
            startGameProcedure(game);
            return game;
        } else {
            logger.warn("Service: resetGame - A játék ({}) nem teljes vagy még el sem indult.", gameId);
            return null;
        }
    }

    private String getGameIdFromSession(WebSocketSession session) {
        if (session == null) {
            return null;
        } else {
            session.getAttributes();
        }
        return (String) session.getAttributes().get("gameId");
    }

    public GameState getGameState(String gameId) {
        return activeGames.get(gameId);
    }

    public record PlayerAssignment(String assignedMark, boolean gameStarted, String gameId) {}
    public record GameResult(ResultType type, Object payload, String errorMessage) {
        public GameResult(ResultType type, GameState gameState) { this(type, gameState, null); }
        public GameResult(ResultType type, String errorMessage) { this(type, null, errorMessage); }
        public enum ResultType { UPDATE, GAMEOVER, ERROR }
    }
}