package com.tomk99.gamebasebackend.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameWebSocketHandler.class);

    // --- Játékállapot Tárolása (Egyszerűsített - 1 játékhoz) ---
    private WebSocketSession playerXSession = null;
    private WebSocketSession playerOSession = null;
    private static final int BOARD_SIZE = 10;
    private String[][] board = null; // Tábla állapota (null, ha nincs játék)
    private String currentPlayer = null; // Ki következik ('X' vagy 'O')
    private boolean gameStarted = false;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Kapcsolat Létrehozása ---
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Új kapcsolat: {}", session.getId());

        if (playerXSession == null) {
            playerXSession = session;
            sendMessage(session, Map.of("type", "ASSIGN_PLAYER", "payload", Map.of("mark", "X")));
            logger.info("Player X csatlakozott: {}", session.getId());
        } else if (playerOSession == null) {
            playerOSession = session;
            sendMessage(session, Map.of("type", "ASSIGN_PLAYER", "payload", Map.of("mark", "O")));
            logger.info("Player O csatlakozott: {}", session.getId());
            // Második játékos csatlakozott, indítjuk a játékot
            startGame();
        } else {
            // Már van két játékos, további kapcsolatokat elutasítunk (vagy nézőként kezelhetnénk)
            logger.warn("Harmadik játékos próbált csatlakozni: {}. Kapcsolat bontva.", session.getId());
            sendMessage(session, Map.of("type", "ERROR", "payload", Map.of("message", "A játék már folyamatban van.")));
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Game full"));
        }
    }

    // --- Játék Indítása ---
    private void startGame() {
        if (playerXSession != null && playerOSession != null) {
            board = new String[BOARD_SIZE][BOARD_SIZE]; // Üres tábla inicializálása
            // Kezdőértékek (null helyett üres string vagy más jelölés is lehet)
            for (int i = 0; i < BOARD_SIZE; i++) {
                for (int j = 0; j < BOARD_SIZE; j++) {
                    board[i][j] = null;
                }
            }
            currentPlayer = "X"; // X kezd
            gameStarted = true;
            logger.info("Játék elindult: {} (X) vs {} (O)", playerXSession.getId(), playerOSession.getId());

            // Üzenet mindkét játékosnak a játék kezdetéről és az aktuális állapotról
            Map<String, Object> gameState = Map.of(
                    "board", board,
                    "currentPlayer", currentPlayer
            );
            broadcast(Map.of("type", "GAME_START", "payload", gameState));
            // broadcast(Map.of("type", "GAME_UPDATE", "payload", gameState)); // Kezdeti update elég lehet a GAME_START után
        }
    }

    // --- Üzenet Kezelése ---
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        logger.info("Üzenet érkezett: {} -> {}", session.getId(), payload);

        try {
            // JSON üzenet feldolgozása Mappé
            Map<String, Object> messageMap = objectMapper.readValue(payload, Map.class);
            String messageType = (String) messageMap.get("type");

            if ("MAKE_MOVE".equals(messageType) && gameStarted) {
                handleMakeMove(session, (Map<String, Object>) messageMap.get("payload"));
            } else if ("RESET_GAME".equals(messageType)) {
                // Kezeljük a reset kérést (ha mindkét játékos csatlakozva van)
                if (playerXSession != null && playerOSession != null) {
                    logger.info("Reset kérés érkezett: {}", session.getId());
                    startGame(); // Újraindítjuk a játékot
                }
            }
            // TODO: További üzenettípusok kezelése (pl. CHAT)

        } catch (Exception e) {
            logger.error("Hiba az üzenet feldolgozása közben: Session ID = {}, Üzenet = {}, Hiba = {}",
                    session.getId(), payload, e.getMessage());
            sendMessage(session, Map.of("type", "ERROR", "payload", Map.of("message", "Érvénytelen üzenet formátum.")));
        }
    }

    // --- Lépés Kezelése (handleMakeMove) - BŐVÍTETT LOGOLÁSSAL ---
    private void handleMakeMove(WebSocketSession session, Map<String, Object> payload) throws Exception {
        String playerMark = getPlayerMark(session);
        // Ellenőrizzük, hogy a megfelelő játékos lép-e
        if (playerMark == null || !playerMark.equals(currentPlayer)) {
            sendMessage(session, Map.of("type", "ERROR", "payload", Map.of("message", "Nem te következel!")));
            logger.warn("Rossz játékos próbált lépni: {} (Mark: {}), Aktuális: {}", session.getId(), playerMark, currentPlayer);
            return;
        }

        Integer row = (Integer) payload.get("row");
        Integer col = (Integer) payload.get("col");

        // Ellenőrizzük, hogy a lépés érvényes-e a táblán
        if (row == null || col == null || row < 0 || row >= BOARD_SIZE || col < 0 || col >= BOARD_SIZE || board[row][col] != null) {
            sendMessage(session, Map.of("type", "ERROR", "payload", Map.of("message", "Érvénytelen lépés!")));
            logger.warn("Érvénytelen lépés: {} -> [{}, {}]", session.getId(), row, col);
            return;
        }

        // Lépés végrehajtása
        board[row][col] = currentPlayer;
        logger.info("Lépés rögzítve: {} -> [{}, {}]", currentPlayer, row, col); // << EDDIG LÁTTUK A LOGOT

        // Győzelem/Döntetlen ellenőrzés
        boolean won = checkWinner(board, row, col, currentPlayer);
        boolean full = !won && isBoardFull(board);

        if (won || full) {
            gameStarted = false;
            String winner = won ? currentPlayer : "draw";
            logger.info("Játék vége! Nyertes: {}. Broadcast küldése...", winner); // << LOG HOZZÁADVA
            broadcast(Map.of("type", "GAME_OVER", "payload", Map.of("winner", winner, "board", board)));
        } else {
            // Játékos váltás
            String previousPlayer = currentPlayer;
            currentPlayer = ("X".equals(currentPlayer)) ? "O" : "X";
            logger.info("Játékos váltás: {} -> {}. Broadcast küldése...", previousPlayer, currentPlayer); // << LOG HOZZÁADVA
            Map<String, Object> gameState = Map.of(
                    "board", board,
                    "currentPlayer", currentPlayer
            );
            broadcast(Map.of("type", "GAME_UPDATE", "payload", gameState));
        }
        logger.info("handleMakeMove vége: {}", session.getId()); // << LOG HOZZÁADVA
    }

    // --- Kapcsolat Bontása ---
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("Kapcsolat bontva: {}, Status: {}", session.getId(), status);
        String disconnectedPlayerMark = getPlayerMark(session);
        WebSocketSession otherPlayerSession = null; // Melyik a másik session?

        if (session.equals(playerXSession)) {
            playerXSession = null;
            otherPlayerSession = playerOSession; // A másik O volt
            logger.info("Player X lecsatlakozott.");
        } else if (session.equals(playerOSession)) {
            playerOSession = null;
            otherPlayerSession = playerXSession; // A másik X volt
            logger.info("Player O lecsatlakozott.");
        }

        // Ha volt játék és a másik játékos még kapcsolódva van, értesítjük
        if (gameStarted && otherPlayerSession != null) {
            try {
                String messageText = "Az ellenfeled (" + (disconnectedPlayerMark != null ? disconnectedPlayerMark : "?") + ") lecsatlakozott.";
                sendMessage(otherPlayerSession, Map.of("type", "OPPONENT_LEFT", "payload", Map.of("message", messageText)));
                logger.info("Értesítés küldve a másik játékosnak ({}): {}", otherPlayerSession.getId(), messageText);
            } catch (IOException e) {
                logger.error("Hiba az ellenfél értesítése közben kilépéskor: {}", e.getMessage());
            }
        }

        // Ha bármelyik játékos kilép, a játék véget ér ebben az egyszerű implementációban
        if (playerXSession == null || playerOSession == null) {
            if (gameStarted) {
                gameStarted = false;
                board = null;
                currentPlayer = null;
                logger.info("Játék leállítva az egyik játékos kilépése miatt.");
            }
            // Ha már csak egy van bent, lehet, hogy őt is bontjuk, vagy várhat új ellenfélre
            // Most egyszerűen csak vár, de a játék nem folytatódik.
        }

        // Ha mindketten kiléptek, a játék alaphelyzetbe áll
        if (playerXSession == null && playerOSession == null) {
            resetGame();
        }
    }

    // --- Hiba Kezelése ---
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        // Először logoljuk az eredeti transport hibát
        logger.error("Kommunikációs hiba (handleTransportError): Session ID = {}, Hiba = {}",
                session != null ? session.getId() : "null", exception.getMessage());

        // Csak akkor próbáljunk meg további műveleteket, ha van session objektum
        if (session != null) {
            try {
                // Próbáljuk meg lefuttatni a normál kapcsolatbontási logikát
                // (ez eltávolítja a sessiont a listából, értesíti a másik játékost, stb.)
                logger.debug("handleTransportError: Meghívja az afterConnectionClosed-t a session eltávolításához: {}", session.getId());
                afterConnectionClosed(session, CloseStatus.SERVER_ERROR); // << HÍVÁS MOST TRY-CATCH-BEN
            } catch (Exception e) {
                // Logoljuk, ha hiba történt a kapcsolatbontási logika futtatása közben is
                logger.error("Hiba az afterConnectionClosed hívása közben handleTransportError-ból: {}", e.getMessage(), e);
                // Itt már valószínűleg nincs szükség a session manuális eltávolítására,
                // mert az afterConnectionClosed vagy sikeres volt, vagy maga is hibát dobott,
                // de a biztonság kedvéért megtehetjük, ha gondoljuk.
                // if (session.equals(playerXSession)) playerXSession = null;
                // if (session.equals(playerOSession)) playerOSession = null;
                // if (playerXSession == null && playerOSession == null) { resetGame(); }
            } finally {
                // Mindig próbáljuk meg bezárni a sessiont a végén, ha még nyitva van
                if (session.isOpen()) {
                    try {
                        logger.debug("handleTransportError: Session bezárása (finally blokk): {}", session.getId());
                        session.close(CloseStatus.SERVER_ERROR);
                    } catch (IOException ioException) {
                        // Ezt a hibát már csak logoljuk, mert a finally-ban vagyunk
                        logger.error("Hiba a session bezárása közben handleTransportError finally blokkjában: {}", ioException.getMessage());
                    }
                }
            }
        } else {
            // Logoljuk, ha valamiért null sessionnel hívódott meg a hibakezelő
            logger.error("handleTransportError hívva null session-nel. Kiváltó hiba: {}", exception.getMessage());
        }
    }

    // --- Segédfüggvények ---

    // Visszaállítja a játékot alaphelyzetbe
    private void resetGame() {
        board = null;
        currentPlayer = null;
        gameStarted = false;
        playerXSession = null; // Biztos, ami biztos
        playerOSession = null;
        logger.info("Játék alaphelyzetbe állítva (resetGame).");
    }


    // Visszaadja, hogy a session X vagy O játékoshoz tartozik-e
    private String getPlayerMark(WebSocketSession session) {
        if (session != null && session.equals(playerXSession)) return "X";
        if (session != null && session.equals(playerOSession)) return "O";
        return null;
    }

    // Üzenet küldése egy adott session-nek (JSON formátumban) - BŐVÍTETT LOGOLÁSSAL
    private void sendMessage(WebSocketSession session, Map<String, Object> messageMap) throws Exception {
        if (session != null && session.isOpen()) {
            String jsonMessage = objectMapper.writeValueAsString(messageMap);
            // Csökkentett logolás, hogy ne legyen túl zajos, csak ha hiba van, vagy debug szinten
            // logger.info("Üzenet küldése neki: {} -> {}", session.getId(), jsonMessage);
            try {
                session.sendMessage(new TextMessage(jsonMessage));
                logger.debug("Üzenet elküldve: {} -> Type: {}", session.getId(), messageMap.get("type")); // DEBUG szintű log
            } catch (IOException e) {
                logger.error("HIBA üzenet küldése közben ({}) neki: {}", e.getMessage(), session.getId());
                // Hiba esetén megpróbálhatjuk bezárni a kapcsolatot
                try { session.close(CloseStatus.PROTOCOL_ERROR); } catch (IOException ignored) {}
                // És eltávolítjuk a sessiont (a hívó felelőssége lehetne, de itt is jó)
                afterConnectionClosed(session, CloseStatus.PROTOCOL_ERROR);
                throw e; // Dobjuk tovább a kivételt, hogy a hívó (pl. broadcast) tudjon róla
            }
        } else {
            // Ez a log segít, ha a session már bezárult vagy null volt
            logger.warn("Session null vagy zárva, üzenet ({}) nem küldhető el.", messageMap.get("type"));
        }
    }

    // Broadcast - BŐVÍTETT LOGOLÁSSAL
    private void broadcast(Map<String, Object> messageMap) {
        String messageType = (String) messageMap.get("type");
        logger.info("Broadcast kísérlet: {}", messageType); // Látnunk kell ezt
        try {
            logger.debug("Üzenet küldése X-nek ({})", playerXSession != null ? playerXSession.getId() : "null");
            sendMessage(playerXSession, messageMap);
            logger.debug("Üzenet küldése O-nak ({})", playerOSession != null ? playerOSession.getId() : "null");
            sendMessage(playerOSession, messageMap);
            logger.info("Broadcast sikeresnek tűnik: {}", messageType); // Ezt is látnunk kellene
        } catch (Exception e) {
            // A sendMessage már logolhatott IO hibát, de itt is logolunk általános hibát
            logger.error("Hiba broadcast ({}) közben: {}", messageType, e.getMessage(), e);
        }
    }

    // --- Győzelem/Döntetlen Ellenőrző Függvények (Bemmásolva ide) ---
    // Fontos: Ezeknek a függvényeknek az osztályon belül kell lenniük, hogy a handleMakeMove elérje őket.
    private boolean checkWinner(String[][] board, int row, int col, String player) {
        if (board == null) return false;
        int size = board.length;
        int winLength = 5;

        final int dr[] = {0, 1, 1, 1}; // Vízszintes, Függőleges, Átló\, Átló/
        final int dc[] = {1, 0, 1, -1};

        for (int i = 0; i < 4; i++) {
            int count = 1;
            for (int j = 1; j < winLength; j++) {
                int r = row + dr[i] * j; int c = col + dc[i] * j;
                if (r >= 0 && r < size && c >= 0 && c < size && board[r][c] != null && board[r][c].equals(player)) count++;
                else break;
            }
            for (int j = 1; j < winLength; j++) {
                int r = row - dr[i] * j; int c = col - dc[i] * j;
                if (r >= 0 && r < size && c >= 0 && c < size && board[r][c] != null && board[r][c].equals(player)) count++;
                else break;
            }
            if (count >= winLength) return true;
        }
        return false;
    }

    private boolean isBoardFull(String[][] board) {
        if (board == null) return false;
        for (int r = 0; r < board.length; r++) {
            for (int c = 0; c < board[r].length; c++) {
                if (board[r][c] == null) {
                    return false;
                }
            }
        }
        return true;
    }
}