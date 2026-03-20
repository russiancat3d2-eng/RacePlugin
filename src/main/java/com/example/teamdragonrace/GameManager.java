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

public class GameManager {

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

    // --- STATIC HELPER METHODS (Fixes the "static context" errors) ---
    public static String teamName(int team) { 
        return team == TEAM_1 ? "Team 1" : "Team 2"; 
    }
    
    public static NamedTextColor teamColor(int team) { 
        return team == TEAM_1 ? NamedTextColor.BLUE : NamedTextColor.RED; 
    }
    
    public static Component msg(String text, NamedTextColor color) {
        return Component.text(text).color(color);
    }

    // --- INSTANCE METHODS ---
    public boolean isRunning() { return gameRunning; }
    public boolean isInGame(Player p) { return playerTeams.containsKey(p.getUniqueId()); }
    public boolean isDead(UUID uuid) { return deadPlayers.contains(uuid); }
    public int getTeam(Player p) { return playerTeams.getOrDefault(p.getUniqueId(), 0); }

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
        if (gameRunning) return;
        playerTeams.put(player.getUniqueId(), team);
        player.sendMessage(msg("You joined " + teamName(team), NamedTextColor.GREEN));
    }

    public void startGame() {
        if (gameRunning) return;
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
        Bukkit.broadcast(msg("★ TEAM DRAGON RACE – START! ★", NamedTextColor.GOLD));
    }

    public void stopGame() {
        gameRunning = false;
        if (compassTask != null) compassTask.cancel();
        if (spectatorGuardTask != null) spectatorGuardTask.cancel();
        playerTeams.clear();
        deadPlayers.clear();
    }

    public void cycleCompassTarget(Player tracker) {
        List<UUID> aliveEnemies = getAliveEnemies(tracker.getUniqueId());
        if (aliveEnemies.isEmpty()) return;
        UUID current = compassTargets.get(tracker.getUniqueId());
        int nextIdx = (aliveEnemies.indexOf(current) + 1) % aliveEnemies.size();
        compassTargets.put(tracker.getUniqueId(), aliveEnemies.get(nextIdx));
        tracker.sendActionBar(msg("Tracking: " + Bukkit.getPlayer(aliveEnemies.get(nextIdx)).getName(), NamedTextColor.YELLOW));
    }

    private void startCompassTask() {
        compassTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<UUID, UUID> entry : compassTargets.entrySet()) {
                Player tracker = Bukkit.getPlayer(entry.getKey());
                Player target = Bukkit.getPlayer(entry.getValue());
                if (tracker != null && target != null) tracker.setCompassTarget(target.getLocation());
            }
        }, 0L, 20L);
    }

    private void startSpectatorGuardTask() {
        spectatorGuardTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : deadPlayers) {
                Player spec = Bukkit.getPlayer(uuid);
                Player nearest = findAliveTeammate(uuid);
                if (spec != null && nearest != null && spec.getWorld().equals(nearest.getWorld())) {
                    if (spec.getLocation().distance(nearest.getLocation()) > SPECTATOR_WARN_DISTANCE) {
                        spec.sendMessage(msg("Too far from team!", NamedTextColor.YELLOW));
                    }
                }
            }
        }, 0L, 100L);
    }

    public void handlePlayerDeath(Player player) {
        deadPlayers.add(player.getUniqueId());
        checkTeamElimination();
    }

    public void postRespawnToSpectator(Player player) {
        if (deadPlayers.contains(player.getUniqueId())) {
            player.setGameMode(GameMode.SPECTATOR);
        }
    }

    public void handlePlayerDisconnect(Player player) {
        deadPlayers.add(player.getUniqueId());
        checkTeamElimination();
    }

    public boolean shouldBlockSpectatorTeleport(Player spectator, Location destination) {
        return false;
    }

    public void useRevivalHeart(Player user, ItemStack heartItem) {
        List<Player> dead = getDeadTeammatesOnline(getTeam(user));
        if (dead.isEmpty()) return;
        Player revived = dead.get(0);
        heartItem.setAmount(heartItem.getAmount() - 1);
        deadPlayers.remove(revived.getUniqueId());
        revived.setGameMode(GameMode.SURVIVAL);
        revived.teleport(user.getLocation());
        
        var attr = revived.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) revived.setHealth(attr.getValue() / 2);
    }

    public void handleDragonKill(Player killer) {
        Bukkit.broadcast(msg(killer.getName() + " killed the Dragon!", NamedTextColor.GOLD));
        stopGame();
    }

    public void checkTeamElimination() {}

    public List<UUID> getTeamPlayers(int team) {
        return playerTeams.entrySet().stream().filter(e -> e.getValue() == team).map(Map.Entry::getKey).collect(Collectors.toList());
    }

    public List<Player> getDeadTeammatesOnline(int team) {
        return getTeamPlayers(team).stream().filter(deadPlayers::contains).map(Bukkit::getPlayer).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public List<UUID> getAliveEnemies(UUID uuid) {
        int enemyTeam = (getTeam(Bukkit.getPlayer(uuid)) == TEAM_1) ? TEAM_2 : TEAM_1;
        return getTeamPlayers(enemyTeam).stream().filter(id -> !deadPlayers.contains(id)).collect(Collectors.toList());
    }

    public Player findAliveTeammate(UUID uuid) {
        return getTeamPlayers(getTeam(Bukkit.getPlayer(uuid))).stream().filter(id -> !deadPlayers.contains(id)).map(Bukkit::getPlayer).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
