package com.tomk99.gamebasebackend.dto;

import java.util.List;
import java.util.Map;

public record GameOverPayload(String winner, String[][] board, List<Map<String, Integer>> winningLine) {
}
