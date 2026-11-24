package com.slowbag.pulse.client.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slowbag.pulse.MusicPlayerMod;
import com.slowbag.pulse.network.PulseMqttClient;
import com.slowbag.pulse.network.PulseNetworkPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundEvents;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PartyManager {

    static {
        try {
            if (System.getProperty("java.net.preferIPv6Addresses") == null) {
                System.setProperty("java.net.preferIPv6Addresses", "system");
            }
        } catch (Throwable ignored) {}
    }

    private static final PartyManager INSTANCE = new PartyManager();
    public static PartyManager getInstance() {
        return INSTANCE;
    }

    public static class RoomEntry {
        private final String name;
        private final String code;
        private final String host;
        private final boolean hasPassword;
        private final String password;
        private int memberCount;
        private volatile long lastSeen;

        public RoomEntry(String name, String host, boolean hasPassword, String password, int memberCount) {
            this(name, "", host, hasPassword, password, memberCount);
        }

        public RoomEntry(String name, String code, String host, boolean hasPassword, String password, int memberCount) {
            this.name = name;
            this.code = (code != null && !code.isEmpty()) ? code : sanitizeTopic(name);
            this.host = host;
            this.hasPassword = hasPassword;
            this.password = password != null ? password : "";
            this.memberCount = memberCount;
            this.lastSeen = System.currentTimeMillis();
        }

        public String getName() {
            return name;
        }

        public String getCode() {
            return code != null ? code : "";
        }

        public String getHost() {
            return host;
        }

        public boolean hasPassword() {
            return hasPassword;
        }

        public String getPassword() {
            return password;
        }

        public int getMemberCount() {
            return memberCount;
        }

        public void setMemberCount(int count) {
            this.memberCount = count;
        }

        public long getLastSeen() {
            return lastSeen;
        }

        public void touch() {
            this.lastSeen = System.currentTimeMillis();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RoomEntry r = (RoomEntry) o;
            return name.equalsIgnoreCase(r.name);
        }

        @Override
        public int hashCode() {
            return name.toLowerCase().hashCode();
        }
    }

    private String roomName = null;
    private static Set<String> createBoundedSet(int maxSize) {
        return Collections.synchronizedSet(Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>(maxSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > maxSize;
                }
            }
        ));
    }

    private String roomPassword = null;
    private volatile String roomCode = null;
    private boolean isHost = false;
    private volatile String currentHost = null;
    private final Set<String> members = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, Long> memberLastSeen = new ConcurrentHashMap<>();
    private final Set<String> invitedRooms = createBoundedSet(200);
    private final Set<String> processedInviteIds = createBoundedSet(500);
    private final Set<String> seenRoomEventIds = createBoundedSet(500);
    private final Set<String> seenDirEventIds = createBoundedSet(500);
    private final Map<String, Long> serverModUsers = new ConcurrentHashMap<>();
    private final Set<String> seenServerEventIds = createBoundedSet(500);
    private volatile String activeServerPartyHost = null;
    private volatile String activeServerPartyRoom = null;
    private volatile String currentServerId = null;
    private volatile boolean serverListening = false;
    private volatile long lastInviteSoundTime = 0;
    private volatile long lastServerPartySoundTime = 0;
    private final CopyOnWriteArrayList<RoomEntry> discoveredRooms = new CopyOnWriteArrayList<>();

    private final ExecutorService partyExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "MusicPlayer-PartyThread");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean roomListening = false;
    private volatile boolean dirListening = false;
    private volatile boolean inviteListening = false;

    private volatile long lastRoomEventTime = 0;
    private volatile long lastDirEventTime = 0;
    private volatile long lastInviteEventTime = 0;

    private PartyManager() {
        startDirectoryListener();
        startPersonalInviteListener();
        startServerDiscoveryListener();
        try {
            PulseMqttClient.getInstance().subscribe("pulse/rooms/global");
            PulseMqttClient.getInstance().addListener(this::handleMqttMessage);
        } catch (Throwable ignored) {}
    }

    private void handleMqttMessage(String topic, String payload) {
        if (topic == null || payload == null) return;
        try {
            if ("pulse/rooms/global".equals(topic)) {
                handleDirectoryPayload(payload);
            } else if (topic.startsWith("pulse/room/")) {
                handleRoomPayload(payload);
            } else if (topic.startsWith("pulse/user/")) {
                handlePersonalInvite(payload);
            }
        } catch (Throwable ignored) {}
    }

    public void init() {
        try {
            ClientPlayNetworking.registerGlobalReceiver(PulseNetworkPayload.TYPE, (payload, context) -> {
                String json = payload.json();
                context.client().execute(() -> handleServerPacket(json));
            });

            net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
                client.execute(() -> {
                    try {
                        if (isServerNetworkingActive()) {
                            JsonObject hello = new JsonObject();
                            hello.addProperty("action", "client_hello");
                            hello.addProperty("player", getPlayerName());
                            ClientPlayNetworking.send(new PulseNetworkPayload(hello.toString()));
                        }
                        refreshRooms();
                    } catch (Throwable ignored) {}
                });
            });

            MusicPlayerMod.LOGGER.info("[Music Player] Registered native client packet receiver for rooms & sync.");
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[Music Player] Server networking not available or client-only: " + t.getMessage());
        }
        refreshRooms();
    }

    public boolean isServerNetworkingActive() {
        try {
            return ClientPlayNetworking.canSend(PulseNetworkPayload.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    public void handleServerPacket(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String action = obj.has("action") ? obj.get("action").getAsString() : "";

            switch (action) {
                case "mod_users" -> {
                    if (obj.has("users")) {
                        JsonArray arr = obj.getAsJsonArray("users");
                        for (com.google.gson.JsonElement el : arr) {
                            String u = el.getAsString();
                            this.serverModUsers.put(u, System.currentTimeMillis());
                            MusicBadgeManager.getActiveModUsers().put(u.toLowerCase(), System.currentTimeMillis());
                        }
                    }
                }
                case "room_list" -> {
                    if (obj.has("rooms")) {
                        JsonArray arr = obj.getAsJsonArray("rooms");
                        List<RoomEntry> list = new ArrayList<>();
                        for (JsonElement el : arr) {
                            JsonObject r = el.getAsJsonObject();
                            String name = r.get("name").getAsString();
                            String host = r.get("host").getAsString();
                            int members = r.has("members") ? r.get("members").getAsInt() : 1;
                            boolean locked = r.has("isLocked") && r.get("isLocked").getAsBoolean();
                            list.add(new RoomEntry(name, host, locked, "", members));
                        }
                        this.discoveredRooms.clear();
                        this.discoveredRooms.addAll(list);
                    }
                }
                case "room_joined" -> {
                    this.roomName = obj.get("room").getAsString();
                    this.isHost = obj.has("isHost") && obj.get("isHost").getAsBoolean();
                    String myName = getPlayerName();
                    this.currentHost = obj.has("host") ? obj.get("host").getAsString() : (isHost ? myName : "Host");
                    if (obj.has("members")) {
                        this.members.clear();
                        for (com.google.gson.JsonElement el : obj.getAsJsonArray("members")) {
                            String mClean = el.getAsString().replaceAll("\\s*\\(Host\\)", "").trim();
                            if (!mClean.isEmpty()) this.members.add(mClean);
                        }
                    }
                    sendPlayerMessage("§a[Pulse] Joined room '§f" + roomName + "§a'!");
                }
                case "members_updated" -> {
                    if (obj.has("members")) {
                        this.members.clear();
                        for (com.google.gson.JsonElement el : obj.getAsJsonArray("members")) {
                            String mClean = el.getAsString().replaceAll("\\s*\\(Host\\)", "").trim();
                            if (!mClean.isEmpty()) this.members.add(mClean);
                        }
                    }
                }
                case "invite_received" -> {
                    String sender = obj.get("host").getAsString();
                    String room = obj.get("room").getAsString();
                    String pass = obj.has("password") ? obj.get("password").getAsString() : "";
                    displayInvite(sender, room, pass);
                }
                case "track_sync" -> {
                    if (!isHost) {
                        handleTrackSyncPacket(obj);
                    }
                }
                case "pause_sync" -> {
                    if (!isHost && obj.has("paused")) {
                        boolean paused = obj.get("paused").getAsBoolean();
                        AudioManager.getInstance().togglePauseRemote(paused);
                    }
                }
                case "seek_sync" -> {
                    if (!isHost && obj.has("ratio")) {
                        double ratio = obj.get("ratio").getAsDouble();
                        AudioManager.getInstance().seekToRemote(ratio);
                    }
                }
                case "queue_add" -> {
                    if (isHost) {
                        String title = obj.has("title") ? obj.get("title").getAsString() : "Track";
                        String artist = obj.has("artist") ? obj.get("artist").getAsString() : "Unknown";
                        String url = obj.has("url") ? obj.get("url").getAsString() : "";
                        long dur = obj.has("duration") ? obj.get("duration").getAsLong() : -1;
                        String art = obj.has("artwork") ? obj.get("artwork").getAsString() : null;
                        String sender = obj.has("sender") ? obj.get("sender").getAsString() : "Member";
                        if (!url.isEmpty()) {
                            AudioTrack qt = new AudioTrack(title, artist, url, false, dur, "Room", art);
                            AudioManager.getInstance().enqueueTrackDirect(qt);
                            sendPlayerMessage("§a[Pulse] 📥 " + sender + " added '§f" + title + "§a' to the Room Queue!");
                            publishHostRoomState();
                        }
                    }
                }
                case "queue_remove" -> {
                    if (isHost) {
                        int idx = obj.has("index") ? obj.get("index").getAsInt() : -1;
                        if (idx >= 0) {
                            AudioManager.getInstance().removeFromQueueDirect(idx);
                            publishHostRoomState();
                        }
                    }
                }
                case "queue_move" -> {
                    if (isHost) {
                        int from = obj.has("fromIndex") ? obj.get("fromIndex").getAsInt() : -1;
                        int to = obj.has("toIndex") ? obj.get("toIndex").getAsInt() : -1;
                        if (from >= 0 && to >= 0) {
                            AudioManager.getInstance().moveQueueItemDirect(from, to);
                            publishHostRoomState();
                        }
                    }
                }
                case "room_disbanded" -> {
                    this.isHost = false;
                    this.roomName = null;
                    this.currentHost = null;
                    sendPlayerMessage("§c[Pulse] The room was disbanded by the host.");
                }
                case "member_joined" -> {
                    String player = obj.get("player").getAsString();
                    String pClean = player.replaceAll("\\s*\\(Host\\)", "").trim();
                    if (!pClean.isEmpty()) this.members.add(pClean);
                    sendPlayerMessage("§a[Pulse] §f" + player + " §ajoined the room!");
                }
                case "feedback" -> {
                    String msg = obj.get("message").getAsString();
                    sendPlayerMessage(msg);
                }
                case "error" -> {
                    String msg = obj.get("message").getAsString();
                    sendPlayerMessage("§c[Pulse] " + msg);
                }
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[Music Player] Error parsing server packet: " + t.getMessage());
        }
    }

    private void handleTrackSyncPacket(JsonObject obj) {
        try {
            String title = obj.get("title").getAsString();
            String artist = obj.get("artist").getAsString();
            String url = obj.get("url").getAsString();
            String category = obj.has("category") ? obj.get("category").getAsString() : "Music";
            long duration = obj.has("duration") ? obj.get("duration").getAsLong() : -1;
            long elapsed = obj.has("elapsed") ? obj.get("elapsed").getAsLong() : 0;
            String artwork = obj.has("artwork") ? obj.get("artwork").getAsString() : null;
            boolean isPaused = obj.has("isPaused") && obj.get("isPaused").getAsBoolean();

            AudioTrack syncTrack = new AudioTrack(title, artist, url, false, duration, category, artwork);
            AudioManager am = AudioManager.getInstance();
            AudioTrack current = am.getCurrentTrack();

            if (current == null || !current.getUrl().equals(url)) {
                am.playTrackInternal(syncTrack, false, elapsed);
            } else {
                long myElapsed = am.getElapsedSeconds();
                if (Math.abs(myElapsed - elapsed) > 3) {
                    am.playTrackInternal(syncTrack, false, elapsed);
                }
            }

            if (isPaused && !am.isPaused()) {
                am.togglePause();
            } else if (!isPaused && am.isPaused()) {
                am.togglePause();
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[Music Player] Error handling sync: " + t.getMessage());
        }
    }

    public static String sanitizeTopic(String name) {
        if (name == null) return "lobby";
        String clean = name.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        while (clean.contains("__")) clean = clean.replace("__", "_");
        return clean.isEmpty() ? "lobby" : clean;
    }

    public String getServerIdentifier() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                return sanitizeTopic(mc.getCurrentServer().ip);
            }
            if (mc.hasSingleplayerServer()) {
                return "local_world";
            }
        } catch (Throwable ignored) {}
        return "global_lobby";
    }

    public String getServerDisplayName() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getCurrentServer() != null) {
                String name = mc.getCurrentServer().name;
                return (name != null && !name.isBlank()) ? name : mc.getCurrentServer().ip;
            }
            if (mc.hasSingleplayerServer()) return "Local World";
        } catch (Throwable ignored) {}
        return "Multiplayer";
    }

    public List<String> getServerModUsers() {
        long now = System.currentTimeMillis();
        serverModUsers.entrySet().removeIf(e -> (now - e.getValue() > 30000));
        return new ArrayList<>(serverModUsers.keySet());
    }

    public String getActiveServerPartyHost() {
        return activeServerPartyHost;
    }

    public String getActiveServerPartyRoom() {
        return activeServerPartyRoom;
    }

    public synchronized void joinServerParty() {
        String srvName = getServerDisplayName();
        String targetRoom = (activeServerPartyRoom != null && !activeServerPartyRoom.isBlank())
                ? activeServerPartyRoom
                : "Server Party (" + srvName + ")";

        boolean ok = joinRoom(targetRoom, "");
        if (!ok || !isInRoom()) {
            createRoom(targetRoom, "");
        }
        inviteAllServerModUsers();
    }

    public void inviteAllServerModUsers() {
        List<String> users = getServerModUsers();
        String me = getPlayerName();
        int count = 0;
        for (String u : users) {
            if (!u.equalsIgnoreCase(me)) {
                invitePlayer(u);
                count++;
            }
        }
        if (count > 0) {
            sendPlayerMessage("§a[Pulse] 💌 Sent private party invite to " + count + " player(s) with the mod on this server!");
        }
    }

    public String getPlayerName() {
        try {
            if (Minecraft.getInstance().player != null) {
                return Minecraft.getInstance().player.getName().getString();
            }
        } catch (Throwable ignored) {}
        return "Player";
    }

    public String getRoomCode() {
        return roomCode != null ? roomCode : "";
    }



    public void publishHostRoomState() {
        if (!isHost || roomCode == null) return;
        AudioManager am = AudioManager.getInstance();
        AudioTrack t = am.getCurrentTrack();

        JsonObject hb = new JsonObject();
        hb.addProperty("type", "host_heartbeat");
        hb.addProperty("action", "track_sync");
        hb.addProperty("code", roomCode);
        hb.addProperty("room", roomName != null ? roomName : "Pulse Room");
        hb.addProperty("host", currentHost != null ? currentHost : getPlayerName());
        hb.addProperty("hasPassword", roomPassword != null && !roomPassword.isEmpty());
        hb.addProperty("password", roomPassword != null ? roomPassword : "");
        hb.addProperty("isPaused", am.isPaused() || !am.isPlaying());
        hb.addProperty("elapsed", am.isBuffering() ? 0 : am.getElapsedSeconds());
        hb.addProperty("lastHeartbeat", System.currentTimeMillis());

        if (t != null) {
            JsonObject tObj = new JsonObject();
            tObj.addProperty("title", t.getTitle());
            tObj.addProperty("artist", t.getArtist());
            tObj.addProperty("url", t.getUrl());
            tObj.addProperty("category", t.getCategory());
            tObj.addProperty("duration", t.getDurationSeconds());
            tObj.addProperty("elapsed", am.getElapsedSeconds());
            tObj.addProperty("isPaused", am.isPaused() || !am.isPlaying());
            if (t.getArtworkUrl() != null) tObj.addProperty("artwork", t.getArtworkUrl());
            if (t.getUrl() != null && t.getUrl().startsWith("file:/")) {
                tObj.addProperty("isLocal", true);
                String lStream = PulseLocalAudioServer.getInstance().getLocalStreamUrl();
                if (lStream != null) tObj.addProperty("localStreamUrl", lStream);
            }
            hb.add("track", tObj);
        }

        JsonArray memArr = new JsonArray();
        synchronized (this) {
            for (String m : members) {
                memArr.add(m);
            }
        }
        hb.add("members", memArr);

        // Serialize current upcoming queue to room
        JsonArray qArr = new JsonArray();
        for (AudioTrack qt : am.getUpcomingQueue()) {
            JsonObject qObj = new JsonObject();
            qObj.addProperty("title", qt.getTitle());
            qObj.addProperty("artist", qt.getArtist());
            qObj.addProperty("url", qt.getUrl());
            qObj.addProperty("duration", qt.getDurationSeconds());
            if (qt.getArtworkUrl() != null) qObj.addProperty("artworkUrl", qt.getArtworkUrl());
            qArr.add(qObj);
        }
        hb.add("queue", qArr);

        sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), hb);
        if (roomName != null && !roomName.isEmpty()) {
            sendNtfyMessage("pulse_room_" + sanitizeTopic(roomName), hb);
        }
        broadcastRoomPresence();
    }

    public void addTrackToRoomQueue(AudioTrack track) {
        if (!isInRoom() || track == null) return;
        if (isHost) {
            AudioManager.getInstance().enqueueTrackDirect(track);
            publishHostRoomState();
            return;
        }

        JsonObject qObj = new JsonObject();
        qObj.addProperty("type", "queue_add");
        qObj.addProperty("action", "queue_add");
        qObj.addProperty("code", roomCode != null ? roomCode : "");
        qObj.addProperty("room", roomName != null ? roomName : "");
        qObj.addProperty("title", track.getTitle());
        qObj.addProperty("artist", track.getArtist());
        qObj.addProperty("url", track.getUrl());
        qObj.addProperty("duration", track.getDurationSeconds());
        if (track.getArtworkUrl() != null) qObj.addProperty("artworkUrl", track.getArtworkUrl());
        qObj.addProperty("sender", getPlayerName());

        if (isServerNetworkingActive()) {
            ClientPlayNetworking.send(new PulseNetworkPayload(qObj.toString()));
        }
        if (roomCode != null && !roomCode.isEmpty()) {
            sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), qObj);
        }

        sendPlayerMessage("§a[Pulse] 🎵 Added '" + track.getTitle() + "' to the Room Queue!");
    }

    public void removeTrackFromRoomQueue(int index) {
        if (!isInRoom() || index < 0) return;
        if (isHost) {
            AudioManager.getInstance().removeFromQueueDirect(index);
            publishHostRoomState();
            return;
        }

        JsonObject qObj = new JsonObject();
        qObj.addProperty("type", "queue_remove");
        qObj.addProperty("action", "queue_remove");
        qObj.addProperty("code", roomCode != null ? roomCode : "");
        qObj.addProperty("room", roomName != null ? roomName : "");
        qObj.addProperty("index", index);
        qObj.addProperty("sender", getPlayerName());

        if (isServerNetworkingActive()) {
            ClientPlayNetworking.send(new PulseNetworkPayload(qObj.toString()));
        }
        if (roomCode != null && !roomCode.isEmpty()) {
            sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), qObj);
        }

        AudioManager.getInstance().removeFromQueueDirect(index);
    }

    public boolean moveTrackInRoomQueue(int fromIndex, int toIndex) {
        if (!isInRoom() || fromIndex < 0 || toIndex < 0) return false;
        if (isHost) {
            boolean ok = AudioManager.getInstance().moveQueueItemDirect(fromIndex, toIndex);
            if (ok) publishHostRoomState();
            return ok;
        }

        JsonObject qObj = new JsonObject();
        qObj.addProperty("type", "queue_move");
        qObj.addProperty("action", "queue_move");
        qObj.addProperty("code", roomCode != null ? roomCode : "");
        qObj.addProperty("room", roomName != null ? roomName : "");
        qObj.addProperty("fromIndex", fromIndex);
        qObj.addProperty("toIndex", toIndex);
        qObj.addProperty("sender", getPlayerName());

        if (isServerNetworkingActive()) {
            ClientPlayNetworking.send(new PulseNetworkPayload(qObj.toString()));
        }
        if (roomCode != null && !roomCode.isEmpty()) {
            sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), qObj);
        }

        return AudioManager.getInstance().moveQueueItemDirect(fromIndex, toIndex);
    }

    // ------------------------------------------------------------------------
    // Room Lifecycle Management
    // ------------------------------------------------------------------------

    public synchronized void createRoom(String name, String password) {
        leaveRoomInternal(false);

        String myName = getPlayerName();
        if (name == null || name.trim().isEmpty()) {
            name = myName + "'s Room";
        }
        name = name.trim();
        String pass = (password != null) ? password.trim() : "";

        // Generate clean 5-digit room code
        this.roomCode = String.format("%05d", new java.util.Random().nextInt(90000) + 10000);
        this.roomName = name;
        this.roomPassword = pass;
        this.isHost = true;
        this.currentHost = myName;
        this.members.clear();
        this.members.add(myName);
        this.memberLastSeen.clear();
        this.memberLastSeen.put(myName.toLowerCase(), System.currentTimeMillis());
        this.roomListening = true;

        startRoomListener(this.roomCode);
        startHostHeartbeat();

        try {
            PulseMqttClient.getInstance().subscribe("pulse/room/" + this.roomCode.toLowerCase());
            PulseMqttClient.getInstance().subscribe("pulse/room/" + sanitizeTopic(this.roomName));
        } catch (Throwable ignored) {}

        if (isServerNetworkingActive()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("action", "create_room");
            obj.addProperty("room", name);
            obj.addProperty("code", this.roomCode);
            obj.addProperty("password", pass);
            ClientPlayNetworking.send(new PulseNetworkPayload(obj.toString()));
        }

        // Register in local IPC directory so alts on same PC instantly see room
        LocalPresenceManager.registerRoom(name, myName, !pass.isEmpty(), pass, 1);

        publishHostRoomState();

        AudioTrack cur = AudioManager.getInstance().getCurrentTrack();
        if (cur != null) {
            broadcastTrackIfHost(cur);
            broadcastPauseIfHost(AudioManager.getInstance().isPaused());
        }

        String passNotice = pass.isEmpty() ? "" : " §7[Password: §f" + pass + "§7]";
        sendPlayerMessage("§a[Pulse] Created room '§f" + this.roomName + "§a'! Room Code: §e§l" + this.roomCode + passNotice + " §7(Share this code with friends to listen together)");
    }

    public synchronized boolean joinRoom(String codeOrName, String password) {
        if (codeOrName == null || codeOrName.trim().isEmpty()) return false;
        String trimmed = codeOrName.trim();
        String pass = (password != null) ? password.trim() : "";

        leaveRoomInternal(false);
        String myName = getPlayerName();

        RoomEntry match = null;
        for (RoomEntry re : discoveredRooms) {
            if (re.getCode().equalsIgnoreCase(trimmed) || re.getName().equalsIgnoreCase(trimmed)) {
                match = re;
                break;
            }
        }

        String targetCode = (match != null && !match.getCode().isEmpty()) ? match.getCode() : trimmed;
        String targetName = (match != null) ? match.getName() : "Room #" + trimmed;
        String topicSuffix = sanitizeTopic(targetCode);

        this.roomCode = targetCode;
        this.roomName = targetName;
        this.roomPassword = pass;
        this.isHost = false;
        this.currentHost = (match != null) ? match.getHost() : "Host";
        this.members.clear();
        this.members.add(myName);
        this.roomListening = true;

        // 1. Server Networking if connected to a Fabric server
        if (isServerNetworkingActive()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("action", "join_room");
            obj.addProperty("room", trimmed);
            obj.addProperty("code", trimmed);
            obj.addProperty("password", pass);
            ClientPlayNetworking.send(new PulseNetworkPayload(obj.toString()));
        }

        // 2. Start P2P listeners and heartbeats
        startRoomListener(topicSuffix);
        startMemberHeartbeat();

        try {
            PulseMqttClient.getInstance().subscribe("pulse/room/" + topicSuffix.toLowerCase());
        } catch (Throwable ignored) {}

        // 3. Send join handshake to the room
        JsonObject joinPkt = new JsonObject();
        joinPkt.addProperty("type", "join");
        joinPkt.addProperty("action", "join");
        joinPkt.addProperty("sender", myName);
        joinPkt.addProperty("code", trimmed);
        joinPkt.addProperty("password", pass);
        sendNtfyMessage("pulse_room_" + topicSuffix, joinPkt);

        // 4. Request initial sync
        requestSync();

        // 5. Query latest heartbeat asynchronously to sync track immediately
        partyExecutor.submit(() -> {
            try {
                URL pollUrl = new URI("https://ntfy.sh/pulse_room_" + topicSuffix + "/json?poll=1&since=60s").toURL();
                HttpURLConnection conn = (HttpURLConnection) pollUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "PulseMusicPlayer/1.5.9");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() == 200) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.isBlank()) continue;
                            try {
                                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                                if (event.has("message")) {
                                    handleRoomPayload(event.get("message").getAsString());
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        });

        sendPlayerMessage("§a[Pulse] ⚡ Connected to room §e#" + trimmed + "§a! Listening together ~ 🎵");
        return true;
    }

    public synchronized boolean joinRoomByCode(String code) {
        return joinRoom(code, "");
    }

    public synchronized void leaveRoom() {
        leaveRoomInternal(true);
    }

    public synchronized void disbandRoom() {
        if (!isInRoom()) return;
        if (!isHost) {
            sendPlayerMessage("§c[Pulse] Only the room host can disband this room.");
            return;
        }
        String curName = this.roomName;
        String curCode = this.roomCode;
        sendPlayerMessage("§c[Pulse] Room '" + curName + "' has been disbanded.");

        // 1. Immediately prune from local presence manager and local discovered list
        if (curName != null) {
            LocalPresenceManager.removeRoom(curName);
            discoveredRooms.removeIf(r -> (curName != null && r.getName().equalsIgnoreCase(curName)) ||
                    (curCode != null && !curCode.isEmpty() && r.getCode().equalsIgnoreCase(curCode)));
        }

        // 2. Broadcast room_close to global directory so ALL online clients instantly remove it
        JsonObject closeMsg = new JsonObject();
        closeMsg.addProperty("type", "room_close");
        closeMsg.addProperty("name", curName != null ? curName : "");
        closeMsg.addProperty("code", curCode != null ? curCode : "");
        sendNtfyMessage("pulse_rooms_global", closeMsg);

        leaveRoomInternal(false);
    }

    private synchronized void leaveRoomInternal(boolean notifyUser) {
        this.roomListening = false;
        String curCode = this.roomCode;
        String curName = this.roomName;
        boolean wasHost = this.isHost;

        if (curCode != null && !curCode.isEmpty()) {
            JsonObject leaveMsg = new JsonObject();
            leaveMsg.addProperty("type", wasHost ? "disband" : "leave");
            leaveMsg.addProperty("sender", getPlayerName());
            leaveMsg.addProperty("code", curCode);
            sendNtfyMessage("pulse_room_" + curCode.toLowerCase(), leaveMsg);

            try {
                PulseMqttClient.getInstance().unsubscribe("pulse/room/" + curCode.toLowerCase());
                if (curName != null) {
                    PulseMqttClient.getInstance().unsubscribe("pulse/room/" + sanitizeTopic(curName));
                }
            } catch (Throwable ignored) {}
        }

        if (isServerNetworkingActive() && curName != null) {
            JsonObject obj = new JsonObject();
            obj.addProperty("action", wasHost ? "disband_room" : "leave_room");
            obj.addProperty("room", curName);
            ClientPlayNetworking.send(new PulseNetworkPayload(obj.toString()));
        }

        if (curName != null) {
            LocalPresenceManager.removeRoom(curName);
        }

        this.roomName = null;
        this.roomCode = null;
        this.roomPassword = null;
        this.isHost = false;
        this.currentHost = null;
        this.members.clear();
        this.memberLastSeen.clear();

        if (notifyUser) {
            sendPlayerMessage("§7[Pulse] Left room.");
        }
    }

    // ------------------------------------------------------------------------
    // Inviting Players (/musicplayer room invite <player>)
    // ------------------------------------------------------------------------

    public void invitePlayer(String targetPlayer) {
        if (!isInRoom()) {
            sendPlayerMessage("§c[Pulse] You are not in a room right now.");
            return;
        }

        if (!isHost) {
            sendPlayerMessage("§c[Pulse] Only the room host can invite players!");
            return;
        }

        final String target = targetPlayer.trim();
        final String room = this.roomName;
        final String pass = (this.roomPassword != null) ? this.roomPassword : "";
        final String myName = getPlayerName();

        if (isServerNetworkingActive()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("action", "invite");
            obj.addProperty("room", room);
            obj.addProperty("target", target);
            ClientPlayNetworking.send(new PulseNetworkPayload(obj.toString()));
            return;
        }

        // Direct private invite delivered secretly to the player's client (never sent to global chat)
        JsonObject invite = new JsonObject();
        invite.addProperty("type", "invite");
        invite.addProperty("sender", myName);
        invite.addProperty("room", room);
        invite.addProperty("hasPassword", !pass.isEmpty());
        invite.addProperty("password", pass);

        sendNtfyMessage("pulse_invite_" + target.toLowerCase(), invite);

        sendPlayerMessage("§a[Pulse] Sent private room invite to §f" + target + "§a!");
    }

    // ------------------------------------------------------------------------
    // Broadcasts (Host -> Room)
    // ------------------------------------------------------------------------

    public void broadcastTrackIfHost(AudioTrack track) {
        broadcastTrackIfHost(track, 0);
    }

    public void broadcastTrackIfHost(AudioTrack track, long seekSeconds) {
        if (!isInRoom() || !isHost || track == null) return;
        publishHostRoomState();

        JsonObject msg = new JsonObject();
        msg.addProperty("action", "track_sync");
        msg.addProperty("type", "track_sync");
        msg.addProperty("sender", getPlayerName());
        msg.addProperty("room", roomName);
        msg.addProperty("code", roomCode != null ? roomCode : "");
        msg.addProperty("title", track.getTitle());
        msg.addProperty("artist", track.getArtist());
        msg.addProperty("url", track.getUrl());
        msg.addProperty("category", track.getCategory());
        msg.addProperty("duration", track.getDurationSeconds());
        msg.addProperty("elapsed", seekSeconds);
        msg.addProperty("isPaused", AudioManager.getInstance().isPaused());
        if (track.getArtworkUrl() != null) {
            msg.addProperty("artwork", track.getArtworkUrl());
        }
        if (track.getUrl() != null && track.getUrl().startsWith("file:/")) {
            msg.addProperty("isLocal", true);
            String lStream = PulseLocalAudioServer.getInstance().getLocalStreamUrl();
            if (lStream != null) msg.addProperty("localStreamUrl", lStream);
        }

        if (isServerNetworkingActive()) {
            ClientPlayNetworking.send(new PulseNetworkPayload(msg.toString()));
        }
        if (roomCode != null && !roomCode.isEmpty()) {
            sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), msg);
        }
        if (roomName != null && !roomName.isEmpty()) {
            sendNtfyMessage("pulse_room_" + sanitizeTopic(roomName), msg);
        }
    }

    public void broadcastPauseIfHost(boolean isPaused) {
        if (!isInRoom() || !isHost) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("action", "pause_sync");
        msg.addProperty("type", "pause_sync");
        msg.addProperty("sender", getPlayerName());
        msg.addProperty("room", roomName);
        msg.addProperty("code", roomCode != null ? roomCode : "");
        msg.addProperty("paused", isPaused);

        if (isServerNetworkingActive()) {
            ClientPlayNetworking.send(new PulseNetworkPayload(msg.toString()));
        }
        if (roomCode != null && !roomCode.isEmpty()) {
            sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), msg);
        }
        if (roomName != null && !roomName.isEmpty()) {
            sendNtfyMessage("pulse_room_" + sanitizeTopic(roomName), msg);
        }
    }

    public void broadcastSeekIfHost(double ratio) {
        if (!isInRoom() || !isHost) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("action", "seek_sync");
        msg.addProperty("type", "seek_sync");
        msg.addProperty("sender", getPlayerName());
        msg.addProperty("room", roomName);
        msg.addProperty("code", roomCode != null ? roomCode : "");
        msg.addProperty("ratio", ratio);

        if (isServerNetworkingActive()) {
            ClientPlayNetworking.send(new PulseNetworkPayload(msg.toString()));
        }
        if (roomCode != null && !roomCode.isEmpty()) {
            sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), msg);
        }
        if (roomName != null && !roomName.isEmpty()) {
            sendNtfyMessage("pulse_room_" + sanitizeTopic(roomName), msg);
        }
    }

    public void requestSync() {
        if (!isInRoom()) return;
        if (isServerNetworkingActive()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("action", "sync_request");
            ClientPlayNetworking.send(new PulseNetworkPayload(obj.toString()));
        }
        JsonObject req = new JsonObject();
        req.addProperty("type", "request_sync");
        req.addProperty("sender", getPlayerName());
        if (roomCode != null && !roomCode.isEmpty()) {
            sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), req);
        }
        if (roomName != null && !roomName.isEmpty()) {
            sendNtfyMessage("pulse_room_" + sanitizeTopic(roomName), req);
        }
    }

    public void reSync() {
        requestSync();
    }

    private void broadcastRoomPresence() {
        if (!isInRoom() || !isHost) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "room_presence");
        msg.addProperty("name", roomName);
        msg.addProperty("code", roomCode != null ? roomCode : "");
        msg.addProperty("host", getPlayerName());
        msg.addProperty("hasPassword", roomPassword != null && !roomPassword.isEmpty());
        msg.addProperty("password", roomPassword != null ? roomPassword : "");
        msg.addProperty("members", members.size());
        msg.addProperty("time", System.currentTimeMillis() / 1000);
        sendNtfyMessage("pulse_rooms_global", msg);
    }

    private void startHostHeartbeat() {
        partyExecutor.submit(() -> {
            while (roomListening && isInRoom() && isHost) {
                try {
                    Thread.sleep(3500);
                    if (!roomListening || !isInRoom() || !isHost) break;

                    String myName = getPlayerName();
                    long now = System.currentTimeMillis();

                    // Prune inactive members who haven't pinged in 25 seconds
                    synchronized (PartyManager.this) {
                        members.removeIf(m -> {
                            if (m.equalsIgnoreCase(myName) || m.equalsIgnoreCase(currentHost)) return false;
                            Long lastSeen = memberLastSeen.get(m.toLowerCase());
                            return (lastSeen == null || (now - lastSeen > 25000));
                        });
                    }

                    publishHostRoomState();
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        });
    }

    private void startMemberHeartbeat() {
        partyExecutor.submit(() -> {
            while (roomListening && isInRoom() && !isHost) {
                try {
                    Thread.sleep(4000);
                    if (!roomListening || !isInRoom() || isHost) break;

                    JsonObject ping = new JsonObject();
                    ping.addProperty("type", "member_ping");
                    ping.addProperty("sender", getPlayerName());
                    ping.addProperty("code", roomCode != null ? roomCode : "");
                    if (roomCode != null && !roomCode.isEmpty()) {
                        sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), ping);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        });
    }

    // ------------------------------------------------------------------------
    // Room Listener Loop (Active Room Messages)
    // ------------------------------------------------------------------------

    private void startRoomListener(String topicSuffix) {
        this.roomListening = true;
        this.seenRoomEventIds.clear();

        partyExecutor.submit(() -> {
            String topic = "pulse_room_" + topicSuffix;
            while (roomListening && isInRoom()) {
                try {
                    URL url = new URI("https://ntfy.sh/" + topic + "/json?since=30s").toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "PulseMusicPlayer/2.6");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(55000);

                    if (conn.getResponseCode() == 200) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while (roomListening && isInRoom() && (line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                try {
                                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                                    String id = event.has("id") ? event.get("id").getAsString() : null;
                                    if (id != null && !seenRoomEventIds.add(id)) {
                                        continue;
                                    }
                                    if (event.has("message")) {
                                        handleRoomPayload(event.get("message").getAsString());
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored2) {
                        break;
                    }
                }
            }
        });
    }

    public synchronized void broadcastMembersSync() {
        if (!isInRoom() || !isHost) return;
        JsonObject sync = new JsonObject();
        sync.addProperty("type", "members_sync");
        sync.addProperty("host", currentHost != null ? currentHost : getPlayerName());
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String m : members) {
            arr.add(m);
        }
        sync.add("members", arr);
        if (roomCode != null && !roomCode.isEmpty()) {
            sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), sync);
        }
        if (roomName != null && !roomName.isEmpty()) {
            sendNtfyMessage("pulse_room_" + sanitizeTopic(roomName), sync);
        }
    }

    private boolean isUrlReachable(String urlStr, int timeoutMs) {
        if (urlStr == null || !urlStr.startsWith("http")) return false;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URI(urlStr).toURL().openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            int code = c.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Throwable t) {
            return false;
        }
    }

    private void handleRoomPayload(String rawJson) {
        if (!isInRoom()) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(rawJson).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            String sender = obj.has("sender") ? obj.get("sender").getAsString() : "Player";
            String myName = getPlayerName();

            if ("disband".equals(type)) {
                sendPlayerMessage("§c[Pulse] The room was disbanded because the host left.");
                leaveRoomInternal(false);
                return;
            }

            if ("password_error".equals(type)) {
                String target = obj.has("target") ? obj.get("target").getAsString() : "";
                if (target.equalsIgnoreCase(myName)) {
                    sendPlayerMessage("§c[Pulse] ✖ Incorrect password for room #" + (roomCode != null ? roomCode : "") + "!");
                    leaveRoomInternal(false);
                    return;
                }
            }

            if ("queue_add".equals(type) && isHost) {
                String title = obj.has("title") ? obj.get("title").getAsString() : "Track";
                String artist = obj.has("artist") ? obj.get("artist").getAsString() : "Unknown";
                String url = obj.has("url") ? obj.get("url").getAsString() : "";
                long dur = obj.has("duration") ? obj.get("duration").getAsLong() : -1;
                String art = obj.has("artworkUrl") ? obj.get("artworkUrl").getAsString() : (obj.has("artwork") ? obj.get("artwork").getAsString() : null);
                String senderName = obj.has("sender") ? obj.get("sender").getAsString() : "Guest";
                if (!url.isEmpty()) {
                    AudioTrack qt = new AudioTrack(title, artist, url, false, dur, "Room", art);
                    AudioManager.getInstance().enqueueTrackDirect(qt);
                    sendPlayerMessage("§d[Pulse] ➕ §f" + senderName + " §aadded '§f" + title + "§a' to the Room Queue! 🎶");
                    publishHostRoomState();
                }
                return;
            }

            if ("queue_remove".equals(type) && isHost) {
                int idx = obj.has("index") ? obj.get("index").getAsInt() : -1;
                if (idx >= 0) {
                    AudioManager.getInstance().removeFromQueueDirect(idx);
                    publishHostRoomState();
                }
                return;
            }

            if ("queue_move".equals(type) && isHost) {
                int from = obj.has("fromIndex") ? obj.get("fromIndex").getAsInt() : -1;
                int to = obj.has("toIndex") ? obj.get("toIndex").getAsInt() : -1;
                if (from >= 0 && to >= 0) {
                    AudioManager.getInstance().moveQueueItemDirect(from, to);
                    publishHostRoomState();
                }
                return;
            }

            if ("member_ping".equals(type) || "join".equals(type)) {
                if (!sender.equalsIgnoreCase(myName)) {
                    if (isHost && "join".equals(type)) {
                        if (this.roomPassword != null && !this.roomPassword.isEmpty()) {
                            String provided = obj.has("password") ? obj.get("password").getAsString().trim() : "";
                            if (!this.roomPassword.equals(provided)) {
                                JsonObject err = new JsonObject();
                                err.addProperty("type", "password_error");
                                err.addProperty("target", sender);
                                err.addProperty("code", roomCode != null ? roomCode : "");
                                if (roomCode != null && !roomCode.isEmpty()) {
                                    sendNtfyMessage("pulse_room_" + roomCode.toLowerCase(), err);
                                }
                                if (roomName != null && !roomName.isEmpty()) {
                                    sendNtfyMessage("pulse_room_" + sanitizeTopic(roomName), err);
                                }
                                return;
                            }
                        }
                    }

                    boolean newlyAdded = false;
                    synchronized (this) {
                        memberLastSeen.put(sender.toLowerCase(), System.currentTimeMillis());
                        boolean exists = false;
                        for (String m : members) {
                            if (m.equalsIgnoreCase(sender)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            members.add(sender);
                            newlyAdded = true;
                        }
                    }

                    if (isHost) {
                        if (newlyAdded || "join".equals(type)) {
                            sendPlayerMessage("§a[Pulse] 👥 §f" + sender + " §ajoined your room! 🎶");
                            AudioTrack current = AudioManager.getInstance().getCurrentTrack();
                            if (current != null) {
                                broadcastTrackIfHost(current);
                            }
                            broadcastPauseIfHost(AudioManager.getInstance().isPaused());
                            publishHostRoomState();
                            broadcastMembersSync();
                            broadcastRoomPresence();
                        }
                    }
                }
                return;
            }

            if ("host_heartbeat".equals(type) && !isHost) {
                if (!isInRoom()) return;
                if (obj.has("code")) {
                    String incCode = obj.get("code").getAsString();
                    if (this.roomCode != null && !this.roomCode.equalsIgnoreCase(incCode)) {
                        return; // Heartbeat from another room, ignore
                    }
                }
                if (obj.has("host")) {
                    String h = obj.get("host").getAsString();
                    if (h != null && !h.isBlank() && !h.equalsIgnoreCase("Host")) {
                        currentHost = h;
                    }
                }
                if (obj.has("room") && this.roomName != null) {
                    this.roomName = obj.get("room").getAsString();
                }
                if (obj.has("code") && this.roomCode != null) {
                    this.roomCode = obj.get("code").getAsString();
                }
                if (obj.has("members")) {
                    JsonArray arr = obj.getAsJsonArray("members");
                    synchronized (this) {
                        members.clear();
                        for (JsonElement e : arr) {
                            members.add(e.getAsString());
                        }
                        members.add(myName);
                        if (currentHost != null) members.add(currentHost);
                    }
                }
                if (obj.has("queue")) {
                    JsonArray qArr = obj.getAsJsonArray("queue");
                    List<AudioTrack> syncedQueue = new ArrayList<>();
                    for (JsonElement e : qArr) {
                        if (e.isJsonObject()) {
                            JsonObject qo = e.getAsJsonObject();
                            String qTitle = qo.has("title") ? qo.get("title").getAsString() : "Track";
                            String qArtist = qo.has("artist") ? qo.get("artist").getAsString() : "";
                            String qUrl = qo.has("url") ? qo.get("url").getAsString() : "";
                            long qDur = qo.has("duration") ? qo.get("duration").getAsLong() : -1;
                            String qArt = qo.has("artworkUrl") ? qo.get("artworkUrl").getAsString() : null;
                            syncedQueue.add(new AudioTrack(qTitle, qArtist, qUrl, false, qDur, "Room", qArt));
                        }
                    }
                    AudioManager.getInstance().syncGuestQueue(syncedQueue);
                }
                if (obj.has("track")) {
                    JsonObject tObj = obj.getAsJsonObject("track");
                    String title = tObj.has("title") ? tObj.get("title").getAsString() : "Party Track";
                    String artist = tObj.has("artist") ? tObj.get("artist").getAsString() : "Host";
                    String url = tObj.has("url") ? tObj.get("url").getAsString() : null;
                    String category = tObj.has("category") ? tObj.get("category").getAsString() : "Room";
                    String artwork = tObj.has("artwork") ? tObj.get("artwork").getAsString() : null;
                    long duration = tObj.has("duration") ? tObj.get("duration").getAsLong() : -1;
                    long hostElapsed = tObj.has("elapsed") ? tObj.get("elapsed").getAsLong() : 0;
                    boolean hostPaused = tObj.has("isPaused") && tObj.get("isPaused").getAsBoolean();

                    if (tObj.has("localStreamUrl")) {
                        String localStream = tObj.get("localStreamUrl").getAsString();
                        if (isUrlReachable(localStream, 600)) {
                            url = localStream;
                        }
                    }

                    if (url != null && !url.isBlank()) {
                        AudioTrack current = AudioManager.getInstance().getCurrentTrack();
                        boolean isPlaying = AudioManager.getInstance().isPlaying();
                        boolean isPaused = AudioManager.getInstance().isPaused();

                        if (current == null || !url.equals(current.getUrl())) {
                            final AudioTrack syncTrack = new AudioTrack(title, artist, url, false, duration, category, artwork);
                            partyExecutor.submit(() -> {
                                AudioManager.getInstance().playTrackInternal(syncTrack, false, Math.max(0, hostElapsed));
                            });
                        } else {
                            long myElapsed = AudioManager.getInstance().getElapsedSeconds();
                            if (Math.abs(myElapsed - hostElapsed) > 4) {
                                final AudioTrack syncTrack = new AudioTrack(title, artist, url, false, duration, category, artwork);
                                partyExecutor.submit(() -> {
                                    AudioManager.getInstance().playTrackInternal(syncTrack, false, Math.max(0, hostElapsed));
                                });
                            }
                        }

                        if (hostPaused != isPaused) {
                            Minecraft.getInstance().execute(() -> {
                                AudioManager.getInstance().togglePauseRemote(hostPaused);
                            });
                        }
                    }
                }
                return;
            }

            if ("members_sync".equals(type) && !isHost) {
                if (obj.has("host")) {
                    String h = obj.get("host").getAsString();
                    if (h != null && !h.isBlank() && !h.equalsIgnoreCase("Host")) {
                        currentHost = h;
                    }
                }
                if (obj.has("members")) {
                    com.google.gson.JsonArray arr = obj.getAsJsonArray("members");
                    synchronized (this) {
                        members.clear();
                        for (com.google.gson.JsonElement e : arr) {
                            String mStr = e.getAsString();
                            if (mStr != null && !mStr.isBlank()) {
                                members.add(mStr);
                            }
                        }
                        if (myName != null) members.add(myName);
                        if (currentHost != null && !currentHost.equalsIgnoreCase("Host")) {
                            members.add(currentHost);
                        }
                    }
                }
                return;
            }

            if ("request_sync".equals(type) && isHost) {
                AudioTrack current = AudioManager.getInstance().getCurrentTrack();
                if (current != null) {
                    broadcastTrackIfHost(current);
                }
                broadcastPauseIfHost(AudioManager.getInstance().isPaused());
                publishHostRoomState();
                broadcastMembersSync();
                broadcastRoomPresence();
                return;
            } else if ("leave".equals(type)) {
                synchronized (this) {
                    members.remove(sender);
                }
                if (isHost) {
                    broadcastRoomPresence();
                    broadcastMembersSync();
                }
            } else if ("track_sync".equals(type) && !isHost) {
                String title = obj.has("title") ? obj.get("title").getAsString() : "Party Track";
                String artist = obj.has("artist") ? obj.get("artist").getAsString() : "Host";
                String url = obj.has("url") ? obj.get("url").getAsString() : null;
                String category = obj.has("category") ? obj.get("category").getAsString() : "Room";
                String artwork = obj.has("artwork") ? obj.get("artwork").getAsString() : null;
                long duration = obj.has("duration") ? obj.get("duration").getAsLong() : -1;
                long hostElapsed = obj.has("elapsed") ? obj.get("elapsed").getAsLong() : 0;

                if (obj.has("localStreamUrl")) {
                    String localStream = obj.get("localStreamUrl").getAsString();
                    if (isUrlReachable(localStream, 600)) {
                        url = localStream;
                    }
                }

                if (url != null && !url.isBlank()) {
                    AudioTrack current = AudioManager.getInstance().getCurrentTrack();
                    boolean isPlaying = AudioManager.getInstance().isPlaying();
                    if (current != null && isPlaying && url.equals(current.getUrl())) {
                        long myElapsed = AudioManager.getInstance().getElapsedSeconds();
                        if (Math.abs(myElapsed - hostElapsed) <= 4) {
                            // Already playing this song within 4 seconds of host - do NOT restart or stutter!
                            return;
                        }
                    }

                    final AudioTrack syncTrack = new AudioTrack(title, artist, url, false, duration, category, artwork);
                    partyExecutor.submit(() -> {
                        AudioManager.getInstance().playTrackInternal(syncTrack, false, Math.max(0, hostElapsed));
                    });
                }
            } else if ("seek_sync".equals(type) && !isHost) {
                if (obj.has("ratio")) {
                    double ratio = obj.get("ratio").getAsDouble();
                    Minecraft.getInstance().execute(() -> {
                        AudioManager.getInstance().seekToRemote(ratio);
                    });
                }
            } else if ("pause_sync".equals(type) && !isHost) {
                if (obj.has("paused")) {
                    boolean paused = obj.get("paused").getAsBoolean();
                    Minecraft.getInstance().execute(() -> {
                        AudioManager.getInstance().togglePauseRemote(paused);
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------------
    // Room Directory Listener (Discovered Rooms for Room List)
    // ------------------------------------------------------------------------

    private void startDirectoryListener() {
        if (dirListening) return;
        this.dirListening = true;

        partyExecutor.submit(() -> {
            while (dirListening) {
                try {
                    // Live streaming of global room directory without replaying stale messages from yesterday
                    URL url = new URI("https://ntfy.sh/pulse_rooms_global/json?since=now").toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "PulseMusicPlayer/2.6");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(55000); // persistent stream connection

                    if (conn.getResponseCode() == 200) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while (dirListening && (line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                try {
                                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                                    String id = event.has("id") ? event.get("id").getAsString() : null;
                                    if (id != null && !seenDirEventIds.add(id)) {
                                        continue;
                                    }
                                    if (event.has("time")) {
                                        long eventTimeSec = event.get("time").getAsLong();
                                        long curTimeSec = System.currentTimeMillis() / 1000;
                                        if (curTimeSec - eventTimeSec > 30) {
                                            continue; // Discard stale message from previous day/session
                                        }
                                    }
                                    if (event.has("message")) {
                                        handleDirectoryPayload(event.get("message").getAsString());
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }

                    // Prune stale rooms older than 20s
                    long now = System.currentTimeMillis();
                    discoveredRooms.removeIf(r -> (now - r.getLastSeen() > 20000));

                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored2) {
                        break;
                    }
                }
            }
        });

        // Periodic pruning of stale rooms
        partyExecutor.submit(() -> {
            while (dirListening) {
                try {
                    Thread.sleep(4000);
                    long now = System.currentTimeMillis();
                    discoveredRooms.removeIf(r -> (now - r.getLastSeen() > 20000));
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        });
    }

    private void handleDirectoryPayload(String rawJson) {
        try {
            JsonObject obj = JsonParser.parseString(rawJson).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";

            if ("room_presence".equals(type)) {
                String name = obj.has("name") ? obj.get("name").getAsString() : "Room";
                String host = obj.has("host") ? obj.get("host").getAsString() : "Host";
                boolean hasPass = obj.has("hasPassword") && obj.get("hasPassword").getAsBoolean();
                String pass = obj.has("password") ? obj.get("password").getAsString() : "";
                int count = obj.has("members") ? obj.get("members").getAsInt() : 1;
                String code = obj.has("code") ? obj.get("code").getAsString() : "";

                RoomEntry existing = null;
                for (RoomEntry re : discoveredRooms) {
                    if (re.getName().equalsIgnoreCase(name) || (!code.isEmpty() && re.getCode().equalsIgnoreCase(code))) {
                        existing = re;
                        break;
                    }
                }

                if (existing != null) {
                    existing.touch();
                    existing.setMemberCount(count);
                } else {
                    discoveredRooms.add(new RoomEntry(name, code, host, hasPass, pass, count));
                }
            } else if ("room_close".equals(type) || "room_disbanded".equals(type) || "room_removed".equals(type) || "disband".equals(type)) {
                String name = obj.has("name") ? obj.get("name").getAsString() : "";
                String code = obj.has("code") ? obj.get("code").getAsString() : "";
                discoveredRooms.removeIf(r -> (!name.isEmpty() && r.getName().equalsIgnoreCase(name))
                        || (!code.isEmpty() && r.getCode().equalsIgnoreCase(code)));
            } else if ("room_query".equals(type)) {
                // Another player is looking for active rooms! If we are a host, announce ourselves right now
                if (isInRoom() && isHost) {
                    broadcastRoomPresence();
                }
            }
        } catch (Throwable ignored) {}
    }

    public void refreshRooms() {
        // 1. Merge local IPC rooms immediately (0ms delay for alts on same PC)
        try {
            List<RoomEntry> localRooms = LocalPresenceManager.getActiveRooms();
            for (RoomEntry lr : localRooms) {
                boolean exists = false;
                for (RoomEntry er : discoveredRooms) {
                    if (er.name.equalsIgnoreCase(lr.name)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    discoveredRooms.add(lr);
                }
            }
        } catch (Throwable ignored) {}

        if (isServerNetworkingActive()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("action", "query_rooms");
            ClientPlayNetworking.send(new PulseNetworkPayload(obj.toString()));
            return;
        }

        partyExecutor.submit(() -> {
            try {
                // 1. Send query probe so active hosts announce themselves immediately
                JsonObject query = new JsonObject();
                query.addProperty("type", "room_query");
                query.addProperty("sender", getPlayerName());
                sendNtfyMessage("pulse_rooms_global", query);

                // 2. Poll only fresh announcements from the last 20 seconds
                URL url = new URI("https://ntfy.sh/pulse_rooms_global/json?poll=1&since=20s").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "PulseMusicPlayer/2.6");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(4000);

                if (conn.getResponseCode() == 200) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isBlank()) continue;
                            try {
                                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                                if (event.has("time")) {
                                    long eventTimeSec = event.get("time").getAsLong();
                                    long curTimeSec = System.currentTimeMillis() / 1000;
                                    if (curTimeSec - eventTimeSec > 20) {
                                        continue;
                                    }
                                }
                                if (event.has("message")) {
                                    handleDirectoryPayload(event.get("message").getAsString());
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    // ------------------------------------------------------------------------
    // Personal Invite Listener (/musicplayer room invite <player>)
    // ------------------------------------------------------------------------

    private void startPersonalInviteListener() {
        if (inviteListening) return;
        this.inviteListening = true;

        partyExecutor.submit(() -> {
            while (inviteListening) {
                try {
                    String myName = getPlayerName();
                    if ("Player".equalsIgnoreCase(myName)) {
                        Thread.sleep(2000);
                        continue;
                    }

                    String topic = "pulse_invite_" + myName.toLowerCase().trim();
                    URL url = new URI("https://ntfy.sh/" + topic + "/json?since=now").toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "PulseMusicPlayer/2.6");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(55000); // real-time streaming

                    if (conn.getResponseCode() == 200) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while (inviteListening && (line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                try {
                                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                                    String id = event.has("id") ? event.get("id").getAsString() : null;
                                    if (id != null && !processedInviteIds.add(id)) {
                                        continue;
                                    }
                                    if (event.has("message")) {
                                        handlePersonalInvite(event.get("message").getAsString());
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored2) {
                        break;
                    }
                }
            }
        });
    }

    private void handlePersonalInvite(String rawJson) {
        try {
            JsonObject obj = JsonParser.parseString(rawJson).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            if (!"invite".equals(type)) return;

            String sender = obj.has("sender") ? obj.get("sender").getAsString() : "Player";
            String room = obj.has("room") ? obj.get("room").getAsString() : "Party";
            String pass = obj.has("password") ? obj.get("password").getAsString() : "";

            displayInvite(sender, room, pass);
        } catch (Throwable ignored) {}
    }

    public void displayInvite(String sender, String room, String pass) {
        if (sender.equalsIgnoreCase(getPlayerName())) return;

        // Automatically register invitation so clicking ACCEPT bypasses any room password
        invitedRooms.add(room.toLowerCase().trim());
        invitedRooms.add(room.replace("\"", "").toLowerCase().trim());

        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        client.execute(() -> {
            if (client.player == null) return;

            String joinCmd = "/mp";

            Component inviteMsg = Component.literal("§d§l∿ PULSE §8| §f" + sender + " §7invited you to join room §d\"" + room + "\"§7!  ")
                    .append(Component.literal("§a§l[▶ OPEN PULSE]")
                            .withStyle(style -> style
                                    .withColor(0x00FF88)
                                    .withUnderlined(true)
                                    .withBold(true)
                                    .withClickEvent(new ClickEvent.RunCommand(joinCmd))
                                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("§aClick to open Pulse Music Player!")))));

            client.player.sendSystemMessage(inviteMsg);

            // Play notification sound only if toasts/notifications are enabled and outside cooldown
            long now = System.currentTimeMillis();
            if (SettingsManager.getInstance().isToastNotifications() && now - lastInviteSoundTime > 8000) {
                lastInviteSoundTime = now;
                try {
                    client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.2f));
                } catch (Throwable ignored) {}
            }
        });
    }

    // ------------------------------------------------------------------------
    // Server / World Mod-Users Discovery & Auto-Sync
    // ------------------------------------------------------------------------

    private void startServerDiscoveryListener() {
        if (serverListening) return;
        this.serverListening = true;

        partyExecutor.submit(() -> {
            while (serverListening) {
                try {
                    String serverId = getServerIdentifier();
                    if ("global_lobby".equals(serverId)) {
                        Thread.sleep(3000);
                        continue;
                    }
                    this.currentServerId = serverId;
                    String topic = "pulse_srv_" + serverId;

                    URL url = new URI("https://ntfy.sh/" + topic + "/json?since=now").toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "PulseMusicPlayer/2.6");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(55000); // persistent streaming

                    if (conn.getResponseCode() == 200) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while (serverListening && (line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                if (!getServerIdentifier().equals(currentServerId)) {
                                    break; // Server changed, rebind
                                }
                                try {
                                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                                    String id = event.has("id") ? event.get("id").getAsString() : null;
                                    if (id != null && !seenServerEventIds.add(id)) {
                                        continue;
                                    }
                                    if (event.has("message")) {
                                        handleServerPayload(event.get("message").getAsString());
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }

                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException ignored2) {
                        break;
                    }
                }
            }
        });

        // Periodic heartbeat beacon announcing mod presence on this server every 8s
        partyExecutor.submit(() -> {
            while (serverListening) {
                try {
                    String me = getPlayerName();
                    if (!"Player".equalsIgnoreCase(me)) {
                        JsonObject beacon = new JsonObject();
                        beacon.addProperty("type", "user_presence");
                        beacon.addProperty("player", me);
                        beacon.addProperty("isHost", isHost);
                        beacon.addProperty("room", roomName != null ? roomName : "");
                        sendNtfyMessage("pulse_srv_" + getServerIdentifier(), beacon);
                    }
                    long now = System.currentTimeMillis();
                    serverModUsers.entrySet().removeIf(e -> (now - e.getValue() > 30000));

                    Thread.sleep(8000);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        });
    }

    private void handleServerPayload(String rawJson) {
        try {
            JsonObject obj = JsonParser.parseString(rawJson).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            String myName = getPlayerName();

            if ("user_presence".equals(type)) {
                String player = obj.has("player") ? obj.get("player").getAsString() : null;
                boolean playerIsHost = obj.has("isHost") && obj.get("isHost").getAsBoolean();
                String pRoom = obj.has("room") ? obj.get("room").getAsString() : null;

                if (player != null && !player.isBlank()) {
                    serverModUsers.put(player, System.currentTimeMillis());
                    if (playerIsHost && pRoom != null && !pRoom.isBlank() && !player.equalsIgnoreCase(myName)) {
                        activeServerPartyHost = player;
                        activeServerPartyRoom = pRoom;
                    }
                }
            } else if ("server_music_party".equals(type)) {
                String host = obj.has("host") ? obj.get("host").getAsString() : "Host";
                String room = obj.has("room") ? obj.get("room").getAsString() : null;

                if (host.equalsIgnoreCase(myName) || room == null) return;

                activeServerPartyHost = host;
                activeServerPartyRoom = room;

                Minecraft client = Minecraft.getInstance();
                if (client == null) return;

                client.execute(() -> {
                    if (client.player == null) return;

                    // 1. If Auto-Connect is enabled and player is not currently in a room, seamlessly auto-sync!
                    if (SettingsManager.getInstance().isAutoConnectServerParty() && !isInRoom()) {
                        joinRoom(room, "");
                        client.player.sendSystemMessage(Component.literal("§a[Pulse] 🎧 Auto-connected to §f" + host + "§a's music party on this server! 💖"));
                        return;
                    }

                    // 2. Otherwise display interactive clickable prompt in chat
                    if (!isInRoom()) {
                        String joinCmd = "/mp";
                        Component prompt = Component.literal("§d§l∿ PULSE §8| §f" + host + " §7started playing music on this server! ")
                                .append(Component.literal("§a§l[▶ CLICK TO LISTEN TOGETHER]")
                                        .withStyle(style -> style
                                                .withColor(0x00FF88)
                                                .withUnderlined(true)
                                                .withBold(true)
                                                .withClickEvent(new ClickEvent.RunCommand(joinCmd))
                                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("§aClick to listen together with §f" + host + "§a!")))))
                                .withStyle(style -> style
                                        .withClickEvent(new ClickEvent.RunCommand(joinCmd))
                                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("§aClick to listen together with §f" + host + "§a!"))));

                        client.player.sendSystemMessage(prompt);

                        if (SettingsManager.getInstance().isToastNotifications()) {
                            long now = System.currentTimeMillis();
                            if (now - lastServerPartySoundTime > 15000) {
                                lastServerPartySoundTime = now;
                                try {
                                    client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.2f));
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private void sendNtfyMessage(String topic, JsonObject obj) {
        // Broadcast over persistent real-time MQTT broker (<25ms latency, no rate limits)
        try {
            String mqttTopic = topic.replace("pulse_room_", "pulse/room/").replace("pulse_rooms_global", "pulse/rooms/global");
            PulseMqttClient.getInstance().publish(mqttTopic, obj.toString());
        } catch (Throwable ignored) {}

        partyExecutor.submit(() -> {
            try {
                URL url = new URI("https://ntfy.sh/" + topic).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", "PulseMusicPlayer/2.6");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                byte[] data = obj.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                }
                conn.getResponseCode();
            } catch (Throwable ignored) {}
        });
    }

    // ------------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------------

    public boolean isInRoom() {
        return roomName != null && !roomName.trim().isEmpty();
    }

    public String getRoomName() {
        return roomName;
    }

    public boolean isHost() {
        return isHost;
    }

    public String getCurrentHost() {
        return (currentHost != null && !currentHost.isBlank()) ? currentHost : (isHost ? getPlayerName() : "Host");
    }

    public synchronized List<String> getMembers() {
        List<String> list = new ArrayList<>();
        String myName = getPlayerName();
        String host = getCurrentHost();
        if (host == null || host.isBlank()) host = "Host";

        // 1. Host first with player name and host tag
        if (isHost || myName.equalsIgnoreCase(host)) {
            list.add(myName + " §e[Host] §7(You)");
        } else {
            list.add(host + " §e[Host]");
        }

        // 2. Members inside the room
        synchronized (members) {
            for (String m : members) {
                String clean = m.replaceAll("\\s*\\(.*?\\)", "").replaceAll("§.", "").trim();
                if (clean.isEmpty() || clean.equalsIgnoreCase(host) || clean.equalsIgnoreCase("Host")) continue;

                if (clean.equalsIgnoreCase(myName)) {
                    if (!isHost) {
                        list.add(clean + " §a[Member] §7(You)");
                    }
                } else {
                    list.add(clean + " §a[Member]");
                }
            }
        }
        return list;
    }

    public List<RoomEntry> getDiscoveredRooms() {
        return Collections.unmodifiableList(discoveredRooms);
    }

    private void sendPlayerMessage(String text) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendSystemMessage(Component.literal(text));
                    }
                });
            }
        } catch (Throwable ignored) {}
    }
}
