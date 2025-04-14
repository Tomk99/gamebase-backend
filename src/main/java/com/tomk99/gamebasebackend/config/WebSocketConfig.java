package com.tomk99.gamebasebackend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomk99.gamebasebackend.handler.GameWebSocketHandler;
import com.tomk99.gamebasebackend.service.GameService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameService gameService;
    private final ObjectMapper objectMapper;

    @Autowired
    public WebSocketConfig(GameService gameService, ObjectMapper objectMapper) {
        this.gameService = gameService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler(), "/websocket/game")
                .setAllowedOrigins("*");
    }

    @Bean
    public WebSocketHandler gameWebSocketHandler() {
        return new GameWebSocketHandler(gameService, objectMapper);
    }
}