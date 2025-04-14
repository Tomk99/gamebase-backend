package com.tomk99.gamebasebackend.handler; // Használd a saját package neved!

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomk99.gamebasebackend.dto.*; // Importáljuk az összes DTO-t
import com.tomk99.gamebasebackend.model.GameState; // GameState import
import com.tomk99.gamebasebackend.service.GameService; // GameService import
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameWebSocketHandler.class);

    // Függőségek: Service és ObjectMapper
    private final GameService gameService;
    private final ObjectMapper objectMapper;

    public GameWebSocketHandler(GameService gameService, ObjectMapper objectMapper) {
        this.gameService = gameService;
        this.objectMapper = objectMapper;
        logger.info("GameWebSocketHandler inicializálva GameService-szel.");
    }

    // Nincsenek már itt állapotváltozók!

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Handler: Új kapcsolat: {}", session.getId());
        // Delegáljuk a játékos hozzáadását a Service-hez
        GameService.PlayerAssignment assignment = gameService.addPlayer(session);

        if (assignment != null) {
            // Sikeres hozzáadás, tároljuk a gameId-t a sessionben!
            session.getAttributes().put("gameId", assignment.gameId());
            logger.info("Handler: Session attribútum beállítva: gameId={}", assignment.gameId());

            sendMessage(session, "ASSIGN_PLAYER", new AssignPlayerPayload(assignment.assignedMark()));
            logger.info("Handler: Játékos hozzárendelve: {} -> Jel: {}, Játék ID: {}", session.getId(), assignment.assignedMark(), assignment.gameId());

            // Ha a játék ezzel a játékossal indult el
            if (assignment.gameStarted()) {
                // Lekérjük a friss GameState-et a Service-től (a gameId alapján, amit most már tudunk)
                GameState initialState = gameService.getGameState(assignment.gameId());
                if (initialState != null && initialState.isFull()) {
                    GameStatePayload initialPayload = new GameStatePayload(initialState.getBoard(), initialState.getCurrentPlayer());
                    // Kezdéskor GAME_START és GAME_UPDATE is mehet, vagy csak UPDATE
                    // A frontend mostmár az UPDATE-re is jól reagál elvileg a kezdéshez
                    broadcast(initialState.getPlayerX(), initialState.getPlayerO(), "GAME_START", initialPayload); // Maradhat ez is
                    broadcast(initialState.getPlayerX(), initialState.getPlayerO(), "GAME_UPDATE", initialPayload); // És ez is
                    logger.info("Handler: Játék ({}) elindult és állapot kiküldve.", assignment.gameId());
                } else {
                    logger.error("Handler: Hiba a játék ({}) kezdőállapotának lekérésekor vagy a játék nem teljes.", assignment.gameId());
                    // Lehet, hogy a másik játékos sessionje már nem él? Extra ellenőrzés
                    if (initialState != null) {
                        logger.error("GameState lekérve: PlayerX={}, PlayerO={}", initialState.getPlayerX(), initialState.getPlayerO());
                    }
                    // Hiba küldése a csatlakozó játékosnak?
                    // sendMessage(session, "ERROR", new ErrorPayload("Hiba a játék indításakor."));
                }
            }
        } else {
            // A Service null-t adott vissza -> a játék tele van vagy hiba történt
            logger.warn("Handler: Játékos ({}) hozzáadása sikertelen (játék tele vagy hiba).", session.getId());
            sendMessage(session, "ERROR", new ErrorPayload("A játék már folyamatban van, vagy hiba történt a csatlakozáskor."));
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Game full or error"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payloadString = message.getPayload();
        String gameId = (String) session.getAttributes().get("gameId"); // Kiolvassuk a gameId-t
        logger.info("Handler: Üzenet érkezett: Session={}, GameId={}, Üzenet={}", session.getId(), gameId, payloadString);

        // Csak akkor dolgozzuk fel, ha a session hozzá van rendelve egy játékhoz
        if (gameId == null) {
            logger.warn("Handler: Üzenet érkezett egy játékhoz nem rendelt sessiontől: {}", session.getId());
            sendMessage(session, "ERROR", new ErrorPayload("Nem vagy játékban."));
            return;
        }

        try {
            // Az üzenet feldolgozása (típus, payload)
            Map<String, Object> messageMap = objectMapper.readValue(payloadString, Map.class);
            String messageType = (String) messageMap.get("type");
            Object rawPayload = messageMap.get("payload");

            if ("MAKE_MOVE".equals(messageType) && rawPayload instanceof Map) {
                MakeMovePayload movePayload = objectMapper.convertValue(rawPayload, MakeMovePayload.class);
                // Delegáljuk a lépés kezelését a Service-hez
                GameService.GameResult result = gameService.handleMove(session, movePayload);
                handleGameResult(result, session); // Feldolgozzuk a Service válaszát

            } else if ("RESET_GAME".equals(messageType)) {
                logger.info("Handler: Reset kérés érkezett: Session={}, GameId={}", session.getId(), gameId);
                // Delegáljuk a resetet a Service-hez
                GameState newState = gameService.resetGame(session);
                if (newState != null && newState.isFull()) { // Csak akkor küldünk, ha a reset sikeres volt és vannak játékosok
                    GameStatePayload resetPayload = new GameStatePayload(newState.getBoard(), newState.getCurrentPlayer());
                    // Új játék esetén is GAME_UPDATE-et küldünk
                    broadcast(newState.getPlayerX(), newState.getPlayerO(), "GAME_UPDATE", resetPayload);
                    logger.info("Handler: Játék ({}) resetelve, új állapot kiküldve.", gameId);
                } else {
                    logger.warn("Handler: Reset kérés feldolgozása sikertelen (Game: {}).", gameId);
                    sendMessage(session, "ERROR", new ErrorPayload("Nem lehetett új játékot kezdeni."));
                }
            }
            // TODO: További üzenettípusok kezelése

        } catch (JsonProcessingException e) {
            logger.error("Handler: JSON feldolgozási hiba: {}", e.getMessage());
            sendMessage(session, "ERROR", new ErrorPayload("Érvénytelen üzenet formátum."));
        } catch (Exception e) {
            logger.error("Handler: Általános hiba az üzenet feldolgozása közben: {}", e.getMessage(), e);
            sendMessage(session, "ERROR", new ErrorPayload("Szerver oldali hiba."));
        }
    }

    // Feldolgozza a GameService által visszaadott eredményt
    private void handleGameResult(GameService.GameResult result, WebSocketSession originatingSession) {
        if (result == null) {
            logger.error("Handler: A GameService null eredménnyel tért vissza.");
            try { sendMessage(originatingSession, "ERROR", new ErrorPayload("Belső szerverhiba (null result).")); }
            catch (IOException e) { logger.error("Hiba az ERROR küldése közben handleGameResult-ban (null result): {}", e.getMessage());}
            return;
        }

        GameState currentGameState = (result.payload() instanceof GameState) ? (GameState) result.payload() : null;
        // Fontos: A sessionöket a GameState objektumból kell kiolvasni!
        WebSocketSession playerX = currentGameState != null ? currentGameState.getPlayerX() : null;
        WebSocketSession playerO = currentGameState != null ? currentGameState.getPlayerO() : null;

        try {
            switch (result.type()) {
                case UPDATE -> {
                    if (currentGameState != null) {
                        logger.info("Handler: Játék ({}) frissítés broadcast küldése...", currentGameState.getGameId());
                        GameStatePayload payload = new GameStatePayload(currentGameState.getBoard(), currentGameState.getCurrentPlayer());
                        broadcast(playerX, playerO, "GAME_UPDATE", payload);
                    } else {
                        logger.error("Handler: UPDATE eredményhez nem tartozott GameState payload.");
                    }
                }
                case GAMEOVER -> {
                    if (currentGameState != null) {
                        logger.info("Handler: Játék ({}) vége broadcast küldése...", currentGameState.getGameId());
                        GameOverPayload payload = new GameOverPayload(
                                currentGameState.getWinner(),
                                currentGameState.getBoard(),
                                currentGameState.getWinningLine() != null ? currentGameState.getWinningLine() : List.of()
                        );
                        broadcast(playerX, playerO, "GAME_OVER", payload);
                    } else {
                        logger.error("Handler: GAMEOVER eredményhez nem tartozott GameState payload.");
                    }
                }
                case ERROR -> {
                    logger.warn("Handler: Hiba a játék logikában ({}) : {}", (currentGameState != null ? currentGameState.getGameId() : "ismeretlen játék"), result.errorMessage());
                    // Csak az eredeti sessionnek küldjük a hibát
                    sendMessage(originatingSession, "ERROR", new ErrorPayload(result.errorMessage()));
                }
            }
        } catch (IOException e) { // sendMessage dobhat IOException-t
            logger.error("Handler: IOException történt üzenetküldés közben a handleGameResult-ban: {}", e.getMessage());
        }
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("Handler: Kapcsolat bontva: {}, Status: {}", session.getId(), status);
        // A Service removePlayer metódusa kezeli a játékállapotot és visszaadja a másik sessiont
        WebSocketSession otherPlayerSession = gameService.removePlayer(session);

        // Ha volt másik játékos, értesítjük
        if (otherPlayerSession != null) {
            try {
                String messageText = "Az ellenfeled lecsatlakozott.";
                sendMessage(otherPlayerSession, "OPPONENT_LEFT", new OpponentLeftPayload(messageText));
                logger.info("Handler: Értesítés küldve a másik játékosnak ({}).", otherPlayerSession.getId());
            } catch (IOException e) {
                logger.error("Handler: Hiba az ellenfél értesítése közben kilépéskor: {}", e.getMessage());
            }
        } else {
            logger.info("Handler: Nem volt másik játékos, vagy a játék már véget ért/törölve lett.");
        }
        // A session attribútumok automatikusan törlődnek, amikor a session bezárul,
        // de a gameId explicit törlése a removePlayer-ben is megtörténik a biztonság kedvéért.
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("Handler: Kommunikációs hiba: Session ID = {}, Hiba = {}", session.getId(), exception.getMessage());
        try {
            afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
        } catch (Exception e) {
            logger.error("Handler: Hiba az afterConnectionClosed hívása közben handleTransportError-ból: {}", e.getMessage(), e);
        } finally {
            if (session.isOpen()) {
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
            }
        }
    }


    // --- Segédfüggvények ---

    // sendMessage és broadcast MÓDOSÍTVA, hogy Object payloadot fogadjon
    private void sendMessage(WebSocketSession session, String type, Object payloadObject) throws IOException {
        if (session != null && session.isOpen()) {
            Map<String, Object> messageMap = Map.of("type", type, "payload", payloadObject);
            String jsonMessage = objectMapper.writeValueAsString(messageMap);
            logger.debug("Üzenet küldése neki: {} -> Type: {}", session.getId(), type);
            try {
                session.sendMessage(new TextMessage(jsonMessage));
            } catch (IOException e) {
                logger.error("HIBA üzenet küldése közben ({}) neki: {}", e.getMessage(), session.getId());
                try { session.close(CloseStatus.PROTOCOL_ERROR); } catch (IOException ignored) {}
                try { afterConnectionClosed(session, CloseStatus.PROTOCOL_ERROR); } catch (Exception cleanupEx) { logger.error("Hiba afterConnectionClosed hívásakor sendMessage hiba után: {}", cleanupEx.getMessage()); }
                // Nem dobjuk tovább, hogy a broadcast próbálkozhasson a másikkal
                // throw e;
            }
        } else {
            logger.warn("Session null vagy zárva, üzenet ({}) nem küldhető el.", type);
        }
    }

    // Broadcast most már paraméterként kapja a sessionöket
    private void broadcast(WebSocketSession playerX, WebSocketSession playerO, String type, Object payloadObject) {
        logger.info("Broadcast kísérlet: {}", type);
        try {
            logger.debug("Üzenet küldése X-nek ({})", playerX != null ? playerX.getId() : "null");
            sendMessage(playerX, type, payloadObject);
        } catch (IOException e) {
            logger.error("Hiba broadcast közben X felé ({}): {}", type, e.getMessage());
        }
        // Mindig megpróbáljuk elküldeni O-nak is, még ha X sikertelen volt is
        try{
            logger.debug("Üzenet küldése O-nak ({})", playerO != null ? playerO.getId() : "null");
            sendMessage(playerO, type, payloadObject);
        } catch (IOException e) {
            logger.error("Hiba broadcast közben O felé ({}): {}", type, e.getMessage());
        }
        // logger.info("Broadcast sikeresnek tűnik: {}", type); // Ezt kivesszük, mert nem tudjuk biztosan
    }
    // A checkWinner és isBoardFull metódusok már NEM itt vannak!
}