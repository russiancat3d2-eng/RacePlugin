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
import org.bukkit.attribute.Attribute;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * GameManager holds all mutable game state and exposes the high-level
 * actions that the listener and command classes call into.
 */
public class GameManager {

    // ── Constants ─────────────────────────────────────────────────────────
    public static final int TEAM_1 = 1;
    public static final int TEAM_2 = 2;
    private static final double SPECTATOR_WARN_DISTANCE = 150.0;

    private final TeamDragonRace plugin;
    private boolean gameRunning = false;
    private final Map<UUID, Integer> playerTeams = new LinkedHashMap<>();
    private final Set<UUID> deadPlayers = new HashSet<>();
    private final Map<UUID, UUID> compassTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> spectatorWarnCooldown = new HashMap<>();
    private BukkitTask compassTask;
    private BukkitTask spectatorGuardTask;
    private final NamespacedKey revivalHeartKey;

    public GameManager(TeamDragonRace plugin) {
        this.plugin = plugin;
        this.revivalHeartKey = new NamespacedKey(plugin, "revival_heart");
    }

    public void registerRevivalRecipe() {
        ItemStack heart = createRevivalHeart();
        ShapedRecipe recipe = new ShapedRecipe(revivalHeartKey, heart);
        recipe.shape("DDD", "DED", "DDD");
        recipe.setIngredient('D', Material.DIAMOND);
        recipe.setIngredient('E', Material.EMERALD_BLOCK);
        plugin.getServer().addRecipe(recipe);
    }

    public ItemStack createRevivalHeart() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.displayName(Component.text("Revival Heart").color(NamedTextColor.RED).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Right-click to revive a fallen teammate.").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Recipe: 8 Diamonds + 1 Emerald Block (centre)").color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(revivalHeartKey, PersistentDataType.BOOLEAN, true);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isRevivalHeart(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(revivalHeartKey, PersistentDataType.BOOLEAN);
    }

    public void joinTeam(Player player, int team) {
        if (gameRunning) {
            player.sendMessage(Component.text("The game is already running!", NamedTextColor.RED));
            return;
        }
        playerTeams.put(player.getUniqueId(), team);
        player.sendMessage(Component.text("You joined ").color(NamedTextColor.GREEN).append(Component.text(teamName(team)).color(teamColor(team))).append(Component.text("!").color(NamedTextColor.GREEN)));
    }

    public void startGame() {
        if (gameRunning) return;
        List<UUID> team1 = getTeamPlayers(TEAM_1);
        List<UUID> team2 = getTeamPlayers(TEAM_2);
        if (team1.isEmpty() || team2.isEmpty()) {
            Bukkit.broadcast(Component.text("Both teams need at least one player to start!", NamedTextColor.RED));
            return;
        }
        gameRunning = true;
        deadPlayers.clear();
        for (UUID uuid : playerTeams.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().addItem(new ItemStack(Material.COMPASS, 1));
            }
        }
        startCompassTask();
        startSpectatorGuardTask();
        Bukkit.broadcast(Component.text("★ TEAM DRAGON RACE – START! ★", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
    }

    public void stopGame() {
        gameRunning = false;
        if (compassTask != null) compassTask.cancel();
        if (spectatorGuardTask != null) spectatorGuardTask.cancel();
        for (UUID uuid : playerTeams.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.setGameMode(GameMode.SURVIVAL);
        }
        playerTeams.clear();
        deadPlayers.clear();
    }

    private void startCompassTask() {
        compassTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<UUID, UUID> entry : compassTargets.entrySet()) {
                Player tracker = Bukkit.getPlayer(entry.getKey());
                Player target = Bukkit.getPlayer(entry.getValue());
                if (tracker != null && target != null) {
                    tracker.setCompassTarget(target.getLocation());
                }
            }
        }, 0L, 20L);
    }

    private void startSpectatorGuardTask() {
        spectatorGuardTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : deadPlayers) {
                Player spec = Bukkit.getPlayer(uuid);
                Player nearest = findAliveTeammate(uuid);
                if (spec != null && nearest != null && spec.getLocation().distance(nearest.getLocation()) > SPECTATOR_WARN_DISTANCE) {
                    spec.sendMessage(Component.text("Stay close to your team!", NamedTextColor.YELLOW));
                }
            }
        }, 0L, 100L);
    }

    public void useRevivalHeart(Player user, ItemStack heartItem) {
        int myTeam = getTeam(user);
        List<Player> deadTeammates = getDeadTeammatesOnline(myTeam);
        if (deadTeammates.isEmpty()) {
            user.sendMessage(Component.text("No one to revive!", NamedTextColor.RED));
            return;
        }
        Player revived = deadTeammates.get(0);
        heartItem.setAmount(heartItem.getAmount() - 1);
        deadPlayers.remove(revived.getUniqueId());
        revived.setGameMode(GameMode.SURVIVAL);
        revived.teleport(user.getLocation());

        // FIXED LINES FOR 1.21.1:
        var attr = revived.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            revived.setHealth(attr.getValue() / 2);
        }

        Bukkit.broadcast(Component.text(revived.getName() + " was revived!", NamedTextColor.GREEN));
    }

    public void handlePlayerDeath(Player player) {
        deadPlayers.add(player.getUniqueId());
        checkTeamElimination();
    }

    public void checkTeamElimination() {
        if (getTeamPlayers(TEAM_1).stream().allMatch(deadPlayers::contains)) {
            Bukkit.broadcast(Component.text("Team 2 Wins!", NamedTextColor.GOLD));
            stopGame();
        } else if (getTeamPlayers(TEAM_2).stream().allMatch(deadPlayers::contains)) {
            Bukkit.broadcast(Component.text("Team 1 Wins!", NamedTextColor.GOLD));
            stopGame();
        }
    }

    public List<UUID> getTeamPlayers(int team) {
        return playerTeams.entrySet().stream().filter(e -> e.getValue() == team).map(Map.Entry::getKey).collect(Collectors.toList());
    }

    public List<Player> getDeadTeammatesOnline(int team) {
        return getTeamPlayers(team).stream().filter(deadPlayers::contains).map(Bukkit::getPlayer).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public Player findAliveTeammate(UUID uuid) {
        return getTeamPlayers(getTeam(Bukkit.getPlayer(uuid))).stream().filter(id -> !deadPlayers.contains(id)).map(Bukkit::getPlayer).filter(Objects::nonNull).findFirst().orElse(null);
    }

    public int getTeam(Player p) { return playerTeams.getOrDefault(p.getUniqueId(), 0); }
    private String teamName(int team) { return team == TEAM_1 ? "Team 1" : "Team 2"; }
    private NamedTextColor teamColor(int team) { return team == TEAM_1 ? NamedTextColor.BLUE : NamedTextColor.RED; }
}
