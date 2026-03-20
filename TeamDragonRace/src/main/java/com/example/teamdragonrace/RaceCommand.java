package com.example.teamdragonrace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * RaceCommand – handles the single {@code /race} command tree.
 *
 * Sub-commands:
 *   /race join <team1|team2>   – join a team (any player)
 *   /race start                – start the game (op / admin)
 *   /race stop                 – stop the game  (op / admin)
 *   /race info                 – show team rosters and status
 *   /race give heart           – admin shortcut: gives a Revival Heart
 */
public class RaceCommand implements CommandExecutor, TabCompleter {

    private final TeamDragonRace plugin;
    private final GameManager     gm;

    public RaceCommand(TeamDragonRace plugin, GameManager gm) {
        this.plugin = plugin;
        this.gm     = gm;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  COMMAND EXECUTION
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            // ── /race join <team1|team2> ───────────────────────────────────
            case "join" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can join a team.");
                    return true;
                }
                if (!player.hasPermission("teamdragonrace.use")) {
                    player.sendMessage(GameManager.msg("You do not have permission to join.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(GameManager.msg("Usage: /race join <team1|team2>", NamedTextColor.RED));
                    return true;
                }
                int team = parseTeam(args[1]);
                if (team == 0) {
                    player.sendMessage(GameManager.msg("Unknown team '" + args[1] + "'. Use team1 or team2.", NamedTextColor.RED));
                    return true;
                }
                gm.joinTeam(player, team);
            }

            // ── /race start ───────────────────────────────────────────────
            case "start" -> {
                if (!sender.hasPermission("teamdragonrace.admin")) {
                    sender.sendMessage(GameManager.msg("You need the teamdragonrace.admin permission.", NamedTextColor.RED));
                    return true;
                }
                gm.startGame();
            }

            // ── /race stop ────────────────────────────────────────────────
            case "stop" -> {
                if (!sender.hasPermission("teamdragonrace.admin")) {
                    sender.sendMessage(GameManager.msg("You need the teamdragonrace.admin permission.", NamedTextColor.RED));
                    return true;
                }
                if (!gm.isRunning()) {
                    sender.sendMessage(GameManager.msg("No game is currently running.", NamedTextColor.YELLOW));
                    return true;
                }
                gm.stopGame();
            }

            // ── /race info ────────────────────────────────────────────────
            case "info" -> sendInfo(sender);

            // ── /race give heart [player] ─────────────────────────────────
            case "give" -> {
                if (!sender.hasPermission("teamdragonrace.admin")) {
                    sender.sendMessage(GameManager.msg("You need the teamdragonrace.admin permission.", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("heart")) {
                    // Optionally target another player
                    Player target = (args.length >= 3)
                        ? Bukkit.getPlayerExact(args[2])
                        : (sender instanceof Player p ? p : null);

                    if (target == null) {
                        sender.sendMessage(GameManager.msg("Player not found or you must be in-game.", NamedTextColor.RED));
                        return true;
                    }
                    target.getInventory().addItem(gm.createRevivalHeart());
                    target.sendMessage(GameManager.msg("An admin gave you a Revival Heart!", NamedTextColor.GREEN));
                    sender.sendMessage(GameManager.msg("Gave Revival Heart to " + target.getName() + ".", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(GameManager.msg("Usage: /race give heart [player]", NamedTextColor.RED));
                }
            }

            default -> sendHelp(sender);
        }

        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TAB COMPLETION
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("join", "info"));
            if (sender.hasPermission("teamdragonrace.admin")) {
                subs.addAll(List.of("start", "stop", "give"));
            }
            return filterPrefix(subs, args[0]);
        }

        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "join" -> filterPrefix(List.of("team1", "team2"), args[1]);
                case "give" -> filterPrefix(List.of("heart"), args[1]);
                default     -> List.of();
            };
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")
                              && args[1].equalsIgnoreCase("heart")) {
            // Suggest online player names
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filterPrefix(names, args[2]);
        }

        return List.of();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("══ Team Dragon Race ══").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        helpLine(sender, "/race join <team1|team2>", "Join a team before the game starts");
        helpLine(sender, "/race info",               "Show team rosters and status");
        if (sender.hasPermission("teamdragonrace.admin")) {
            helpLine(sender, "/race start",              "Start the game");
            helpLine(sender, "/race stop",               "Stop the game");
            helpLine(sender, "/race give heart [player]","Give a Revival Heart");
        }
    }

    private void helpLine(CommandSender sender, String command, String description) {
        sender.sendMessage(
            Component.text("  " + command).color(NamedTextColor.YELLOW)
                .append(Component.text(" – " + description).color(NamedTextColor.GRAY))
        );
    }

    /** Prints current team rosters, alive/dead status, and game state. */
    private void sendInfo(CommandSender sender) {
        sender.sendMessage(Component.text("══ Game Status ══").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));

        sender.sendMessage(
            Component.text("  Running: ").color(NamedTextColor.YELLOW)
                .append(gm.isRunning()
                    ? Component.text("YES").color(NamedTextColor.GREEN)
                    : Component.text("NO" ).color(NamedTextColor.RED))
        );

        for (int team : new int[]{GameManager.TEAM_1, GameManager.TEAM_2}) {
            List<UUID> members = gm.getTeamPlayers(team);
            StringBuilder sb = new StringBuilder();
            for (UUID uuid : members) {
                Player p    = Bukkit.getPlayer(uuid);
                String name = (p != null) ? p.getName() : Bukkit.getOfflinePlayer(uuid).getName();
                if (name == null) name = uuid.toString().substring(0, 8);
                boolean dead = gm.isDead(uuid);
                sb.append(name).append(dead ? "§c(dead)§r" : "§a(alive)§r").append(", ");
            }
            String roster = sb.isEmpty() ? "§7(empty)" : sb.substring(0, sb.length() - 2);
            sender.sendMessage(
                Component.text("  " + GameManager.teamName(team) + ": ").color(GameManager.teamColor(team))
                    .append(Component.text(roster).color(NamedTextColor.WHITE))
            );
        }
    }

    /** Converts "team1"/"team2" strings (case-insensitive) to team constants. */
    private int parseTeam(String input) {
        return switch (input.toLowerCase()) {
            case "team1", "1", "blue" -> GameManager.TEAM_1;
            case "team2", "2", "red"  -> GameManager.TEAM_2;
            default                   -> 0;
        };
    }

    /** Returns entries from {@code options} that start with {@code prefix} (case-insensitive). */
    private List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase().startsWith(lower)) result.add(s);
        }
        return result;
    }
}
