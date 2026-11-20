package com.slowbag.pulse.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slowbag.pulse.MusicPlayerMod;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PulseServerNetworking {

    public static class ServerRoom {
        public final String name;
        public final String password;
        public UUID hostUuid;
        public String hostName;
        public final Set<UUID> members = ConcurrentHashMap.newKeySet();
        public final Set<UUID> invitedUuids = ConcurrentHashMap.newKeySet();
        public JsonObject lastTrackSync = null;

        public ServerRoom(String name, String password, UUID hostUuid, String hostName) {
            this.name = name;
            this.password = password != null ? password : "";
            this.hostUuid = hostUuid;
            this.hostName = hostName;
            this.members.add(hostUuid);
        }
    }

    private static final Map<String, ServerRoom> ROOMS = new ConcurrentHashMap<>();

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(PulseNetworkPayload.TYPE, (payload, context) -> {
            MinecraftServer server = context.server();
            ServerPlayer player = context.player();
            String json = payload.json();

            server.execute(() -> handleClientPacket(server, player, json));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                server.execute(() -> {
                    if (ServerPlayNetworking.canSend(player, PulseNetworkPayload.TYPE)) {
                        sendRoomListToPlayer(player);
                    }
                });
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                handlePlayerDisconnect(server, player.getUUID(), player.getName().getString());
            }
        });

        MusicPlayerMod.LOGGER.info("[Pulse Server] Native high-speed server rooms and audio sync registered.");
    }

    private static void handleClientPacket(MinecraftServer server, ServerPlayer player, String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String action = obj.has("action") ? obj.get("action").getAsString() : "";

            switch (action) {
                case "create_room" -> handleCreateRoom(server, player, obj);
                case "query_rooms" -> sendRoomListToPlayer(player);
                case "join_room" -> handleJoinRoom(server, player, obj);
                case "leave_room" -> handleLeaveRoom(server, player);
                case "disband_room" -> handleDisbandRoom(server, player);
                case "invite" -> handleInvite(server, player, obj);
                case "track_sync" -> handleTrackSync(server, player, obj);
                case "server_party" -> handleServerParty(server, player);
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[Music Player] Error processing packet from " + player.getName().getString() + ": " + t.getMessage());
        }
    }

    private static JsonArray getRoomMembersJson(MinecraftServer server, ServerRoom room) {
        JsonArray arr = new JsonArray();
        for (UUID uuid : room.members) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                String name = p.getName().getString();
                if (uuid.equals(room.hostUuid)) {
                    name += " (Host)";
                }
                arr.add(name);
            }
        }
        return arr;
    }

    private static void handleCreateRoom(MinecraftServer server, ServerPlayer player, JsonObject obj) {
        String name = obj.get("room").getAsString().trim();
        String pass = obj.has("password") ? obj.get("password").getAsString().trim() : "";

        // Remove from existing rooms
        removePlayerFromAllRooms(server, player.getUUID());

        ServerRoom room = new ServerRoom(name, pass, player.getUUID(), player.getName().getString());
        ROOMS.put(name.toLowerCase(), room);

        JsonObject resp = new JsonObject();
        resp.addProperty("action", "room_joined");
        resp.addProperty("room", name);
        resp.addProperty("isHost", true);
        resp.add("members", getRoomMembersJson(server, room));
        sendPacket(player, resp);

        broadcastRoomList(server);
    }

    private static void handleJoinRoom(MinecraftServer server, ServerPlayer player, JsonObject obj) {
        String name = obj.get("room").getAsString().trim();
        String pass = obj.has("password") ? obj.get("password").getAsString().trim() : "";

        ServerRoom room = ROOMS.get(name.toLowerCase());
        if (room == null) {
            sendError(player, "Room '" + name + "' does not exist!");
            return;
        }

        if (!room.password.isEmpty() && !room.password.equals(pass) && !room.invitedUuids.contains(player.getUUID())) {
            sendError(player, "Incorrect password for room '" + name + "'!");
            return;
        }

        removePlayerFromAllRooms(server, player.getUUID());
        room.members.add(player.getUUID());

        JsonObject resp = new JsonObject();
        resp.addProperty("action", "room_joined");
        resp.addProperty("room", room.name);
        resp.addProperty("host", room.hostName);
        resp.addProperty("isHost", room.hostUuid.equals(player.getUUID()));
        resp.add("members", getRoomMembersJson(server, room));
        sendPacket(player, resp);

        // Immediate audio synchronization
        if (room.lastTrackSync != null) {
            sendPacket(player, room.lastTrackSync);
        }

        // Notify all room members with updated unique roster
        JsonObject joinedNotice = new JsonObject();
        joinedNotice.addProperty("action", "members_updated");
        joinedNotice.addProperty("player", player.getName().getString());
        joinedNotice.addProperty("room", room.name);
        joinedNotice.add("members", getRoomMembersJson(server, room));
        broadcastToRoom(server, room, joinedNotice, null);

        broadcastRoomList(server);
    }

    private static void handleInvite(MinecraftServer server, ServerPlayer player, JsonObject obj) {
        String roomName = obj.has("room") ? obj.get("room").getAsString() : "";
        ServerRoom room = ROOMS.get(roomName.toLowerCase());

        if (room == null || !room.hostUuid.equals(player.getUUID())) {
            sendError(player, "Only the room host can invite players!");
            return;
        }

        String targetName = obj.has("target") ? obj.get("target").getAsString().trim() : "";
        ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);

        if (target == null) {
            sendError(player, "Player '" + targetName + "' is not online!");
            return;
        }

        room.invitedUuids.add(target.getUUID());

        JsonObject invitePkt = new JsonObject();
        invitePkt.addProperty("action", "invite_received");
        invitePkt.addProperty("room", room.name);
        invitePkt.addProperty("host", player.getName().getString());
        invitePkt.addProperty("password", room.password);
        sendPacket(target, invitePkt);

        JsonObject fb = new JsonObject();
        fb.addProperty("action", "feedback");
        fb.addProperty("message", "§a[Pulse] Sent private invitation to §f" + targetName + "§a!");
        sendPacket(player, fb);
    }

    private static void handleTrackSync(MinecraftServer server, ServerPlayer player, JsonObject obj) {
        String roomName = obj.has("room") ? obj.get("room").getAsString() : "";
        ServerRoom room = ROOMS.get(roomName.toLowerCase());

        if (room == null || !room.hostUuid.equals(player.getUUID())) {
            return; // Only host can broadcast audio
        }

        room.lastTrackSync = obj;
        broadcastToRoom(server, room, obj, player.getUUID());
    }

    private static void handleLeaveRoom(MinecraftServer server, ServerPlayer player) {
        removePlayerFromAllRooms(server, player.getUUID());
        JsonObject leftNotice = new JsonObject();
        leftNotice.addProperty("action", "feedback");
        leftNotice.addProperty("message", "§e[Pulse] You left the room.");
        sendPacket(player, leftNotice);
        broadcastRoomList(server);
    }

    private static void handleDisbandRoom(MinecraftServer server, ServerPlayer player) {
        for (var it = ROOMS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerRoom room = entry.getValue();
            if (room.hostUuid.equals(player.getUUID())) {
                JsonObject disbanded = new JsonObject();
                disbanded.addProperty("action", "room_disbanded");
                disbanded.addProperty("room", room.name);
                broadcastToRoom(server, room, disbanded, null);
                it.remove();
            }
        }
        broadcastRoomList(server);
    }

    private static void handleServerParty(MinecraftServer server, ServerPlayer player) {
        String srvRoomName = "Server Music Party";
        ServerRoom room = ROOMS.computeIfAbsent(srvRoomName.toLowerCase(), k ->
                new ServerRoom(srvRoomName, "", player.getUUID(), player.getName().getString())
        );

        removePlayerFromAllRooms(server, player.getUUID());
        room.members.add(player.getUUID());

        JsonObject resp = new JsonObject();
        resp.addProperty("action", "room_joined");
        resp.addProperty("room", room.name);
        resp.addProperty("isHost", room.hostUuid.equals(player.getUUID()));
        sendPacket(player, resp);

        if (room.lastTrackSync != null) {
            sendPacket(player, room.lastTrackSync);
        }

        broadcastRoomList(server);
    }

    private static void handlePlayerDisconnect(MinecraftServer server, UUID playerUuid, String playerName) {
        removePlayerFromAllRooms(server, playerUuid);
        broadcastRoomList(server);
    }

    private static void removePlayerFromAllRooms(MinecraftServer server, UUID playerUuid) {
        for (var it = ROOMS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerRoom room = entry.getValue();
            if (room.hostUuid.equals(playerUuid)) {
                // Host left: disband room
                JsonObject disbanded = new JsonObject();
                disbanded.addProperty("action", "room_disbanded");
                disbanded.addProperty("room", room.name);
                broadcastToRoom(server, room, disbanded, playerUuid);
                it.remove();
            } else {
                if (room.members.remove(playerUuid)) {
                    if (room.members.isEmpty()) {
                        it.remove();
                    } else {
                        JsonObject updated = new JsonObject();
                        updated.addProperty("action", "members_updated");
                        updated.addProperty("room", room.name);
                        updated.add("members", getRoomMembersJson(server, room));
                        broadcastToRoom(server, room, updated, null);
                    }
                }
            }
        }
    }

    public static void sendRoomListToPlayer(ServerPlayer player) {
        JsonObject listPkt = new JsonObject();
        listPkt.addProperty("action", "room_list");

        JsonArray arr = new JsonArray();
        for (ServerRoom r : ROOMS.values()) {
            JsonObject rObj = new JsonObject();
            rObj.addProperty("name", r.name);
            rObj.addProperty("host", r.hostName);
            rObj.addProperty("members", r.members.size());
            rObj.addProperty("isLocked", !r.password.isEmpty());
            arr.add(rObj);
        }
        listPkt.add("rooms", arr);
        sendPacket(player, listPkt);
    }

    public static void broadcastRoomList(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(p, PulseNetworkPayload.TYPE)) {
                sendRoomListToPlayer(p);
            }
        }
    }

    private static void broadcastToRoom(MinecraftServer server, ServerRoom room, JsonObject obj, UUID excludeUuid) {
        for (UUID memberUuid : room.members) {
            if (excludeUuid != null && excludeUuid.equals(memberUuid)) continue;
            ServerPlayer m = server.getPlayerList().getPlayer(memberUuid);
            if (m != null) {
                sendPacket(m, obj);
            }
        }
    }

    private static void sendPacket(ServerPlayer player, JsonObject obj) {
        if (ServerPlayNetworking.canSend(player, PulseNetworkPayload.TYPE)) {
            ServerPlayNetworking.send(player, new PulseNetworkPayload(obj.toString()));
        }
    }

    private static void sendError(ServerPlayer player, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("action", "error");
        err.addProperty("message", message);
        sendPacket(player, err);
    }
}
