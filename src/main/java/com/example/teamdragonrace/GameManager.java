package com.example.teamdragonrace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * GameManager holds all mutable game state and exposes the high-level
 * actions that the listener and command classes call into.
 *
 * State lifecycle:
 *   LOBBY  →  (startGame)  →  RUNNING  →  (stopGame / win)  →  LOBBY
 */
public class GameManager {

    // ── Constants ─────────────────────────────────────────────────────────
    public static final int TEAM_1 = 1;
    public static final int TEAM_2 = 2;

    /** Distance (in blocks) at which spectators receive a "too far" warning. */
    private static final double SPECTATOR_WARN_DISTANCE = 150.0;

    // ── Plugin reference ──────────────────────────────────────────────────
    private final TeamDragonRace plugin;

    // ── Game state ────────────────────────────────────────────────────────
    private boolean gameRunning = false;

    /**
     * Maps each player UUID → team number (TEAM_1 or TEAM_2).
     * Populated during lobby; cleared when the game stops.
     */
    private final Map<UUID, Integer> playerTeams = new LinkedHashMap<>();

    /**
     * UUIDs of players who have died this game and are now spectating.
     */
    private final Set<UUID> deadPlayers = new HashSet<>();

    /**
     * Maps each tracker UUID → the UUID of the enemy they are currently
     * watching with their compass.
     */
    private final Map<UUID, UUID> compassTargets = new ConcurrentHashMap<>();

    /**
     * Cooldown map used to throttle the "you are too far" spectator warning.
     * Maps UUID → System.currentTimeMillis() of last warning.
     */
    private final Map<UUID, Long> spectatorWarnCooldown = new HashMap<>();

    // ── Background tasks ──────────────────────────────────────────────────
    private BukkitTask compassTask;
    private BukkitTask spectatorGuardTask;

    // ── Namespaced keys ───────────────────────────────────────────────────
    /** PersistentDataContainer key that marks an item as a Revival Heart. */
    private final NamespacedKey revivalHeartKey;

    // ─────────────────────────────────────────────────────────────────────
    public GameManager(TeamDragonRace plugin) {
        this.plugin = plugin;
        this.revivalHeartKey = new NamespacedKey(plugin, "revival_heart");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  RECIPE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Registers the shaped recipe for the Revival Heart.
     *
     * Layout (D = Diamond, E = Emerald Block):
     *   D D D
     *   D E D
     *   D D D
     */
    public void registerRevivalRecipe() {
        ItemStack heart = createRevivalHeart();
        ShapedRecipe recipe = new ShapedRecipe(revivalHeartKey, heart);
        recipe.shape("DDD", "DED", "DDD");
        recipe.setIngredient('D', Material.DIAMOND);
        recipe.setIngredient('E', Material.EMERALD_BLOCK);
        plugin.getServer().addRecipe(recipe);
        plugin.getLogger().info("Revival Heart recipe registered.");
    }

    /**
     * Builds a Revival Heart ItemStack.
     * Uses TOTEM_OF_UNDYING with:
     *   • Enchantment-glint override (no real enchant needed)
     *   • Bold red custom display name
     *   • PersistentDataContainer tag for reliable identification
     */
    public ItemStack createRevivalHeart() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());

        // Custom display name – Adventure API (italic disabled so it looks clean)
        meta.displayName(
            Component.text("Revival Heart")
                .color(NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false)
        );

        // Lore
        meta.lore(List.of(
            Component.text("Right-click to revive a fallen teammate.")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Recipe: 8 Diamonds + 1 Emerald Block (centre)")
                .color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));

        // Enchantment glint override (Paper 1.20.5+ / 1.21 API)
        meta.setEnchantmentGlintOverride(true);

