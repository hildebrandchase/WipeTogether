package com.wipetogether;

import java.util.*;
import java.util.stream.Stream;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class AdminCommands {
    private static final Set<String> COMMANDS = Set.of("resetruns", "setrun", "setdeaths", "resetworld");

    static boolean execute(WipeTogether plugin, CommandSender sender, String command, String[] args) {
        command = command.toLowerCase(Locale.ROOT);
        if (!COMMANDS.contains(command)) return false;
        if (!WipeTogether.hasAdminPermission(sender)) {
            error(sender, "You do not have permission to run this command.");
            return true;
        }
        if (!command.equals("setdeaths") && plugin.resetInProgress()) {
            error(sender, "A world reset is already in progress. Wait for it to finish.");
            return true;
        }
        switch (command) {
            case "resetruns" -> {
                if (args.length != 0) {
                    error(sender, "Usage: /resetruns");
                    break;
                }
                plugin.setCurrentCycle(1);
                success(sender, "Run number set to 1.");
            }
            case "setrun" -> {
                Integer number = args.length == 1 ? parse(args[0], 1) : null;
                if (number == null) {
                    error(sender, "Usage: /setrun <number> (1 to 2147483647)");
                    break;
                }
                plugin.setCurrentCycle(number);
                success(sender, "Run number set to " + number + ".");
            }
            case "setdeaths" -> {
                Integer count = args.length == 2 ? parse(args[1], 0) : null;
                if (count == null) {
                    error(sender, "Usage: /setdeaths <player> <number> (0 to 2147483647)");
                    break;
                }
                Identity player = findPlayer(plugin, args[0]);
                if (player == null) {
                    error(sender, "Unknown player. Use an online player or someone who has joined this server.");
                    break;
                }
                plugin.setDeathCount(player.id(), player.name(), count);
                success(sender, player.name() + "'s death count set to " + count + ".");
            }
            case "resetworld" -> {
                if (args.length != 0) {
                    error(sender, "Usage: /resetworld");
                    break;
                }
                if (plugin.beginManualReset())
                    success(sender, "World reset started. Run number and death counts will stay the same.");
                else error(sender, "A world reset is already in progress.");
            }
        }
        return true;
    }

    private static Integer parse(String text, int minimum) {
        try {
            int value = Integer.parseInt(text);
            return value >= minimum ? value : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private record Identity(UUID id, String name) {
    }

    private static Identity findPlayer(WipeTogether plugin, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new Identity(online.getUniqueId(), online.getName());
        var deaths = plugin.getConfig().getConfigurationSection("deaths");
        if (deaths != null) for (String id : deaths.getKeys(false)) {
            String savedName = deaths.getString(id + ".name");
            if (savedName != null && savedName.equalsIgnoreCase(name)) {
                try {
                    return new Identity(UUID.fromString(id), savedName);
                } catch (IllegalArgumentException ignored) { /* Skip malformed legacy records. */ }
            }
        }
        // Cache-only lookup avoids blocking the server thread on a Mojang profile request.
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && cached.getName() != null ? new Identity(cached.getUniqueId(), cached.getName()) : null;
    }

    static List<String> complete(WipeTogether plugin, CommandSender sender, String command, String[] args) {
        if (!WipeTogether.hasAdminPermission(sender)) return List.of();
        if (command.equalsIgnoreCase("setdeaths") && args.length == 1) {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            var section = plugin.getConfig().getConfigurationSection("deaths");
            if (section != null) for (String id : section.getKeys(false)) {
                String name = section.getString(id + ".name");
                if (name != null) names.add(name);
            }
            return names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if ((command.equalsIgnoreCase("setrun") && args.length == 1)
                || (command.equalsIgnoreCase("setdeaths") && args.length == 2)) {
            return Stream.of(command.equalsIgnoreCase("setrun") ? "1" : "0")
                    .filter(n -> n.startsWith(args[args.length - 1])).toList();
        }
        return List.of();
    }

    private static void error(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    private static void success(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[WipeTogether] " + text, NamedTextColor.GREEN));
    }
}
