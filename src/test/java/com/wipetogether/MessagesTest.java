package com.wipetogether;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {
    @TempDir Path root;
    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test void allBundledFieldsValidateWithoutWarnings() {
        List<String> warnings = new ArrayList<>();
        Messages messages = Messages.from(new YamlConfiguration(), warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals("[WipeTogether] Run number set to 12.", plain(messages.component("commands.run-set", Map.of("run", 12))));
        assertEquals("Usage: /setrun <number> (1 to 2147483647)", plain(messages.component("commands.usage-setrun", Map.of())));
        assertEquals("Usage: /setdeaths <player> <number> (0 to 2147483647)", plain(messages.component("commands.usage-setdeaths", Map.of())));
    }

    @Test void defaultsRenderAllTitleAudiencesWithExpectedTiming() {
        Messages messages = Messages.defaults();
        Map<String, Object> values = Map.of("victim", "Alex", "seconds", 2, "run", 4,
                "time", "01:23", "countdown_color", NamedTextColor.GOLD);
        assertEquals("YOU DIED!", plain(messages.title("countdown-victim", values).orElseThrow().title()));
        assertEquals("Alex DIED", plain(messages.title("countdown-teammate", values).orElseThrow().title()));
        assertEquals("WORLD RESET!", plain(messages.title("countdown-manual", values).orElseThrow().title()));
        assertEquals("NEW ATTEMPT!", plain(messages.title("welcome", values).orElseThrow().title()));
        var victory = messages.title("victory", values).orElseThrow();
        assertEquals("Final time: 01:23", plain(victory.subtitle()));
        assertEquals(Duration.ofMillis(300), victory.times().fadeIn());
        assertEquals(Duration.ofSeconds(5), victory.times().stay());
        assertEquals(Duration.ofMillis(700), victory.times().fadeOut());
    }

    @Test void customTitleColorsPlaceholdersAndDurationsAreApplied() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("titles.welcome.title", "<aqua>Attempt <run></aqua>");
        config.set("titles.welcome.subtitle", "Ready<newline>Go!");
        config.set("titles.welcome.fade-in-ms", 0);
        config.set("titles.welcome.stay-ms", 60000);
        config.set("titles.welcome.fade-out-ms", 250);
        var title = Messages.from(config, failWarning()).title("welcome", Map.of("run", 7)).orElseThrow();
        assertEquals("Attempt 7", plain(title.title()));
        assertEquals(NamedTextColor.AQUA, title.title().color());
        assertEquals("Ready\nGo!", plain(title.subtitle()));
        assertEquals(Duration.ZERO, title.times().fadeIn());
        assertEquals(Duration.ofMinutes(1), title.times().stay());
        assertEquals(Duration.ofMillis(250), title.times().fadeOut());
    }

    @Test void playerSuppliedMarkupIsAlwaysLiteral() {
        String name = "<red><click:run_command:'/op me'>Alex</click></red>";
        var title = Messages.defaults().title("countdown-teammate", Map.of("victim", name,
                "seconds", 3, "countdown_color", NamedTextColor.YELLOW)).orElseThrow();
        assertEquals(name + " DIED", plain(title.title()));
        assertNull(title.title().clickEvent());
        Component feedback = Messages.defaults().component("commands.deaths-set", Map.of("player", name, "count", 3));
        assertEquals("[WipeTogether] " + name + "'s death count set to 3.", plain(feedback));
    }

    @Test void placeholderValuesAreNotExpandedRecursively() {
        Component message = Messages.defaults().component("commands.deaths-set", Map.of("player", "<count>", "count", 8));
        assertEquals("[WipeTogether] <count>'s death count set to 8.", plain(message));
    }

    @Test void configuredPrefixIsFormattedAndInserted() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("prefix", "<gold>[Team]</gold> ");
        config.set("commands.run-set", "<prefix>Attempt <run>!");
        assertEquals("[Team] Attempt 9!", plain(Messages.from(config, failWarning()).component("commands.run-set", Map.of("run", 9))));
    }

    @Test void disabledTitleDoesNotSendAnything() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("titles.welcome.enabled", false);
        Messages messages = Messages.from(config, failWarning());
        Player player = mock(Player.class);
        assertTrue(messages.title("welcome", Map.of()).isEmpty());
        messages.showTitle(player, "welcome", Map.of());
        verifyNoInteractions(player);
    }

    @Test void blankChatMessagesDoNotSendOrBroadcast() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("chat.death", "");
        config.set("commands.run-set", "");
        Messages messages = Messages.from(config, failWarning());
        CommandSender sender = mock(CommandSender.class);
        messages.send(sender, "commands.run-set", Map.of("run", 3));
        verifyNoInteractions(sender);
        try (var bukkit = mockStatic(Bukkit.class)) {
            messages.broadcast("chat.death", Map.of());
            bukkit.verifyNoInteractions();
        }
    }

    @Test void malformedFormattingUsesDefaultAndWarnsOnce() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("titles.welcome.title", "<red>Unclosed");
        List<String> warnings = new ArrayList<>();
        Messages messages = Messages.from(config, warnings::add);
        for (int i = 0; i < 3; i++) assertEquals("NEW ATTEMPT!", plain(messages.title("welcome", Map.of()).orElseThrow().title()));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("titles.welcome.title"));
        assertEquals("<red>Unclosed", config.getString("titles.welcome.title"));
    }

    @Test void invalidDurationsFallBackWithoutThrowing() {
        for (Object invalid : List.of(-1, 60001, Long.MAX_VALUE, "oops", 2.5, Double.NaN)) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("titles.welcome.stay-ms", invalid);
            List<String> warnings = new ArrayList<>();
            Messages messages = Messages.from(config, warnings::add);
            assertEquals(Duration.ofSeconds(3), messages.title("welcome", Map.of()).orElseThrow().times().stay());
            assertEquals(1, warnings.size(), invalid.toString());
        }
    }

    @Test void invalidTextAndBooleanTypesUseTheirDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("titles.welcome.title", List.of("not", "text"));
        config.set("titles.welcome.enabled", "definitely");
        List<String> warnings = new ArrayList<>();
        var title = Messages.from(config, warnings::add).title("welcome", Map.of()).orElseThrow();
        assertEquals("NEW ATTEMPT!", plain(title.title()));
        assertEquals(2, warnings.size());
    }

    @Test void freshFileIncludesGuideAndNoSavedGameState() throws Exception {
        Path file = root.resolve("WipeTogether/messages.yml");
        Messages.load(file, failWarning());
        String text = Files.readString(file);
        assertTrue(text.contains("QUICK START"));
        assertTrue(text.contains("PLACEHOLDERS"));
        assertTrue(text.contains("fade-in-ms"));
        var yaml = ConfigStore.load(file, null);
        assertFalse(yaml.contains("state"));
        assertFalse(yaml.contains("players"));
        assertFalse(yaml.contains("wins"));
    }

    @Test void partialCustomFileSurvivesLoadsAndMissingKeysUseDefaults() throws Exception {
        Path file = root.resolve("messages.yml");
        String custom = "# Keep this comment\ntitles:\n  welcome:\n    title: '<green>Custom</green>'\n";
        Files.writeString(file, custom);
        for (int i = 0; i < 2; i++) {
            Messages messages = Messages.load(file, failWarning());
            assertEquals("Custom", plain(messages.title("welcome", Map.of()).orElseThrow().title()));
            assertEquals(Duration.ofSeconds(3), messages.title("welcome", Map.of()).orElseThrow().times().stay());
            assertEquals("HARDCORE COMPLETE!", plain(messages.title("victory", Map.of("time", "00:01")).orElseThrow().title()));
            assertEquals(custom, Files.readString(file));
        }
    }

    @Test void malformedYamlIsRejectedWithoutRewritingIt() throws Exception {
        Path file = root.resolve("messages.yml");
        String broken = "titles: [unterminated\n";
        Files.writeString(file, broken);
        assertThrows(InvalidConfigurationException.class, () -> Messages.load(file, failWarning()));
        assertEquals(broken, Files.readString(file));
    }

    private static java.util.function.Consumer<String> failWarning() {
        return warning -> fail(warning);
    }
}