        // PersistentDataContainer tag – the reliable identification mechanism
        meta.getPersistentDataContainer().set(
            revivalHeartKey,
            PersistentDataType.BOOLEAN,
            true
        );

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Returns {@code true} if the given ItemStack is a Revival Heart
     * (identified by its PersistentDataContainer tag).
     */
    public boolean isRevivalHeart(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(revivalHeartKey, PersistentDataType.BOOLEAN);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LOBBY  –  joining a team before the game starts
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Assigns a player to the given team.
     * A player may switch teams freely before the game starts.
     */
    public void joinTeam(Player player, int team) {
        if (gameRunning) {
            player.sendMessage(msg("The game is already running!", NamedTextColor.RED));
            return;
        }

        playerTeams.put(player.getUniqueId(), team);
        NamedTextColor teamColor = teamColor(team);
        String teamName = teamName(team);

        player.sendMessage(
            Component.text("You joined ").color(NamedTextColor.GREEN)
                .append(Component.text(teamName).color(teamColor))
                .append(Component.text("!").color(NamedTextColor.GREEN))
        );

        // Inform other players in that team
        broadcastToTeam(team,
            Component.text(player.getName()).color(NamedTextColor.WHITE)
                .append(msg(" has joined your team.", NamedTextColor.GREEN)),
            player
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GAME START / STOP
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Starts the game.  Validates that both teams have at least one player,
     * gives compasses, and starts the background tasks.
     */
    public void startGame() {
        if (gameRunning) {
            Bukkit.broadcast(msg("The game is already running!", NamedTextColor.RED));
            return;
        }

        List<UUID> team1 = getTeamPlayers(TEAM_1);
        List<UUID> team2 = getTeamPlayers(TEAM_2);

        if (team1.isEmpty() || team2.isEmpty()) {
            Bukkit.broadcast(msg("Both teams need at least one player to start!", NamedTextColor.RED));
            return;
        }

        // ── Initialise state ──────────────────────────────────────────────
        gameRunning = true;
        deadPlayers.clear();
        compassTargets.clear();
        spectatorWarnCooldown.clear();

        // ── Set up each player ────────────────────────────────────────────
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().addItem(new ItemStack(Material.COMPASS, 1));

            // Assign the first living enemy as the initial compass target
            List<UUID> enemies = getAliveEnemies(uuid);
            if (!enemies.isEmpty()) {
                compassTargets.put(uuid, enemies.get(0));
            }
        }

        // ── Broadcast start ───────────────────────────────────────────────
        Bukkit.broadcast(
            Component.text("══════════════════════════════").color(NamedTextColor.GOLD)
        );
        Bukkit.broadcast(
            Component.text(" ★  TEAM DRAGON RACE  –  START!  ★").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
        );
        Bukkit.broadcast(
            Component.text("  Kill the Ender Dragon or wipe out the enemy team!").color(NamedTextColor.YELLOW)
        );
        Bukkit.broadcast(
            Component.text("  Right-click your compass to cycle enemy targets.").color(NamedTextColor.AQUA)
        );
        Bukkit.broadcast(
            Component.text("══════════════════════════════").color(NamedTextColor.GOLD)
        );

        // ── Start background tasks ────────────────────────────────────────
        startCompassTask();
        startSpectatorGuardTask();
    }

    /**
     * Stops the game, cancels all tasks, and restores players to SURVIVAL.
     * Safe to call even if no game is running.
     */
    public void stopGame() {
        if (!gameRunning) return;
        gameRunning = false;

        cancelTasks();

        // Restore spectating players to survival so they aren't stuck
        for (UUID uuid : playerTeams.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline() && p.getGameMode() == GameMode.SPECTATOR) {
                p.setGameMode(GameMode.SURVIVAL);
            }
        }

        playerTeams.clear();
        deadPlayers.clear();
        compassTargets.clear();
        spectatorWarnCooldown.clear();

        Bukkit.broadcast(msg("═══  Team Dragon Race has been stopped.  ═══", NamedTextColor.RED));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  COMPASS TRACKING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Cycles the given player's compass target to the next living enemy.
     * Sends an Action Bar confirmation message.
     */
    public void cycleCompassTarget(Player tracker) {
        UUID trackerUUID = tracker.getUniqueId();
        if (!playerTeams.containsKey(trackerUUID)) return;

        List<UUID> aliveEnemies = getAliveEnemies(trackerUUID);

        if (aliveEnemies.isEmpty()) {
            tracker.sendActionBar(msg("No living enemies to track!", NamedTextColor.RED));
            compassTargets.remove(trackerUUID);
            return;
        }

        // Find the current index and advance by 1 (wraps around)
        UUID current = compassTargets.get(trackerUUID);
        int currentIdx = aliveEnemies.indexOf(current);
        int nextIdx    = (currentIdx + 1) % aliveEnemies.size();
        UUID nextUUID  = aliveEnemies.get(nextIdx);

        compassTargets.put(trackerUUID, nextUUID);

        Player target = Bukkit.getPlayer(nextUUID);
        String name = (target != null) ? target.getName() : "(offline)";

        tracker.sendActionBar(
            Component.text("Now tracking: ").color(NamedTextColor.YELLOW)
                .append(Component.text(name).color(NamedTextColor.WHITE)
                    .decorate(TextDecoration.BOLD))
        );
    }

    /**
     * Background task: updates every player's compass needle every 10 ticks.
     *
     * Cross-dimension strategy:
     *   • Same world        → setCompassTarget(target location) directly.
     *   • Overworld ↔ Nether → project coordinates using the 8:1 scale ratio.
     *   • Involving The End  → point to world spawn (no sensible projection).
     *
     * For dimensions where the native compass is unreliable we also send a
     * continuous Action Bar showing a directional arrow + distance.
     */
    private void startCompassTask() {
        compassTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Iterate over a snapshot to avoid ConcurrentModificationException
            for (Map.Entry<UUID, UUID> entry : new HashMap<>(compassTargets).entrySet()) {
                UUID trackerUUID = entry.getKey();
                UUID targetUUID  = entry.getValue();

                if (deadPlayers.contains(trackerUUID)) continue;

                Player tracker = Bukkit.getPlayer(trackerUUID);
                Player target  = Bukkit.getPlayer(targetUUID);

                if (tracker == null || !tracker.isOnline()) continue;

                // Target went offline or died → auto-cycle
                if (target == null || !target.isOnline() || deadPlayers.contains(targetUUID)) {
                    cycleCompassTarget(tracker);
                    continue;
                }

                updateCompassForPlayer(tracker, target);
            }
        }, 0L, 10L);  // every 10 ticks (~0.5 s)
    }

