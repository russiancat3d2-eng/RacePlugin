package com.example.teamdragonrace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.block.Action;

/**
 * GameListener – handles every Bukkit event needed by the plugin.
 *
 * Responsibilities:
 *   1. Friendly-fire prevention
 *   2. Death → Spectator transition (PlayerDeathEvent + PlayerRespawnEvent)
 *   3. Compass right-click cycling
 *   4. Revival Heart right-click usage
 *   5. Ender Dragon death → win condition
 *   6. Spectator teleport restriction (teammate-only)
 *   7. Player disconnect handling
 *   8. Block non-participants from accidentally crafting Revival Hearts
 */
public class GameListener implements Listener {

    private final TeamDragonRace plugin;
    private final GameManager     gm;

    public GameListener(TeamDragonRace plugin, GameManager gm) {
        this.plugin = plugin;
        this.gm     = gm;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  1. FRIENDLY FIRE PREVENTION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Cancels any damage dealt from one team-member to another.
     * Handles both melee and projectile attacks.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!gm.isRunning()) return;

        // ── Resolve the actual attacking player ──────────────────────────
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj
                   && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        // ── Resolve the victim ────────────────────────────────────────────
        if (!(event.getEntity() instanceof Player victim)) return;
        if (attacker == null) return;

        // Both must be in the game
        if (!gm.isInGame(attacker) || !gm.isInGame(victim)) return;

        // Spectators cannot deal damage anyway, but be safe
        if (gm.isDead(attacker.getUniqueId())) return;

        if (gm.getTeam(attacker) == gm.getTeam(victim)) {
            event.setCancelled(true);
            attacker.sendActionBar(GameManager.msg("Friendly fire is disabled!", NamedTextColor.RED));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  2. DEATH → SPECTATOR  (two-event flow)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Step A: When a game-participant dies, register the death in GameManager.
     * We also suppress the default death screen message so it's less confusing,
     * and let death drops remain on the ground for the other team to loot.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        if (!gm.isRunning() || !gm.isInGame(dead)) return;

        // Register in GameManager (sets deadPlayers, notifies team, checks elimination)
        gm.handlePlayerDeath(dead);

        // Keep the vanilla death message so everyone sees it, but override with ours
        event.deathMessage(
            Component.text("☠ ").color(NamedTextColor.DARK_RED)
                .append(Component.text(dead.getName()).color(GameManager.teamColor(gm.getTeam(dead))))
                .append(Component.text(" was eliminated!").color(NamedTextColor.GRAY))
        );
    }

