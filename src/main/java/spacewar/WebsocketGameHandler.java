package spacewar;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class WebsocketGameHandler extends TextWebSocketHandler {

	private static final String PLAYER_ATTRIBUTE = "PLAYER";
	private static final String ROOM_ATTRIBUTE = "ROOM";
	
	private ObjectMapper mapper = new ObjectMapper();
	private AtomicInteger playerId = new AtomicInteger(0);
	private AtomicInteger projectileId = new AtomicInteger(0);
	
	private Map<Room, SpacewarGame> rooms = new HashMap<>();

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		Player player = new Player(playerId.incrementAndGet(), session);
		session.getAttributes().put(PLAYER_ATTRIBUTE, player);
		
		ObjectNode msg = mapper.createObjectNode();
		msg.put("event", "JOIN");
		msg.put("id", player.getPlayerId());
		msg.put("shipType", player.getShipType());
		player.getSession().sendMessage(new TextMessage(msg.toString()));
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		try {
			JsonNode node = mapper.readTree(message.getPayload());
			ObjectNode msg = mapper.createObjectNode();
			Player player = (Player) session.getAttributes().get(PLAYER_ATTRIBUTE);
			SpacewarGame swg;
			Room room;

			switch (node.get("event").asText()) {
			case "JOIN":
				msg.put("event", "JOIN");
				msg.put("id", player.getPlayerId());
				msg.put("shipType", player.getShipType());
				player.getSession().sendMessage(new TextMessage(msg.toString()));
				break;
			case "UPDATE PLAYER":
				player.setUsername(node.get("username").asText()); 
				player.setShipType(node.get("ship").asText());
				break;
			case "CREATE ROOM":
				for(Room r: rooms.keySet()) {
					if (node.get("name").asText() == r.getRoomId()) {
						msg.put("event", "NEW ROOM");
						msg.put("room", -1);
						player.getSession().sendMessage(new TextMessage(msg.toString()));
						break;
					}
				}
				room = new Room(node.get("name").asText(), node.get("mode").asInt(), node.get("maxPlayers").asInt(), node.get("difficulty").asInt());
				session.getAttributes().put(ROOM_ATTRIBUTE, room);
				
				swg = SpacewarGame.INSTANCE;
				swg.addPlayer(player);
				
				rooms.put(room, swg);
				
				msg.put("event", "NEW ROOM");
				msg.put("room", room.getRoomId());
				player.getSession().sendMessage(new TextMessage(msg.toString()));
				break;
			case "JOIN ROOM":
				boolean found = false;
				String sala = node.get("room").asText();
				for(Room r: rooms.keySet()) {
					if (sala.equals(r.getRoomId())) {
						session.getAttributes().put(ROOM_ATTRIBUTE, r);
						swg = rooms.get(r);
						swg.addPlayer(player);
						
						found = true;
						
						msg.put("event", "NEW ROOM");
						msg.put("room", sala);
						player.getSession().sendMessage(new TextMessage(msg.toString()));
						break;
					}
				}
				
				if (!found) {
					msg.put("event", "NEW ROOM");
					msg.put("room", -1);
					player.getSession().sendMessage(new TextMessage(msg.toString()));
				}
				break;
			case "GET ROOMS":
				ArrayNode arrayNodeRooms = mapper.createArrayNode();
				msg.put("event", "GET ROOMS");
				for(Room r: rooms.keySet()) {
					ObjectNode jsonRoom = mapper.createObjectNode();
					jsonRoom.put("name", r.getRoomId());
					jsonRoom.put("mode", r.getMode());
					jsonRoom.put("maxPlayers", r.getMaxPlayers());
					jsonRoom.put("difficulty", r.getDifficulty());
					arrayNodeRooms.addPOJO(jsonRoom);
				}
				msg.putPOJO("rooms", arrayNodeRooms);
				player.getSession().sendMessage(new TextMessage(msg.toString()));
				break;
			case "UPDATE MOVEMENT":
				if (player.getHealth() <= 0) {
					break;
				}
				if (player.getThruster() > 0) {
					player.loadMovement(node.path("movement").get("thrust").asBoolean(),
							node.path("movement").get("brake").asBoolean(),
							node.path("movement").get("rotLeft").asBoolean(),
							node.path("movement").get("rotRight").asBoolean());
					if (node.path("movement").get("thrust").asBoolean()) {
						player.setThruster(player.getThruster() - 1);
					}
				} else {
					player.loadMovement(false,
							node.path("movement").get("brake").asBoolean(),
							node.path("movement").get("rotLeft").asBoolean(),
							node.path("movement").get("rotRight").asBoolean());
				}
				if (node.path("bullet").asBoolean() && player.getAmmo() > 0) {
					player.setAmmo(player.getAmmo() - 1);
					room = (Room) session.getAttributes().get(ROOM_ATTRIBUTE);
					swg = rooms.get(room);
					
					Projectile projectile = new Projectile(player, this.projectileId.incrementAndGet());
					swg.addProjectile(projectile.getId(), projectile);
				}
				break;
			default:
				break;
			}

		} catch (Exception e) {
			System.err.println("Exception processing message " + message.getPayload());
			e.printStackTrace(System.err);
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
		Player player = (Player) session.getAttributes().get(PLAYER_ATTRIBUTE);
		if (session.getAttributes().get(ROOM_ATTRIBUTE) instanceof Room) {
			Room room = (Room) session.getAttributes().get(ROOM_ATTRIBUTE);
			SpacewarGame swg = rooms.get(room);
			swg.removePlayer(player);
			
			ObjectNode msg = mapper.createObjectNode();
			msg.put("event", "REMOVE PLAYER");
			msg.put("id", player.getPlayerId());
			swg.broadcast(msg.toString());
		}
	}
}