    /**
     * Core compass-update logic for one tracker→target pair.
     */
    private void updateCompassForPlayer(Player tracker, Player target) {
        World trackerWorld = tracker.getWorld();
        World targetWorld  = target.getWorld();
        World.Environment trkEnv = trackerWorld.getEnvironment();
        World.Environment tgtEnv = targetWorld.getEnvironment();
        boolean sameWorld = trackerWorld.equals(targetWorld);

        // ── Compute the projected compass location ────────────────────────
        Location compassLoc;
        boolean needsActionBar;  // True when native compass is unhelpful

        if (sameWorld) {
            compassLoc    = target.getLocation().clone();
            needsActionBar = (trkEnv != World.Environment.NORMAL);
        } else if (trkEnv == World.Environment.NETHER && tgtEnv == World.Environment.NORMAL) {
            // Nether coord = overworld / 8
            double x = target.getLocation().getX() / 8.0;
            double z = target.getLocation().getZ() / 8.0;
            compassLoc    = new Location(trackerWorld, x, tracker.getLocation().getY(), z);
            needsActionBar = true; // cross-dim always needs bar
        } else if (trkEnv == World.Environment.NORMAL && tgtEnv == World.Environment.NETHER) {
            // Overworld coord = nether * 8
            double x = target.getLocation().getX() * 8.0;
            double z = target.getLocation().getZ() * 8.0;
            compassLoc    = new Location(trackerWorld, x, tracker.getLocation().getY(), z);
            needsActionBar = true;
        } else {
            // End involved, or other unknown pairing – point to world spawn as fallback
            compassLoc    = trackerWorld.getSpawnLocation();
            needsActionBar = true;
        }

        tracker.setCompassTarget(compassLoc);

        // ── Action Bar (shown whenever native compass is unreliable) ──────
        if (needsActionBar) {
            String arrow    = getDirectionArrow(tracker.getLocation(), compassLoc);
            String distStr  = buildDistanceString(tracker, target, sameWorld);
            String dimLabel = sameWorld ? "" : "  §7[" + envName(tgtEnv) + "]";

            tracker.sendActionBar(
                Component.text(arrow + "  Tracking: ").color(NamedTextColor.YELLOW)
                    .append(Component.text(target.getName()).color(NamedTextColor.WHITE))
                    .append(Component.text("  |  " + distStr + dimLabel).color(NamedTextColor.AQUA))
            );
        }
    }