    /**
     * Step B: After the server processes the respawn, switch the player to
     * SPECTATOR mode and teleport them to a surviving teammate.
     *
     * Using a 1-tick delay is required: setting GameMode inside
     * PlayerRespawnEvent is unreliable because the server hasn't finished
     * the respawn sequence yet.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!gm.isRunning() || !gm.isInGame(player)) return;
        if (!gm.isDead(player.getUniqueId())) return;

        // Teleport the respawn point to a teammate's location so they don't
        // appear at world-spawn before we switch modes
        Player teammate = gm.findAliveTeammate(player.getUniqueId());
        if (teammate != null) {
            event.setRespawnLocation(teammate.getLocation());
        }

        // Delay the GameMode switch by 1 tick
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                gm.postRespawnToSpectator(player);
            }
        }, 1L);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  3. COMPASS RIGHT-CLICK CYCLING
    //  4. REVIVAL HEART RIGHT-CLICK USAGE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Handles right-clicks for both the Compass and the Revival Heart.
     *
     * We only process the MAIN_HAND to avoid the event firing twice
     * (once per hand).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only process main hand to avoid double-firing
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player    player = event.getPlayer();
        ItemStack item   = event.getItem();
        if (item == null) return;

        Action action = event.getAction();
        boolean isRightClick = (action == Action.RIGHT_CLICK_AIR
                             || action == Action.RIGHT_CLICK_BLOCK);
        if (!isRightClick) return;

        // ── Compass: cycle tracking target ────────────────────────────────
        if (item.getType() == Material.COMPASS) {
            if (!gm.isRunning() || !gm.isInGame(player)) return;
            if (gm.isDead(player.getUniqueId())) return;

            event.setCancelled(true);   // prevent lodestone compass UI from opening
            gm.cycleCompassTarget(player);
            return;
        }

        // ── Revival Heart: revive a dead teammate ─────────────────────────
        if (gm.isRevivalHeart(item)) {
            event.setCancelled(true);   // never let the totem interact normally

            if (!gm.isRunning()) {
                player.sendMessage(GameManager.msg("No game is currently running.", NamedTextColor.RED));
                return;
            }
            if (!gm.isInGame(player)) {
                player.sendMessage(GameManager.msg("You are not part of this game.", NamedTextColor.RED));
                return;
            }
            if (gm.isDead(player.getUniqueId())) {
                player.sendMessage(GameManager.msg("You cannot use items while spectating.", NamedTextColor.RED));
                return;
            }

            gm.useRevivalHeart(player, item);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  5. ENDER DRAGON DEATH → WIN CONDITION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Detects the Ender Dragon's death and attributes the kill to a player.
     *
     * {@link LivingEntity#getKiller()} returns the player who dealt the
     * killing blow; if it is null (e.g. dragon died to environment), we fall
     * back to scanning for any in-game player in The End.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!gm.isRunning()) return;
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;

        Player killer = dragon.getKiller();

        if (killer != null && gm.isInGame(killer)) {
            gm.handleDragonKill(killer);
            return;
        }

        // Fallback: find the first in-game player in The End to credit
        for (Player p : dragon.getWorld().getPlayers()) {
            if (gm.isInGame(p) && !gm.isDead(p.getUniqueId())) {
                gm.handleDragonKill(p);
                return;
            }
        }

        // Nobody in The End – announce but don't award a win
        Bukkit.broadcast(
            Component.text("The Ender Dragon has been slain, but no one from the race was there to claim the kill!")
                .color(NamedTextColor.GOLD)
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    //  6. SPECTATOR TELEPORT RESTRICTION (teammate-only spectating)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * When a spectator left-clicks to "enter" a player's view, Minecraft
     * fires a {@link PlayerTeleportEvent} with cause {@code SPECTATE}.
     *
     * We cancel the event if the destination player belongs to the enemy team,
     * enforcing the teammate-only spectating rule.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectatorTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        if (!gm.isRunning()) return;

        Player player = event.getPlayer();
        if (!gm.isInGame(player) || !gm.isDead(player.getUniqueId())) return;

        if (event.getTo() == null) return;

        if (gm.shouldBlockSpectatorTeleport(player, event.getTo())) {
            event.setCancelled(true);
            player.sendActionBar(
                GameManager.msg("You may only spectate your own teammates!", NamedTextColor.RED)
            );
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  7. PLAYER DISCONNECT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Marks a disconnecting player as dead so they don't block win-condition
     * checks.  This can trigger team-elimination if they were the last alive.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!gm.isRunning() || !gm.isInGame(player)) return;
        gm.handlePlayerDisconnect(player);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  8. MISCELLANEOUS SAFEGUARDS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Prevents dead (spectating) players from picking up items off the ground.
     */
    @EventHandler(ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!gm.isRunning() || !gm.isInGame(player)) return;
        if (gm.isDead(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents dead players from dropping items (in the rare case GameMode
     * is still transitioning).
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!gm.isRunning() || !gm.isInGame(player)) return;
        if (gm.isDead(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Shows a "Revival Heart crafted!" message so players know they succeeded.
     */
    @EventHandler
    public void onCraftRevivalHeart(CraftItemEvent event) {
        if (!gm.isRevivalHeart(event.getCurrentItem())) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        player.sendMessage(
            Component.text("You crafted a ").color(NamedTextColor.GREEN)
                .append(Component.text("Revival Heart").color(NamedTextColor.RED)
                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                .append(Component.text("!").color(NamedTextColor.GREEN))
        );
    }
}
