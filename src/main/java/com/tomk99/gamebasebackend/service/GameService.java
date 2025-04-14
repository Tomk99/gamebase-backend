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
    // Map a játékmenetek tárolására: Kulcs = gameId (String), Érték = GameState
    private final Map<String, GameState> activeGames = new ConcurrentHashMap<>();

    @Autowired
    public GameService(AmoebaGameLogicService logicService) {
        this.logicService = logicService;
    }

    // Játékos hozzáadása - MOST MÁR TÖBB JÁTÉKOT KEZEL
    public synchronized PlayerAssignment addPlayer(WebSocketSession session) {
        String sessionId = session.getId();
        logger.info("Service: addPlayer kérés: {}", sessionId);

        // 1. Keressünk egy játékot, ahol van hely O számára
        GameState waitingGame = findWaitingGame();

        if (waitingGame != null) {
            // 2a. Találtunk váró játékot, csatlakozás O-ként
            logger.info("Service: addPlayer - Várakozó játék ({}) található. Csatlakozás O-ként: {}", waitingGame.getGameId(), sessionId);
            waitingGame.setPlayerO(session);
            // Tároljuk a gameId-t a sessionben
            session.getAttributes().put("gameId", waitingGame.getGameId());
            // Indítsuk a játékot
            startGameProcedure(waitingGame);
            return new PlayerAssignment("O", true, waitingGame.getGameId());
        } else {
            // 2b. Nincs váró játék, hozzunk létre újat X számára
            String newGameId = UUID.randomUUID().toString(); // Egyedi ID generálása
            logger.info("Service: addPlayer - Nincs várakozó játék. Új játék létrehozása ({}) X-nek: {}", newGameId, sessionId);
            GameState newGame = new GameState(newGameId, logicService.getBoardSize());
            newGame.setPlayerX(session);
            // Tároljuk a gameId-t a sessionben
            session.getAttributes().put("gameId", newGameId);
            activeGames.put(newGameId, newGame); // Tegyük be a Map-be
            logger.info("Service: addPlayer - Új játék létrehozva. Player X ({}) várakozik.", sessionId);
            return new PlayerAssignment("X", false, newGameId); // Játék még nem indult
        }
    }

    // Segédfüggvény: Keres egy játékot, ahol X vár O-ra
    private GameState findWaitingGame() {
        for (GameState game : activeGames.values()) {
            // Csak olyan játékot keresünk, ami még nem indult el és csak X van benne
            if (!game.isStarted() && game.getPlayerX() != null && game.getPlayerO() == null) {
                return game;
            }
        }
        return null; // Nincs ilyen játék
    }

    // Játék indítása/resetelése egy adott GameState-re
    private void startGameProcedure(GameState gameState) {
        if (gameState == null || !gameState.isFull()) {
            logger.error("Service: startGameProcedure hívva nem teljes játékra!");
            return;
        }
        gameState.resetBoardAndStatus(); // Tábla és státuszok resetelése
        gameState.setStarted(true); // Most már tényleg elindult
        logger.info("Service: Játék ({}) elindítva/újraindítva. Kezdő játékos: {}", gameState.getGameId(), gameState.getCurrentPlayer());
    }

    // Lépés kezelése - MOST MÁR gameId alapján azonosít
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

        // Lépés végrehajtása a megfelelő GameState tábláján
        game.getBoard()[row][col] = playerMark;
        logger.info("Service: Lépés rögzítve (Game: {}): {} -> [{}, {}]", gameId, playerMark, row, col);

        // Győzelem ellenőrzése
        List<Map<String, Integer>> winningCells = logicService.checkWinner(game.getBoard(), row, col, playerMark);
        if (winningCells != null) {
            game.setGameOver(true); game.setWinner(playerMark); game.setWinningLine(winningCells);
            logger.info("Service: Játék vége (Game: {})! Nyertes: {}", gameId, playerMark);
            return new GameResult(GameResult.ResultType.GAMEOVER, game); // Visszaadjuk a teljes GameState-et
        }

        // Döntetlen ellenőrzése
        if (logicService.isBoardFull(game.getBoard())) {
            game.setGameOver(true); game.setWinner("draw"); game.setWinningLine(List.of());
            logger.info("Service: Játék vége (Game: {})! Döntetlen.", gameId);
            return new GameResult(GameResult.ResultType.GAMEOVER, game);
        }

        // Játékosváltás
        game.setCurrentPlayer("X".equals(playerMark) ? "O" : "X");
        logger.info("Service: Játékos váltás (Game: {}) -> {}", gameId, game.getCurrentPlayer());
        return new GameResult(GameResult.ResultType.UPDATE, game); // Visszaadjuk a teljes GameState-et
    }

    // Játékos eltávolítása - MOST MÁR gameId alapján azonosít
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
            // Távolítsuk el a gameId-t a sessionből, ha még ott van
            session.getAttributes().remove("gameId");
            return null;
        }

        WebSocketSession otherPlayer = game.getOtherPlayer(session);
        String leftPlayerMark = game.getPlayerMark(session);

        // Eltávolítjuk a játékost a GameState-ből
        if ("X".equals(leftPlayerMark)) { game.setPlayerX(null); logger.info("Service: Player X ({}) eltávolítva a játékból ({})", sessionId, gameId); }
        else if ("O".equals(leftPlayerMark)) { game.setPlayerO(null); logger.info("Service: Player O ({}) eltávolítva a játékból ({})", sessionId, gameId); }
        else { logger.warn("Service: removePlayer - A session ({}) nem volt X vagy O a játékban ({})", sessionId, gameId); }

        // Töröljük a gameId-t a kilépő session attribútumaiból
        session.getAttributes().remove("gameId");

        // Ha volt játék és nem ért véget korábban, lezárjuk
        if (game.isStarted() && !game.isGameOver()) {
            game.setGameOver(true); game.setStarted(false);
            logger.info("Service: Játék ({}) leállítva ({}) kilépése miatt.", gameId, leftPlayerMark != null ? leftPlayerMark : "?");
        }

        // Ha a játék kiürült (mindkét játékos null), eltávolítjuk a map-ből
        if (game.getPlayerX() == null && game.getPlayerO() == null) {
            logger.info("Service: Játék ({}) kiürült, eltávolítás az aktív játékok közül.", gameId);
            activeGames.remove(gameId);
        }

        return otherPlayer; // Visszaadjuk a másik játékost (ha volt), hogy a Handler értesíthesse
    }

    // Játék resetelése - MOST MÁR gameId alapján azonosít
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
        // Csak akkor resetelünk, ha a játék tele volt (vagy legalább elindult/véget ért)
        if (game.isFull() || game.isGameOver()) {
            logger.info("Service: Játék ({}) resetelése kérésre: {}", gameId, session.getId());
            startGameProcedure(game); // Újraindítja a játékot a meglévő játékosokkal
            return game;
        } else {
            logger.warn("Service: resetGame - A játék ({}) nem teljes vagy még el sem indult.", gameId);
            return null; // Nem resetelünk, ha pl. csak X van bent
        }
    }

    // Segédfüggvény a gameId kiolvasásához a sessionből
    private String getGameIdFromSession(WebSocketSession session) {
        if (session == null || session.getAttributes() == null) {
            return null;
        }
        return (String) session.getAttributes().get("gameId");
    }

    // Segédfüggvény a GameState lekéréséhez (Handlernek hasznos lehet)
    public GameState getGameState(String gameId) {
        return activeGames.get(gameId);
    }

    // --- Belső Osztályok/Rekordok ---
    public record PlayerAssignment(String assignedMark, boolean gameStarted, String gameId) {}
    public record GameResult(ResultType type, Object payload, String errorMessage) {
        public GameResult(ResultType type, GameState gameState) { this(type, gameState, null); }
        public GameResult(ResultType type, String errorMessage) { this(type, null, errorMessage); }
        public enum ResultType { UPDATE, GAMEOVER, ERROR }
    }
}