    /**
     * Returns a Unicode directional arrow relative to the tracker's
     * current yaw heading.
     */
    private String getDirectionArrow(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        // atan2 with (dx, -dz) gives a Minecraft-style bearing: 0 = north, +ve clockwise
        double bearingToTarget = Math.toDegrees(Math.atan2(dx, -dz));
        // Player yaw: -180..180, north=−180|+180, east=−90, south=0, west=90
        double playerYaw = from.getYaw();
        // Relative angle clamped to [0, 360)
        double rel = ((bearingToTarget - playerYaw) % 360 + 360) % 360;
        // Snap to 8 directions
        String[] arrows = { "↑", "↗", "→", "↘", "↓", "↙", "←", "↖" };
        int idx = (int) Math.round(rel / 45.0) % 8;
        return arrows[idx];
    }

    private String buildDistanceString(Player tracker, Player target, boolean sameWorld) {
        if (!sameWorld) return "??m";
        double dist = tracker.getLocation().distance(target.getLocation());
        return dist < 1000 ? String.format("%.0fm", dist) : String.format("%.1fkm", dist / 1000.0);
    }

    private String envName(World.Environment env) {
        return switch (env) {
            case NETHER  -> "Nether";
            case THE_END -> "The End";
            default      -> "Overworld";
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEATH & SPECTATING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called by the listener when a game-participant dies.
     * Marks the player as dead and schedules the transition to spectator.
     * The actual GameMode switch is done in {@link GameListener#onPlayerRespawn}
     * after the server has processed the respawn event.
     */
    public void handlePlayerDeath(Player player) {
        UUID uuid = player.getUniqueId();
        if (!gameRunning || !playerTeams.containsKey(uuid)) return;

        deadPlayers.add(uuid);

        // Notify the team
        int myTeam = playerTeams.get(uuid);
        broadcastToTeam(myTeam,
            Component.text(player.getName()).color(NamedTextColor.RED).decorate(TextDecoration.BOLD)
                .append(Component.text(" has fallen!  Use a Revival Heart to bring them back.").color(NamedTextColor.GRAY))
        );

        // Check if the whole team was just wiped out
        checkTeamElimination();
    }

    /**
     * Called after a dead player has been respawned by the server.
     * Sets their GameMode to SPECTATOR and teleports them to a living teammate.
     */
    public void postRespawnToSpectator(Player player) {
        if (!gameRunning || !deadPlayers.contains(player.getUniqueId())) return;

        player.setGameMode(GameMode.SPECTATOR);

        // Teleport to a surviving teammate (best-effort)
        Player target = findAliveTeammate(player.getUniqueId());
        if (target != null) {
            player.teleport(target.getLocation());
        }

        player.sendMessage(
            Component.text("You are now spectating. ").color(NamedTextColor.GRAY)
                .append(Component.text("Ask a teammate to craft and use a ").color(NamedTextColor.WHITE))
                .append(Component.text("Revival Heart").color(NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .append(Component.text(" to respawn you.").color(NamedTextColor.WHITE))
        );
    }

    /**
     * Handles a player disconnecting mid-game: marks them as dead and checks
     * for team elimination.
     */
    public void handlePlayerDisconnect(Player player) {
        UUID uuid = player.getUniqueId();
        if (!gameRunning || !playerTeams.containsKey(uuid)) return;

        if (!deadPlayers.contains(uuid)) {
            deadPlayers.add(uuid);
            int myTeam = playerTeams.get(uuid);
            broadcastToTeam(myTeam,
                msg(player.getName() + " disconnected and is out of the game.", NamedTextColor.DARK_GRAY)
            );
            checkTeamElimination();
        }
    }

    /**
     * Periodic guard task: every 5 seconds checks whether any spectator has
     * drifted more than {@value #SPECTATOR_WARN_DISTANCE} blocks from their
     * nearest living teammate and warns them (once per 30 s).
     */
    private void startSpectatorGuardTask() {
        spectatorGuardTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : new HashSet<>(deadPlayers)) {
                Player spec = Bukkit.getPlayer(uuid);
                if (spec == null || !spec.isOnline()) continue;

                Player nearest = findAliveTeammate(uuid);
                if (nearest == null) continue;  // whole team dead – no one to be near

                // Only compare if they're in the same world
                if (!spec.getWorld().equals(nearest.getWorld())) continue;

                double dist = spec.getLocation().distance(nearest.getLocation());
                if (dist > SPECTATOR_WARN_DISTANCE) {
                    long now    = System.currentTimeMillis();
                    long lastWarn = spectatorWarnCooldown.getOrDefault(uuid, 0L);

                    if (now - lastWarn >= 30_000L) {  // warn at most once per 30 s
                        spec.sendMessage(
                            msg("You are too far from your team! Stay close to your surviving teammates.", NamedTextColor.YELLOW)
                        );
                        spectatorWarnCooldown.put(uuid, now);
                    }
                }
            }
        }, 100L, 100L);  // every 5 s
    }

    // ══════════════════════════════════════════════════════════════════════
    //  REVIVAL HEART USAGE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Processes a Revival Heart right-click.
     *
     * Outcomes:
     *   • No dead teammate → cancel + explain (item is NOT consumed).
     *   • Has dead teammate → consume 1 heart, revive a random dead teammate.
     */
    public void useRevivalHeart(Player user, ItemStack heartItem) {
        UUID userUUID = user.getUniqueId();
        if (!gameRunning || !playerTeams.containsKey(userUUID)) {
            user.sendMessage(msg("No game is running.", NamedTextColor.RED));
            return;
        }

        int myTeam = playerTeams.get(userUUID);
        List<Player> deadTeammates = getDeadTeammatesOnline(myTeam);

        // ── No one to revive ──────────────────────────────────────────────
        if (deadTeammates.isEmpty()) {
            user.sendMessage(msg("None of your teammates are dead!", NamedTextColor.RED));
            // Do NOT consume the item
            return;
        }

        // ── Pick a random dead teammate ───────────────────────────────────
        Player revived = deadTeammates.get(new Random().nextInt(deadTeammates.size()));

        // ── Consume one heart from the user's hand ────────────────────────
        if (heartItem.getAmount() > 1) {
            heartItem.setAmount(heartItem.getAmount() - 1);
        } else {
            // Remove the item from whichever hand holds it
            if (user.getInventory().getItemInMainHand().equals(heartItem)) {
                user.getInventory().setItemInMainHand(null);
            } else {
                user.getInventory().setItemInOffHand(null);
            }
        }

        // ── Revive the chosen player ──────────────────────────────────────
        deadPlayers.remove(revived.getUniqueId());
        revived.setGameMode(GameMode.SURVIVAL);
        revived.teleport(user.getLocation());

        // Give partial health & hunger on revival (not a full reset – makes
        // them vulnerable so it's not an instant get-out-of-death-free card).
        // Uses MAX_HEALTH (renamed from GENERIC_MAX_HEALTH in Paper 1.21.4+).
        double maxHp = Objects.requireNonNull(
                revived.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)
        ).getValue();
        revived.setHealth(Math.min(10.0, maxHp));
        revived.setFoodLevel(10);
        revived.setSaturation(0f);

        // Re-assign compass and initial target
        revived.getInventory().addItem(new ItemStack(Material.COMPASS, 1));
        List<UUID> aliveEnemies = getAliveEnemies(revived.getUniqueId());
        if (!aliveEnemies.isEmpty()) {
            compassTargets.put(revived.getUniqueId(), aliveEnemies.get(0));
        }

        // ── Broadcast ─────────────────────────────────────────────────────
        Component reviveMsg =
            Component.text("[Revival] ").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .append(Component.text(user.getName()).color(NamedTextColor.WHITE))
                .append(Component.text(" has revived ").color(NamedTextColor.GREEN))
                .append(Component.text(revived.getName()).color(NamedTextColor.WHITE))
                .append(Component.text("!").color(NamedTextColor.GREEN));

        broadcastToTeam(myTeam, reviveMsg);

        revived.sendMessage(
            Component.text("You have been revived by ").color(NamedTextColor.GREEN)
                .append(Component.text(user.getName()).color(NamedTextColor.WHITE))
                .append(Component.text("!  Stay alive!").color(NamedTextColor.GREEN))
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    //  WIN CONDITIONS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called when an EnderDragon is killed by a player who is in the game.
     * The team of that player wins immediately.
     */
    public void handleDragonKill(Player killer) {
        if (!gameRunning || !playerTeams.containsKey(killer.getUniqueId())) return;

        int winningTeam = playerTeams.get(killer.getUniqueId());
        announceWin(winningTeam,
            killer.getName() + " slew the Ender Dragon for their team!"
        );
        stopGame();
    }

    /**
     * Checks whether one team has been completely eliminated (all members
     * dead or offline).  If so, the surviving team wins.
     */
    public void checkTeamElimination() {
        if (!gameRunning) return;

        boolean team1Dead = isTeamEliminated(TEAM_1);
        boolean team2Dead = isTeamEliminated(TEAM_2);

        if (team1Dead && team2Dead) {
            // Edge case: simultaneous wipe (e.g., explosion)
            Bukkit.broadcast(
                Component.text("Both teams have been eliminated – the game is a DRAW!")
                    .color(NamedTextColor.GOLD)
            );
            stopGame();
        } else if (team1Dead) {
            announceWin(TEAM_2, "Team 1 has been completely eliminated!");
            stopGame();
        } else if (team2Dead) {
            announceWin(TEAM_1, "Team 2 has been completely eliminated!");
            stopGame();
        }
    }

    private boolean isTeamEliminated(int team) {
        List<UUID> members = getTeamPlayers(team);
        if (members.isEmpty()) return false;   // team never had anyone – not eliminated
        return members.stream().allMatch(uuid ->
            deadPlayers.contains(uuid) || Bukkit.getPlayer(uuid) == null
        );
    }

    private void announceWin(int winningTeam, String reason) {
        NamedTextColor color = teamColor(winningTeam);
        String name          = teamName(winningTeam);

        Bukkit.broadcast(Component.text("══════════════════════════════").color(NamedTextColor.GOLD));
        Bukkit.broadcast(
            Component.text(" ★  " + name.toUpperCase() + " WINS!  ★")
                .color(color).decorate(TextDecoration.BOLD)
        );
        Bukkit.broadcast(Component.text("  " + reason).color(NamedTextColor.YELLOW));
        Bukkit.broadcast(Component.text("══════════════════════════════").color(NamedTextColor.GOLD));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SPECTATOR TELEPORT VALIDATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if a spectator teleport to {@code destination}
     * should be blocked because the closest player there is an enemy.
     * Used by the listener to enforce teammate-only spectating.
     */
    public boolean shouldBlockSpectatorTeleport(Player spectator, Location destination) {
        int spectatorTeam = playerTeams.getOrDefault(spectator.getUniqueId(), 0);
        if (spectatorTeam == 0) return false;  // not in game, don't interfere

        Player closest = null;
        double minDistSq = 25.0;  // within 5 blocks of the destination

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(spectator)) continue;
            if (!other.getWorld().equals(destination.getWorld())) continue;

            double distSq = other.getLocation().distanceSquared(destination);
            if (distSq < minDistSq) {
                minDistSq = distSq;
                closest   = other;
            }
        }

        if (closest == null) return false; // no one there

        int closestTeam = playerTeams.getOrDefault(closest.getUniqueId(), 0);
        // Block if the closest player belongs to a different team (or isn't in the game)
        return closestTeam != spectatorTeam;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPER / UTILITY METHODS
    // ══════════════════════════════════════════════════════════════════════

    /** Returns all UUIDs registered to the given team. */
    public List<UUID> getTeamPlayers(int team) {
        return playerTeams.entrySet().stream()
            .filter(e -> e.getValue() == team)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Returns the UUIDs of living enemies of {@code playerUUID}.
     * "Alive" = not in {@code deadPlayers} and currently online.
     */
    public List<UUID> getAliveEnemies(UUID playerUUID) {
        int myTeam = playerTeams.getOrDefault(playerUUID, 0);
        if (myTeam == 0) return List.of();
        int enemyTeam = (myTeam == TEAM_1) ? TEAM_2 : TEAM_1;

        return getTeamPlayers(enemyTeam).stream()
            .filter(uuid -> !deadPlayers.contains(uuid))
            .filter(uuid -> Bukkit.getPlayer(uuid) != null)
            .collect(Collectors.toList());
    }

    /** Returns all dead (spectating) online members of {@code team}. */
    public List<Player> getDeadTeammatesOnline(int team) {
        return getTeamPlayers(team).stream()
            .filter(deadPlayers::contains)
            .map(Bukkit::getPlayer)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Finds the first living, online teammate of {@code playerUUID}.
     * Returns {@code null} if none exists.
     */
    public Player findAliveTeammate(UUID playerUUID) {
        int myTeam = playerTeams.getOrDefault(playerUUID, 0);
        if (myTeam == 0) return null;

        for (UUID uuid : getTeamPlayers(myTeam)) {
            if (uuid.equals(playerUUID)) continue;
            if (deadPlayers.contains(uuid)) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) return p;
        }
        return null;
    }

    /**
     * Broadcasts a message to all online members of {@code team},
     * optionally excluding {@code except}.
     */
    public void broadcastToTeam(int team, Component message, Player... except) {
        Set<Player> excluded = except == null ? Set.of() : Set.of(except);
        for (UUID uuid : getTeamPlayers(team)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline() && !excluded.contains(p)) {
                p.sendMessage(message);
            }
        }
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public boolean isRunning()                  { return gameRunning; }

    public boolean isInGame(Player p)           { return playerTeams.containsKey(p.getUniqueId()); }

    public int getTeam(Player p)                { return playerTeams.getOrDefault(p.getUniqueId(), 0); }

    public boolean isDead(UUID uuid)            { return deadPlayers.contains(uuid); }

    /** Exposed for the CompassTracker runnable (already inlined above). */
    public Map<UUID, UUID> getCompassTargets()  { return compassTargets; }

    public Map<UUID, Integer> getPlayerTeams()  { return Collections.unmodifiableMap(playerTeams); }

    // ── Formatting helpers ─────────────────────────────────────────────────

    public static NamedTextColor teamColor(int team) {
        return (team == TEAM_1) ? NamedTextColor.BLUE : NamedTextColor.RED;
    }

    public static String teamName(int team) {
        return (team == TEAM_1) ? "Team Blue" : "Team Red";
    }

    /** Quick one-colour Component factory. */
    public static Component msg(String text, NamedTextColor color) {
        return Component.text(text).color(color);
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void cancelTasks() {
        if (compassTask != null)        { compassTask.cancel();        compassTask = null; }
        if (spectatorGuardTask != null) { spectatorGuardTask.cancel(); spectatorGuardTask = null; }
    }
}
