package com.slowbag.pulse.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.slowbag.pulse.client.audio.AudioManager;
import com.slowbag.pulse.client.audio.AudioTrack;
import com.slowbag.pulse.client.audio.FavoritesManager;
import com.slowbag.pulse.client.audio.MusicSearchService;
import com.slowbag.pulse.client.audio.PartyManager;
import com.slowbag.pulse.client.audio.RecentUrlManager;
import com.slowbag.pulse.client.audio.YouTubeResolver;
import com.slowbag.pulse.client.gui.HudConfigManager;
import com.slowbag.pulse.client.gui.HudMoveScreen;
import com.slowbag.pulse.client.gui.MusicPlayerScreen;
import com.slowbag.pulse.client.gui.ScreenCompat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import com.slowbag.pulse.client.audio.MusicBadgeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Streamlined, clean Brigadier command tree for /mp.
 * Only presents essential commands without duplicates or clutter.
 */
public class MusicPlayerCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        registerAlias(dispatcher, "mp");
        registerAlias(dispatcher, "musicplayer");
    }

    private static boolean checkGuestPermission(FabricClientCommandSource source) {
        PartyManager pm = PartyManager.getInstance();
        if (pm.isInRoom() && !pm.isHost()) {
            source.sendFeedback(Component.literal("§c[Pulse] You are in §d" + pm.getRoomName() + "§c! Only the room host (" + pm.getCurrentHost() + ") can control playback. Use §f/mp queue <song> §cto add it to the queue."));
            return false;
        }
        return true;
    }

    private static void registerAlias(CommandDispatcher<FabricClientCommandSource> dispatcher, String alias) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal(alias)
                .executes(context -> openGui(0));

        // /mp find
        root.then(ClientCommands.literal("find").executes(MusicPlayerCommand::executeFindCommand));

        // /mp play <song/url>
        root.then(ClientCommands.literal("play")
                .then(ClientCommands.argument("query", StringArgumentType.greedyString())
                        .executes(context -> {
                            if (!checkGuestPermission(context.getSource())) return 1;
                            String query = StringArgumentType.getString(context, "query");
                            if (query.startsWith("http://") || query.startsWith("https://") || query.startsWith("www.") || YouTubeResolver.isYouTubeUrl(query)) {
                                return executeUrlCommand(context, query, false);
                            } else {
                                context.getSource().sendFeedback(Component.literal("§e[Pulse] Searching for '" + query + "'..."));
                                MusicSearchService.searchSongsAsync(query, results -> {
                                    if (!results.isEmpty()) {
                                        AudioTrack top = results.get(0);
                                        AudioManager.getInstance().playTrack(top);
                                        Minecraft.getInstance().execute(() -> {
                                            if (Minecraft.getInstance().player != null) {
                                                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§a[Pulse] Now Playing: §f" + top.getTitle() + " §7(" + top.getArtist() + ")"));
                                            }
                                        });
                                    } else {
                                        Minecraft.getInstance().execute(() -> {
                                            if (Minecraft.getInstance().player != null) {
                                                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§c[Pulse] No songs found for: " + query));
                                            }
                                        });
                                    }
                                });
                            }
                            return 1;
                        })));

        // /mp pause
        root.then(ClientCommands.literal("pause").executes(context -> {
            if (!checkGuestPermission(context.getSource())) return 1;
            AudioManager.getInstance().togglePause();
            boolean paused = AudioManager.getInstance().isPaused();
            context.getSource().sendFeedback(Component.literal(paused ? "§e[Pulse] ❚❚ Playback paused." : "§a[Pulse] ▶ Playback resumed."));
            return 1;
        }));

        // /mp stop
        root.then(ClientCommands.literal("stop").executes(context -> {
            if (!checkGuestPermission(context.getSource())) return 1;
            AudioManager.getInstance().stop();
            context.getSource().sendFeedback(Component.literal("§c[Pulse] Music stopped."));
            return 1;
        }));

        // /mp next
        root.then(ClientCommands.literal("next").executes(context -> {
            if (!checkGuestPermission(context.getSource())) return 1;
            AudioManager.getInstance().next();
            context.getSource().sendFeedback(Component.literal("§a[Pulse] ⏭ Skipped to next track."));
            return 1;
        }));

        // /mp prev
        root.then(ClientCommands.literal("prev").executes(context -> {
            if (!checkGuestPermission(context.getSource())) return 1;
            AudioManager.getInstance().previous();
            context.getSource().sendFeedback(Component.literal("§a[Pulse] ⏮ Playing previous track."));
            return 1;
        }));

        // /mp loop
        root.then(ClientCommands.literal("loop").executes(context -> {
            if (!checkGuestPermission(context.getSource())) return 1;
            AudioManager.getInstance().toggleLoop();
            boolean loop = AudioManager.getInstance().isLoopCurrent();
            context.getSource().sendFeedback(Component.literal(loop ? "§a[Pulse] 🔁 Loop: On (Repeating track)" : "§7[Pulse] 🔁 Loop: Off"));
            return 1;
        }));

        // /mp shuffle
        root.then(ClientCommands.literal("shuffle").executes(context -> {
            if (!checkGuestPermission(context.getSource())) return 1;
            AudioManager.getInstance().toggleShuffle();
            boolean shuf = AudioManager.getInstance().isShuffle();
            context.getSource().sendFeedback(Component.literal(shuf ? "§a[Pulse] 🔀 Shuffle: On" : "§7[Pulse] 🔀 Shuffle: Off"));
            return 1;
        }));

        // /mp queue [clear | <song/url>]
        LiteralArgumentBuilder<FabricClientCommandSource> queueCmd = ClientCommands.literal("queue")
                .executes(MusicPlayerCommand::executeQueueView);
        queueCmd.then(ClientCommands.literal("clear").executes(context -> {
            int sz = AudioManager.getInstance().getQueueSize();
            AudioManager.getInstance().clearQueue();
            context.getSource().sendFeedback(Component.literal("§a[Pulse] 🗑 Cleared " + sz + " queued songs."));
            return 1;
        }));
        queueCmd.then(ClientCommands.argument("query", StringArgumentType.greedyString())
                .executes(MusicPlayerCommand::executeQueueAdd));
        root.then(queueCmd);

        // /mp star
        root.then(ClientCommands.literal("star").executes(context -> {
            AudioTrack track = AudioManager.getInstance().getCurrentTrack();
            if (track != null) {
                boolean starred = FavoritesManager.getInstance().toggleFavorite(track);
                context.getSource().sendFeedback(Component.literal(starred ? "§a[Pulse] ★ Starred: §f" + track.getTitle() : "§7[Pulse] ☆ Removed from favorites: §f" + track.getTitle()));
            } else {
                openGui(2); // open starred tab
            }
            return 1;
        }));

        // /mp hud [move|on|off|left|right]
        LiteralArgumentBuilder<FabricClientCommandSource> hudCmd = ClientCommands.literal("hud")
                .executes(context -> {
                    String state = HudConfigManager.getInstance().cycleSetting();
                    context.getSource().sendFeedback(Component.literal("§a[Pulse] ✨ In-Game HUD: §f" + state));
                    return 1;
                });
        hudCmd.then(ClientCommands.literal("move").executes(context -> {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                ScreenCompat.setScreen(client, new HudMoveScreen(null));
            });
            return 1;
        }));
        hudCmd.then(ClientCommands.literal("on").executes(context -> {
            HudConfigManager.getInstance().setHudEnabled(true);
            context.getSource().sendFeedback(Component.literal("§b[Pulse] 🎛 In-Game HUD: §aEnabled"));
            return 1;
        }));
        hudCmd.then(ClientCommands.literal("off").executes(context -> {
            HudConfigManager.getInstance().setHudEnabled(false);
            context.getSource().sendFeedback(Component.literal("§b[Pulse] 🎛 In-Game HUD: §cDisabled"));
            return 1;
        }));
        hudCmd.then(ClientCommands.literal("left").executes(context -> {
            HudConfigManager.getInstance().setHudEnabled(true);
            HudConfigManager.getInstance().setPosition("LEFT");
            context.getSource().sendFeedback(Component.literal("§b[Pulse] 🎛 In-Game HUD: §fLeft Side"));
            return 1;
        }));
        hudCmd.then(ClientCommands.literal("right").executes(context -> {
            HudConfigManager.getInstance().setHudEnabled(true);
            HudConfigManager.getInstance().setPosition("RIGHT");
            context.getSource().sendFeedback(Component.literal("§b[Pulse] 🎛 In-Game HUD: §fRight Side"));
            return 1;
        }));
        root.then(hudCmd);

        // /mp volume <0-100>
        root.then(ClientCommands.literal("volume")
                .then(ClientCommands.argument("percent", IntegerArgumentType.integer(0, 100))
                        .executes(context -> {
                            int pct = IntegerArgumentType.getInteger(context, "percent");
                            AudioManager.getInstance().setVolume(pct / 100.0f);
                            context.getSource().sendFeedback(Component.literal("§a[Pulse] 🔊 Volume set to: §f" + pct + "%"));
                            return 1;
                        })));

        // /mp join <code> [password]
        LiteralArgumentBuilder<FabricClientCommandSource> joinDirect = ClientCommands.literal("join")
                .then(ClientCommands.argument("code", StringArgumentType.word())
                        .executes(context -> executeJoinRoom(context, StringArgumentType.getString(context, "code"), ""))
                        .then(ClientCommands.argument("password", StringArgumentType.greedyString())
                                .executes(context -> executeJoinRoom(context, StringArgumentType.getString(context, "code"), StringArgumentType.getString(context, "password")))));
        root.then(joinDirect);

        // /mp room [join|leave|disband|code|info|create|list]
        LiteralArgumentBuilder<FabricClientCommandSource> roomCmd = ClientCommands.literal("room")
                .executes(context -> openGui(4)); // open Rooms tab

        // /mp room join <code> [password]
        roomCmd.then(ClientCommands.literal("join")
                .then(ClientCommands.argument("code", StringArgumentType.word())
                        .executes(context -> executeJoinRoom(context, StringArgumentType.getString(context, "code"), ""))
                        .then(ClientCommands.argument("password", StringArgumentType.greedyString())
                                .executes(context -> executeJoinRoom(context, StringArgumentType.getString(context, "code"), StringArgumentType.getString(context, "password"))))));

        // /mp room <code> [password]
        roomCmd.then(ClientCommands.argument("code", StringArgumentType.word())
                .executes(context -> executeJoinRoom(context, StringArgumentType.getString(context, "code"), ""))
                .then(ClientCommands.argument("password", StringArgumentType.greedyString())
                        .executes(context -> executeJoinRoom(context, StringArgumentType.getString(context, "code"), StringArgumentType.getString(context, "password")))));

        // /mp room leave
        roomCmd.then(ClientCommands.literal("leave").executes(context -> {
            PartyManager pm = PartyManager.getInstance();
            if (!pm.isInRoom()) {
                context.getSource().sendFeedback(Component.literal("§7[Pulse] You are not currently in any room."));
                return 1;
            }
            pm.leaveRoom();
            return 1;
        }));

        // /mp room disband
        roomCmd.then(ClientCommands.literal("disband").executes(context -> {
            PartyManager pm = PartyManager.getInstance();
            if (!pm.isInRoom()) {
                context.getSource().sendFeedback(Component.literal("§7[Pulse] You are not currently in any room."));
                return 1;
            }
            if (!pm.isHost()) {
                context.getSource().sendFeedback(Component.literal("§c[Pulse] Only the room host (" + pm.getCurrentHost() + ") can disband this room."));
                return 1;
            }
            pm.disbandRoom();
            return 1;
        }));

        // /mp room code
        roomCmd.then(ClientCommands.literal("code").executes(context -> {
            PartyManager pm = PartyManager.getInstance();
            if (!pm.isInRoom()) {
                context.getSource().sendFeedback(Component.literal("§7[Pulse] You are not currently in any room."));
                return 1;
            }
            context.getSource().sendFeedback(Component.literal("§d[Pulse] 👥 Room: §f" + pm.getRoomName() + " §8| §eCode: §f§l" + pm.getRoomCode() + " §7(Share: §f/mp room join " + pm.getRoomCode() + "§7)"));
            return 1;
        }));

        // /mp room info
        roomCmd.then(ClientCommands.literal("info").executes(context -> {
            PartyManager pm = PartyManager.getInstance();
            if (!pm.isInRoom()) {
                context.getSource().sendFeedback(Component.literal("§7[Pulse] You are not currently in any room."));
                return 1;
            }
            context.getSource().sendFeedback(Component.literal("§5§m-----------------------------------------------------"));
            context.getSource().sendFeedback(Component.literal("§d♫ Room Info §8» §f" + pm.getRoomName()));
            context.getSource().sendFeedback(Component.literal("  §8• §eCode: §f§l" + pm.getRoomCode()));
            context.getSource().sendFeedback(Component.literal("  §8• §bHost: §f" + pm.getCurrentHost() + (pm.isHost() ? " §e(You)" : "")));
            context.getSource().sendFeedback(Component.literal("  §8• §dMembers (" + pm.getMembers().size() + "): §f" + String.join(", ", pm.getMembers())));
            context.getSource().sendFeedback(Component.literal("§5§m-----------------------------------------------------"));
            return 1;
        }));

        // /mp room create <name> [password]
        roomCmd.then(ClientCommands.literal("create")
                .then(ClientCommands.argument("name", StringArgumentType.word())
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            PartyManager.getInstance().createRoom(name, "");
                            return 1;
                        })
                        .then(ClientCommands.argument("pass", StringArgumentType.greedyString())
                                .executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            String pass = StringArgumentType.getString(context, "pass");
                            PartyManager.getInstance().createRoom(name, pass);
                            return 1;
                        }))));

        root.then(roomCmd);

        dispatcher.register(root);
    }

    private static int executeQueueView(CommandContext<FabricClientCommandSource> context) {
        AudioManager am = AudioManager.getInstance();
        List<AudioTrack> upNext = am.getUpcomingQueue();
        List<AudioTrack> playlist = am.getPlaylist();

        if (upNext.isEmpty() && playlist.isEmpty()) {
            context.getSource().sendFeedback(Component.literal("§7[Pulse] The queue is currently empty. Use §f/mp queue <song/link> §7to queue tracks!"));
            return 1;
        }

        if (!upNext.isEmpty()) {
            context.getSource().sendFeedback(Component.literal("§a[Pulse] 🎶 Upcoming Queued Tracks (§f" + upNext.size() + " songs§a):"));
            for (int i = 0; i < Math.min(5, upNext.size()); i++) {
                AudioTrack t = upNext.get(i);
                String ord = (i == 0) ? "§aPlays Next" : ((i == 1) ? "§bPlays Next Next" : "§7Queued #" + (i + 1));
                context.getSource().sendFeedback(Component.literal("§8" + (i + 1) + ". §f" + t.getTitle() + " §7(" + t.getArtist() + ") ~ " + ord));
            }
            if (upNext.size() > 5) {
                context.getSource().sendFeedback(Component.literal("§7...and §f" + (upNext.size() - 5) + " §7more queued tracks."));
            }
        } else {
            context.getSource().sendFeedback(Component.literal("§a[Pulse] 📻 Station Playlist (§f" + playlist.size() + " tracks§a):"));
            for (int i = 0; i < Math.min(5, playlist.size()); i++) {
                AudioTrack t = playlist.get(i);
                boolean isCurr = (am.getCurrentTrack() != null && am.getCurrentTrack().getTitle().equals(t.getTitle()));
                context.getSource().sendFeedback(Component.literal("§8" + (i + 1) + ". " + (isCurr ? "§a▶ " : "§f") + t.getTitle() + " §7(" + t.getArtist() + ")"));
            }
        }
        return 1;
    }

    private static int executeQueueAdd(CommandContext<FabricClientCommandSource> context) {
        PartyManager pm = PartyManager.getInstance();
        boolean isGuest = (pm.isInRoom() && !pm.isHost());
        String query = StringArgumentType.getString(context, "query");

        if (query.startsWith("http://") || query.startsWith("https://") || query.startsWith("www.") || YouTubeResolver.isYouTubeUrl(query)) {
            context.getSource().sendFeedback(Component.literal("§e[Pulse] Resolving link to queue..."));
            RecentUrlManager.parseUrlOrPlaylistAsync(query, tracks -> {
                if (!tracks.isEmpty()) {
                    if (isGuest) {
                        for (AudioTrack t : tracks) {
                            pm.addTrackToRoomQueue(t);
                        }
                        context.getSource().sendFeedback(Component.literal("§a[Pulse] ➕ Added §f" + tracks.size() + " §asongs to the room queue! 🎶"));
                    } else {
                        AudioManager am = AudioManager.getInstance();
                        for (AudioTrack t : tracks) {
                            am.enqueueTrackLast(t);
                        }
                        if (tracks.size() > 1) {
                            context.getSource().sendFeedback(Component.literal("§a[Pulse] ➕ Queued §f" + tracks.size() + " §atracks from playlist! 💖"));
                        } else {
                            int qPos = am.getQueueSize();
                            String ord = (qPos == 1) ? "Plays next!" : ((qPos == 2) ? "Plays next next!" : "(#" + qPos + " in queue)");
                            context.getSource().sendFeedback(Component.literal("§a[Pulse] ➕ Queued: §f" + tracks.get(0).getTitle() + " §7(" + tracks.get(0).getArtist() + ") §a~ " + ord + " 🎶"));
                        }
                    }
                } else {
                    context.getSource().sendFeedback(Component.literal("§c[Pulse] Could not resolve stream URL to queue: " + query));
                }
            });
        } else {
            context.getSource().sendFeedback(Component.literal("§e[Pulse] Searching track to queue: '" + query + "'..."));
            MusicSearchService.searchSongsAsync(query, results -> {
                if (!results.isEmpty()) {
                    AudioTrack top = results.get(0);
                    if (isGuest) {
                        pm.addTrackToRoomQueue(top);
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§a[Pulse] ➕ Added to room queue: §f" + top.getTitle() + " §7(" + top.getArtist() + ") 🎶"));
                            }
                        });
                    } else {
                        AudioManager.getInstance().enqueueTrack(top);
                        int qPos = AudioManager.getInstance().getQueueSize();
                        String ord = (qPos == 1) ? "Plays next!" : ((qPos == 2) ? "Plays next next!" : "(#" + qPos + " in queue)");
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.sendSystemMessage(Component.literal("§a[Pulse] ➕ Queued: §f" + top.getTitle() + " §7(" + top.getArtist() + ") §a~ " + ord + " 🎶"));
                            }
                        });
                    }
                } else {
                    Minecraft.getInstance().execute(() -> {
                        if (Minecraft.getInstance().player != null) {
                            Minecraft.getInstance().player.sendSystemMessage(Component.literal("§c[Pulse] No songs found to queue for: " + query));
                        }
                    });
                }
            });
        }
        return 1;
    }

    private static int executeJoinRoom(CommandContext<FabricClientCommandSource> context, String codeOrName, String password) {
        if (codeOrName == null || codeOrName.trim().isEmpty()) {
            context.getSource().sendFeedback(Component.literal("§c[Pulse] Usage: §f/mp room join <code> [password]"));
            return 0;
        }
        String target = codeOrName.trim();
        String pass = (password != null) ? password.trim() : "";
        PartyManager pm = PartyManager.getInstance();

        // Check if room requires password but none was provided
        for (PartyManager.RoomEntry r : pm.getDiscoveredRooms()) {
            if ((r.getCode().equalsIgnoreCase(target) || r.getName().equalsIgnoreCase(target)) && r.hasPassword() && pass.isEmpty()) {
                context.getSource().sendFeedback(Component.literal("§c[Pulse] Room #" + target + " is password-protected! Usage: §f/mp room join " + target + " <password>"));
                return 1;
            }
        }

        context.getSource().sendFeedback(Component.literal("§a[Pulse] 🔑 Joining room #" + target + "... 🎶"));
        boolean joined = pm.joinRoom(target, pass);
        if (!joined) {
            context.getSource().sendFeedback(Component.literal("§c[Pulse] Failed to join room #" + target + "."));
        }
        return 1;
    }

    private static int openGui(int defaultTab) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            MusicPlayerScreen screen = new MusicPlayerScreen(ScreenCompat.getCurrentScreen(client));
            if (defaultTab > 0) {
                screen.setSelectedTab(defaultTab);
            }
            ScreenCompat.setScreen(client, screen);
        });
        return 1;
    }

    private static int executeUrlCommand(CommandContext<FabricClientCommandSource> context, String rawUrl, boolean queueOnly) {
        String url = (rawUrl != null) ? rawUrl.trim() : "";
        if (url.startsWith("<") && url.endsWith(">")) {
            url = url.substring(1, url.length() - 1).trim();
        }
        if (url.isEmpty()) {
            context.getSource().sendFeedback(Component.literal("§c[Pulse] Please provide a valid URL or YouTube link! Usage: §f/mp play <url>"));
            return 0;
        }

        final String targetUrl = url;
        context.getSource().sendFeedback(Component.literal("§e[Pulse] ⚡ Resolving YouTube link: §f" + truncate(targetUrl, 42) + "§e..."));

        RecentUrlManager.parseUrlOrPlaylistAsync(targetUrl, tracks -> {
            if (tracks != null && !tracks.isEmpty()) {
                AudioManager am = AudioManager.getInstance();
                if (queueOnly) {
                    for (AudioTrack t : tracks) {
                        am.enqueueTrackLast(t);
                    }
                    if (tracks.size() > 1) {
                        sendClientMessage(context, Component.literal("§a[Pulse] ➕ Queued §f" + tracks.size() + " §atracks from playlist! 💖"));
                    } else {
                        int qPos = am.getQueueSize();
                        String ord = (qPos == 1) ? "Plays next!" : ((qPos == 2) ? "Plays next next!" : "(#" + qPos + " in queue)");
                        sendClientMessage(context, Component.literal("§a[Pulse] ➕ Queued: §f" + tracks.get(0).getTitle() + " §7(" + tracks.get(0).getArtist() + ") §a~ " + ord));
                    }
                } else {
                    am.setPlaylist(tracks);
                    if (tracks.size() > 1) {
                        sendClientMessage(context, Component.literal("§a[Pulse] 🎉 Loaded and playing §f" + tracks.size() + " §atracks from playlist!"));
                    } else {
                        AudioTrack first = tracks.get(0);
                        sendClientMessage(context, Component.literal("§a[Pulse] ▶ Now Playing: §f" + first.getTitle() + " §7(" + first.getArtist() + ") ~ Enjoy! 🎶"));
                    }
                }
            } else {
                sendClientMessage(context, Component.literal("§c[Pulse] ✕ Could not resolve YouTube link: §7" + truncate(targetUrl, 32)));
            }
        });
        return 1;
    }

    private static void sendClientMessage(CommandContext<FabricClientCommandSource> context, Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendSystemMessage(message);
                } else {
                    try {
                        context.getSource().sendFeedback(message);
                    } catch (Throwable ignored) {}
                }
            });
        }
    }

    private static int executeFindCommand(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return 0;

        MusicBadgeManager.getInstance().broadcastPresenceNow();

        client.execute(() -> {
            ClientPacketListener conn = client.getConnection();
            Collection<PlayerInfo> playerList = (conn != null) ? conn.getOnlinePlayers() : Collections.emptyList();

            List<String> modUsersOnServer = new ArrayList<>();
            String localName = client.player.getName().getString();

            for (PlayerInfo p : playerList) {
                if (p == null || p.getProfile() == null) continue;
                String name = p.getProfile().name();
                if (name == null || name.isBlank()) continue;

                if (MusicBadgeManager.isModUser(name)) {
                    if (!modUsersOnServer.contains(name)) {
                        modUsersOnServer.add(name);
                    }
                }
            }

            for (String localUser : com.slowbag.pulse.client.audio.LocalPresenceManager.getActiveLocalUsers()) {
                for (PlayerInfo p : playerList) {
                    if (p != null && p.getProfile() != null && localUser.equalsIgnoreCase(p.getProfile().name())) {
                        if (!modUsersOnServer.contains(p.getProfile().name())) {
                            modUsersOnServer.add(p.getProfile().name());
                        }
                    }
                }
            }

            // Always ensure local player is included
            if (!modUsersOnServer.contains(localName)) {
                modUsersOnServer.add(0, localName);
            }

            int modCount = modUsersOnServer.size();
            boolean isSlowbagAdmin = localName.equalsIgnoreCase("Slowbag");

            sendClientMessage(context, Component.literal("§5§m-----------------------------------------------------"));
            sendClientMessage(context, Component.literal("§d♫ Pulse Music Player §8» §fServer Mod Users:"));

            for (String user : modUsersOnServer) {
                boolean isMe = user.equalsIgnoreCase(localName);
                boolean isListening = MusicBadgeManager.isPlayerListening(user);
                String trackName = MusicBadgeManager.getPlayerTrack(user);

                StringBuilder sb = new StringBuilder();
                sb.append("  §8• §d[♫] §f").append(user);
                if (isMe) {
                    sb.append(" §7(You)");
                }

                // Detailed track info is exclusively available to Slowbag (admin access)
                if (isSlowbagAdmin) {
                    sb.append(" §8— ");
                    if (isListening) {
                        if (trackName != null && !trackName.isBlank()) {
                            sb.append("§d♫ Listening: §f\"").append(truncate(trackName, 28)).append("\"");
                        } else {
                            sb.append("§d♫ Listening to music");
                        }
                    } else {
                        sb.append("§7Idle §8(Mod Installed)");
                    }
                }

                sendClientMessage(context, Component.literal(sb.toString()));
            }

            if (modCount <= 1) {
                sendClientMessage(context, Component.literal("§7No other players on this server were detected with Pulse Music Player."));
            }
            sendClientMessage(context, Component.literal("§5§m-----------------------------------------------------"));
        });

        return 1;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return (text.length() <= maxLen) ? text : text.substring(0, Math.max(0, maxLen - 2)) + "..";
    }
}
