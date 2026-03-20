package com.example.teamdragonrace;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * TeamDragonRace – Entry point.
 *
 * Two teams race to kill the Ender Dragon while hunting each other.
 * Mechanics covered:
 *   • Team management (/race join/start/stop/info)
 *   • Friendly-fire prevention
 *   • Cross-dimension compass tracking with action-bar fallback
 *   • Death → Spectator with teammate-only spectating restriction
 *   • Revival Heart custom item + shaped recipe
 *   • Win condition: dragon kill or full enemy-team elimination
 */
public final class TeamDragonRace extends JavaPlugin {

    private GameManager gameManager;

    @Override
    public void onEnable() {
        // ── Core manager ──────────────────────────────────────────────────
        gameManager = new GameManager(this);

        // ── Register the Revival Heart shaped recipe ───────────────────────
        gameManager.registerRevivalRecipe();

        // ── Register all event listeners ──────────────────────────────────
        getServer().getPluginManager().registerEvents(
                new GameListener(this, gameManager), this);

        // ── Register /race command + tab-completer ─────────────────────────
        RaceCommand raceCmd = new RaceCommand(this, gameManager);
        //noinspection ConstantConditions
        getCommand("race").setExecutor(raceCmd);
        getCommand("race").setTabCompleter(raceCmd);

        getLogger().info("TeamDragonRace v" + getDescription().getVersion() + " enabled!");
    }

    @Override
    public void onDisable() {
        // Gracefully end any in-progress game so tasks are cancelled
        if (gameManager != null && gameManager.isRunning()) {
            gameManager.stopGame();
        }
        getLogger().info("TeamDragonRace disabled.");
    }

    /** Provides access to the shared GameManager from other classes. */
    public GameManager getGameManager() {
        return gameManager;
    }
}
