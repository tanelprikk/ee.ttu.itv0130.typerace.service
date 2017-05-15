package ee.ttu.itv0130.typerace.service.sockets.services;

import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import ee.ttu.itv0130.typerace.service.data_service.ScoreService;
import ee.ttu.itv0130.typerace.service.data_service.WordService;
import ee.ttu.itv0130.typerace.service.data_service.objects.RoundScore;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.GameMessageType;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.GameState;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.PlayerMessageType;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.PlayerSocketSession;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.messages.player.MessageJoinGame;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.messages.player.MessageSetNickname;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.messages.player.MessageTypeWord;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.messages.server.MessageBroadcastWord;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.messages.server.MessageJoinLobbyResponse;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.messages.server.MessageTerminateGame;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.messages.server.MessageTypeWordResponse;
import ee.ttu.itv0130.typerace.service.sockets.services.objects.messages.server.ServerMessage;

@Service
public class PlayerSocketService {
	@Autowired
	private ScoreService scoreService;
	@Autowired
	private WordService wordService;

	private static class LobbyItem {
		public PlayerSocketSession playerSession;
		public Date insertionDate;
	
		public LobbyItem(PlayerSocketSession playerSession, Date insertionDate) {
			this.playerSession = playerSession;
			this.insertionDate = insertionDate;
		}
	}

	private Map<String, PlayerSocketSession> socketMap = new ConcurrentHashMap<>();
	private Map<String, LobbyItem> lobbyMap = new ConcurrentHashMap<>();
	private Map<String, GameState> gameStateMap = new ConcurrentHashMap<>();

	public synchronized void handleMessage(WebSocketSession session, JSONObject jsonMessage) {
		String sessionId = session.getId();
		String type = jsonMessage.getString("type");
		PlayerMessageType messageType = PlayerMessageType.valueOf(type);
		PlayerSocketSession playerSession = socketMap.get(sessionId);
		
		switch (messageType) {
			case JOIN_GAME:
				handleJoinGameMessage(playerSession, new MessageJoinGame(jsonMessage));
				break;
			case TYPE_WORD:
				handleTypeWordMessage(playerSession, new MessageTypeWord(jsonMessage));
				break;
			case SET_NICKNAME:
				handleSetNicknameMessage(playerSession, new MessageSetNickname(jsonMessage));
				break;
			default:
				break;
		}
	}

	public synchronized void register(WebSocketSession session) {
		String sessionId = session.getId();
		MessageJoinLobbyResponse message = new MessageJoinLobbyResponse();
		message.setSessionId(sessionId);
		
		PlayerSocketSession gameSession = new PlayerSocketSession(session);
		socketMap.put(sessionId, gameSession);
		
		sendMessage(gameSession, message);
	}

	public synchronized void drop(WebSocketSession session) {
		String sessionId = session.getId();
		socketMap.remove(sessionId);
		scoreService.remove(sessionId);
		
		if (lobbyMap.containsKey(sessionId)) {
			lobbyMap.remove(sessionId);
		} else if (gameStateMap.containsKey(sessionId)) {
			GameState gameState = gameStateMap.get(sessionId);
			String otherSessionId = gameState.getOtherPlayerSessionId(sessionId);
			gameStateMap.remove(sessionId);
			gameStateMap.remove(otherSessionId);
			
			PlayerSocketSession otherPlayerSession = gameState.getPlayer(otherSessionId);
			MessageTerminateGame terminationMessage = new MessageTerminateGame();
			terminationMessage.setReason("Opponent left");
			// opponent should manually re-join the game
			sendMessage(otherPlayerSession, terminationMessage);
		}
	}

	private void handleJoinGameMessage(PlayerSocketSession playerSession, MessageJoinGame message) {
		if (gameStateMap.containsKey(playerSession.getId())) {
			// already in-game
			return;
		}
		
		joinGame(playerSession);
	}

	private void handleSetNicknameMessage(PlayerSocketSession playerSession, MessageSetNickname message) {
		// nickname is broadcast together with current word
		String nickname = message.getNickname();
		playerSession.setNickname(nickname);
	}

