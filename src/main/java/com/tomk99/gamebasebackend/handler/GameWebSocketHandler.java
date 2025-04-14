package com.tomk99.gamebasebackend.handler; // Használd a saját package neved!

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomk99.gamebasebackend.dto.*; // DTO importok
import com.tomk99.gamebasebackend.model.GameState; // GameState import
import com.tomk99.gamebasebackend.service.GameService; // GameService import
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
// Handler már nem @Component
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
                    broadcast(initialState.getPlayerX(), initialState.getPlayerO(), "GAME_START", initialPayload); // Maradhat ez is
                    broadcast(initialState.getPlayerX(), initialState.getPlayerO(), "GAME_UPDATE", initialPayload); // És ez is
                    logger.info("Handler: Játék ({}) elindult és állapot kiküldve.", assignment.gameId());
                } else {
                    logger.error("Handler: Hiba a játék ({}) kezdőállapotának lekérésekor vagy a játék nem teljes.", assignment.gameId());
                    if (initialState != null) { logger.error("GameState lekérve: PlayerX={}, PlayerO={}", initialState.getPlayerX(), initialState.getPlayerO()); }
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

        GameService.GameResult result = null; // Előre deklaráljuk a result-ot
        try {
            Map<String, Object> messageMap = objectMapper.readValue(payloadString, Map.class);
            String messageType = (String) messageMap.get("type");
            Object rawPayload = messageMap.get("payload");

            if ("MAKE_MOVE".equals(messageType) && rawPayload instanceof Map) {
                MakeMovePayload movePayload = objectMapper.convertValue(rawPayload, MakeMovePayload.class);

                logger.info("Handler: Hívja a gameService.handleMove metódust (GameId: {})...", gameId);
                // <<< TRY-CATCH a Service hívás köré >>>
                try {
                    result = gameService.handleMove(session, movePayload);
                    logger.info("Handler: Visszatérés a gameService.handleMove-ból. Result Type: {}",
                            result != null ? result.type() : "null");
                } catch (Exception serviceException) {
                    logger.error("Handler: KIVÉTEL a gameService.handleMove hívása közben! GameId: {}, Hiba: {}",
                            gameId, serviceException.getMessage(), serviceException);
                    try {sendMessage(session, "ERROR", new ErrorPayload("Hiba történt a lépés feldolgozása közben."));} catch (IOException ignored) {}
                    return; // Ne folytassuk a feldolgozást, ha a service hibát dobott
                }
                // <<< TRY-CATCH VÉGE >>>

                // Csak akkor hívjuk a handleGameResult-ot, ha a service nem null-t adott vissza
                if (result != null) {
                    logger.info("Handler: Meghívja a handleGameResult metódust...");
                    handleGameResult(result, session);
                    logger.info("Handler: handleGameResult feldolgozás után.");
                } else {
                    logger.error("Handler: A gameService.handleMove null eredménnyel tért vissza kivétel nélkül! GameId: {}", gameId);
                    try {sendMessage(session, "ERROR", new ErrorPayload("Belső szerverhiba (null result)."));} catch (IOException ignored) {}
                }

            } else if ("RESET_GAME".equals(messageType)) {
                logger.info("Handler: Reset kérés érkezett: Session={}, GameId={}", session.getId(), gameId);
                GameState newState = gameService.resetGame(session);
                if (newState != null && newState.isFull()) {
                    GameStatePayload resetPayload = new GameStatePayload(newState.getBoard(), newState.getCurrentPlayer());
                    broadcast(newState.getPlayerX(), newState.getPlayerO(), "GAME_UPDATE", resetPayload);
                    logger.info("Handler: Játék ({}) resetelve, új állapot kiküldve.", gameId);
                } else {
                    logger.warn("Handler: Reset kérés feldolgozása sikertelen (Game: {}).", gameId);
                    sendMessage(session, "ERROR", new ErrorPayload("Nem lehetett új játékot kezdeni."));
                }
            } else {
                logger.warn("Handler: Ismeretlen vagy nem támogatott üzenettípus ebben az állapotban: {}", messageType);
                // sendMessage(session, "ERROR", new ErrorPayload("Ismeretlen kérés: " + messageType));
            }

        } catch (JsonProcessingException e) {
            logger.error("Handler: JSON feldolgozási hiba: {}", e.getMessage());
            sendMessage(session, "ERROR", new ErrorPayload("Érvénytelen üzenet formátum."));
        } catch (Exception e) {
            // Itt minden más váratlan hibát elkapunk
            logger.error("Handler: Általános hiba az üzenet feldolgozása közben: {}", e.getMessage(), e);
            sendMessage(session, "ERROR", new ErrorPayload("Szerver oldali hiba."));
        }
    }

    // Feldolgozza a GameService által visszaadott eredményt - RÉSZLETESEBB LOGOLÁSSAL
    private void handleGameResult(GameService.GameResult result, WebSocketSession originatingSession) {
        // Logoljuk a belépést és a kapott eredményt
        logger.info("Handler: ---> handleGameResult ENTRY <---. Result Type: {}, Error: {}, Originating Session: {}",
                result != null ? result.type() : "null",
                result != null ? result.errorMessage() : "N/A",
                originatingSession != null ? originatingSession.getId() : "null");

        if (result == null) {
            logger.error("Handler: handleGameResult - GameService null eredménnyel tért vissza. Kilépés.");
            try { sendMessage(originatingSession, "ERROR", new ErrorPayload("Belső szerverhiba (null result).")); }
            catch (IOException e) { logger.error("Hiba az ERROR küldése közben handleGameResult-ban (null result): {}", e.getMessage());}
            return; // Kilépünk, ha nincs result
        }

        // Próbáljuk meg kinyerni a GameState-et
        GameState currentGameState = (result.payload() instanceof GameState) ? (GameState) result.payload() : null;
        logger.info("Handler: handleGameResult - GameState kinyerve? {}", (currentGameState != null));
        if (currentGameState != null) {
            logger.info("Handler: handleGameResult - GameState ID: {}", currentGameState.getGameId());
        }

        // Próbáljuk meg kinyerni a sessionöket a GameState-ből
        WebSocketSession playerX = currentGameState != null ? currentGameState.getPlayerX() : null;
        WebSocketSession playerO = currentGameState != null ? currentGameState.getPlayerO() : null;
        logger.info("Handler: handleGameResult - Kinyert Sessionök - PlayerX: {} (Open: {}), PlayerO: {} (Open: {})",
                playerX != null ? playerX.getId() : "null",
                playerX != null && playerX.isOpen(), // isOpen() hívás
                playerO != null ? playerO.getId() : "null",
                playerO != null && playerO.isOpen()); // isOpen() hívás

        try {
            switch (result.type()) {
                case UPDATE:
                    logger.info("Handler: handleGameResult - UPDATE ág ENTER.");
                    if (currentGameState != null) {
                        // Ellenőrizzük a sessionöket közvetlenül a broadcast előtt
                        boolean canBroadcast = (playerX != null && playerX.isOpen()) && (playerO != null && playerO.isOpen());
                        logger.info("Handler: handleGameResult - UPDATE ág. Broadcast hívása ({}) játékhoz... Lehetséges? {}", currentGameState.getGameId(), canBroadcast);
                        if (canBroadcast) {
                            GameStatePayload payload = new GameStatePayload(currentGameState.getBoard(), currentGameState.getCurrentPlayer());
                            broadcast(playerX, playerO, "GAME_UPDATE", payload); // A broadcast hívás
                            logger.info("Handler: handleGameResult - UPDATE ág. Broadcast hívás UTÁN.");
                        } else {
                            logger.error("Handler: handleGameResult - UPDATE ág. Broadcast nem lehetséges, mert az egyik vagy mindkét session null vagy zárva!");
                        }
                    } else { logger.error("Handler: UPDATE eredményhez nem tartozott GameState payload."); }
                    break;
                case GAMEOVER:
                    logger.info("Handler: handleGameResult - GAMEOVER ág ENTER.");
                    if (currentGameState != null) {
                        boolean canBroadcast = (playerX != null && playerX.isOpen()) && (playerO != null && playerO.isOpen());
                        logger.info("Handler: handleGameResult - GAMEOVER ág. Broadcast hívása ({}) játékhoz... Lehetséges? {}", currentGameState.getGameId(), canBroadcast);
                        if (canBroadcast) {
                            GameOverPayload payload = new GameOverPayload(
                                    currentGameState.getWinner(),
                                    currentGameState.getBoard(),
                                    currentGameState.getWinningLine() != null ? currentGameState.getWinningLine() : List.of()
                            );
                            broadcast(playerX, playerO, "GAME_OVER", payload); // A broadcast hívás
                            logger.info("Handler: handleGameResult - GAMEOVER ág. Broadcast hívás UTÁN.");
                        } else {
                            logger.error("Handler: handleGameResult - GAMEOVER ág. Broadcast nem lehetséges, mert az egyik vagy mindkét session null vagy zárva!");
                        }
                    } else { logger.error("Handler: GAMEOVER eredményhez nem tartozott GameState payload."); }
                    break;
                case ERROR:
                    logger.warn("Handler: handleGameResult - ERROR ág. Hiba: {}", result.errorMessage());
                    sendMessage(originatingSession, "ERROR", new ErrorPayload(result.errorMessage()));
                    break;
                default:
                    logger.warn("Handler: handleGameResult - Ismeretlen ResultType: {}", result.type());
                    break;
            }
        } catch (IOException e) {
            logger.error("Handler: IOException történt üzenetküldés közben a handleGameResult-ban (valószínűleg a sendMessage hívásban az ERROR ágon): {}", e.getMessage());
        } catch (Exception e) {
            // Egyéb váratlan hibák elkapása
            logger.error("Handler: Váratlan hiba a handleGameResult switch blokkjában: {}", e.getMessage(), e);
        }
        logger.info("Handler: ---> handleGameResult EXIT <---.");
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("Handler: Kapcsolat bontva: {}, Status: {}", session.getId(), status);
        WebSocketSession otherPlayerSession = gameService.removePlayer(session);

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
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("Handler: Kommunikációs hiba: Session ID = {}, Hiba = {}", session != null ? session.getId() : "null", exception.getMessage());
        if (session != null) {
            try { afterConnectionClosed(session, CloseStatus.SERVER_ERROR); }
            catch (Exception e) { logger.error("Handler: Hiba az afterConnectionClosed hívása közben handleTransportError-ból: {}", e.getMessage(), e); }
            finally { if (session.isOpen()) { try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) {} } }
        }
    }


    // --- Segédfüggvények ---
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
                // throw e; // Nem dobjuk tovább, hogy a broadcast próbálkozhasson
            }
        } else {
            logger.warn("Session null vagy zárva, üzenet ({}) nem küldhető el.", type);
        }
    }

    private void broadcast(WebSocketSession playerX, WebSocketSession playerO, String type, Object payloadObject) {
        logger.info("Broadcast kísérlet: {}", type);
        try {
            logger.debug("Üzenet küldése X-nek ({})", playerX != null ? playerX.getId() : "null");
            sendMessage(playerX, type, payloadObject);
        } catch (IOException e) {
            logger.error("Hiba broadcast közben X felé ({}): {}", type, e.getMessage());
        }
        try{
            logger.debug("Üzenet küldése O-nak ({})", playerO != null ? playerO.getId() : "null");
            sendMessage(playerO, type, payloadObject);
        } catch (IOException e) {
            logger.error("Hiba broadcast közben O felé ({}): {}", type, e.getMessage());
        }
    }
    // Nincs már itt checkWinner, isBoardFull!
}