	private void handleTypeWordMessage(PlayerSocketSession playerSession, MessageTypeWord message) {
		GameState gameState = gameStateMap.get(playerSession.getId());
		MessageTypeWordResponse responseMessage = new MessageTypeWordResponse();
		GameMessageType gameMessageType;
		
		if (gameState != null) {
			// game exists
			Long currentTimeMillis = new Date().getTime();
			if (gameState.getCurrentWord().equals(message.getWord())) {
				Long playerTimeMillis = currentTimeMillis - gameState.getRoundStartedMillis();
				gameState.setPlayerTime(playerSession.getId(), playerTimeMillis);
				responseMessage.setPlayerTimeMillis(playerTimeMillis);
				int loserScore = gameState.getCurrentWord().length();
				int winnerScore = loserScore * 10;
				
				if (gameState.hasWinner()) {
					gameMessageType = GameMessageType.ROUND_LOST;
					responseMessage.setPlayerScore(loserScore);
					
					// store scores
					String otherPlayerSessionId = gameState.getOtherPlayerSessionId(playerSession.getId());
					RoundScore roundScore = new RoundScore();
					roundScore.setDidWin(false);
					roundScore.setPlayerTimeMillis(playerTimeMillis);
					roundScore.setPlayerScore(loserScore);
					roundScore.setOpponentScore(winnerScore);
					roundScore.setOpponentTimeMillis(gameState.getPlayerTime(otherPlayerSessionId));
					scoreService.addRoundScore(playerSession.getId(), roundScore);
					scoreService.addRoundScore(otherPlayerSessionId, roundScore.forOpponent());
					
					// start the next round
					startNewRound(gameState);
				} else {
					gameMessageType = GameMessageType.ROUND_WON;
					responseMessage.setPlayerScore(winnerScore);
					gameState.setHasWinner(true);
				}
			} else {
				gameMessageType = GameMessageType.WORD_MISMATCH;
			}
		} else {
			// no game found
			gameMessageType = GameMessageType.NO_GAME_FOUND;
		}
		
		responseMessage.setGameMessageType(gameMessageType);
		sendMessage(playerSession, responseMessage);
	}

	private void joinGame(PlayerSocketSession playerSession) {
		if (lobbyMap.isEmpty()) {
			lobbyMap.put(playerSession.getId(), new LobbyItem(playerSession, new Date()));
		} else {
			String lobbyKey = lobbyMap.entrySet()
				.stream()
				.reduce(null, (accEntry, currEntry) -> {
					if (accEntry == null) {
						return currEntry;
					}
					
					LobbyItem acc = accEntry.getValue();
					LobbyItem curr = currEntry.getValue();
					if (curr.insertionDate.before(acc.insertionDate)) {
						return currEntry;
					}
					
					return accEntry;
				})
				.getKey();
			
			PlayerSocketSession otherPlayerSession = lobbyMap.remove(lobbyKey).playerSession;
			startNewGame(playerSession, otherPlayerSession);
		}
	}

	private void startNewGame(PlayerSocketSession firstPlayer, PlayerSocketSession secondPlayer) {
		GameState gameState = new GameState();
		gameState.addPlayer(firstPlayer);
		gameState.addPlayer(secondPlayer);
		
		gameStateMap.put(firstPlayer.getId(), gameState);
		gameStateMap.put(secondPlayer.getId(), gameState);
		
		startNewRound(gameState);
	}

	private void startNewRound(GameState gameState) {
		String nextWord = null;
		
		do {
			nextWord = wordService.getRandomWord();
		} while (gameState.getPreviousWords().contains(nextWord));
		
		gameState.setCurrentWord(nextWord);
		gameState.setRoundStartedMillis(new Date().getTime());
		gameState.setHasWinner(false);
		
		broadcastWord(gameState);
	}

	private void broadcastWord(GameState gameState) {
		MessageBroadcastWord message = new MessageBroadcastWord();
		message.setWord(gameState.getCurrentWord());
		
		for (PlayerSocketSession playerSession : gameState.getPlayers()) {
			sendMessage(playerSession, message);
		}
	}

	private void sendMessage(PlayerSocketSession playerSession, ServerMessage message) {
		sendResponse(playerSession, message.toJSON());
	}

	private void sendResponse(PlayerSocketSession playerSession, JSONObject responseJson) {
		try {
			playerSession.send(responseJson);
		} catch (IOException e) {
			// ignore for now
		}
	}
}
