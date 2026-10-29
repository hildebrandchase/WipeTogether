package com.wipetogether;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.StyleBuilderApplicable;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Presentation settings cannot alter the reset lifecycle or interpret player names as markup. */
final class Messages {
    private static final MiniMessage FORMAT = MiniMessage.builder().strict(true).build();
    private static final YamlConfiguration BUNDLED = bundled();
    private static final Messages DEFAULT = new Messages(new YamlConfiguration(), ignored -> {});
    private static final Map<String, Object> EXAMPLES = Map.ofEntries(
            Map.entry("run", 1), Map.entry("victim", "Player"), Map.entry("seconds", 3),
            Map.entry("time", "00:00"), Map.entry("team", "Player, Teammate"),
            Map.entry("seed", 123L), Map.entry("player", "Player"), Map.entry("count", 0),
            Map.entry("countdown_color", NamedTextColor.YELLOW));
    private final YamlConfiguration config;
    private final Consumer<String> warning;
    private final Set<String> warned = new HashSet<>();

    private Messages(YamlConfiguration config, Consumer<String> warning) {
        this.config = config;
        this.config.setDefaults(BUNDLED);
        this.warning = warning;
    }

    static Messages defaults() { return DEFAULT; }

    static Messages from(YamlConfiguration config, Consumer<String> warning) {
        Messages messages = new Messages(config, warning);
        // Validate every editable field at startup, rather than waiting for a victory or death.
        for (String key : BUNDLED.getKeys(true)) {
            Object value = BUNDLED.get(key);
            if (value instanceof String) messages.component(key, EXAMPLES);
            else if (value instanceof Boolean) messages.enabled(key);
            else if (value instanceof Number) messages.millis(key);
        }
        return messages;
    }

    static Messages load(Path file, Consumer<String> warning) throws IOException, InvalidConfigurationException {
        if (!Files.exists(file)) {
            try (InputStream stream = resource()) {
                ConfigStore.save(file, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        // Existing customizations, comments, and missing keys are never rewritten.
        return from(ConfigStore.load(file, null), warning);
    }

    Component component(String path, Map<String, ?> values) {
        String fallback = BUNDLED.getString(path);
        if (fallback == null) throw new IllegalArgumentException("Unknown message: " + path);
        Object configured = config.get(path);
        String text;
        if (configured instanceof String string) text = string;
        else { warn(path, "expected quoted text"); text = fallback; }
        try { return parse(text, path, values); }
        catch (RuntimeException error) {
            warn(path, "invalid MiniMessage formatting; close formatting tags");
            return parse(fallback, path, values);
        }
    }

    private Component parse(String text, String path, Map<String, ?> values) {
        TagResolver.Builder resolver = TagResolver.builder();
        if (!path.equals("prefix")) resolver.resolver(Placeholder.component("prefix", component("prefix", Map.of())));
        values.forEach((key, value) -> {
            if (key.equals("prefix")) return;
            if (value instanceof StyleBuilderApplicable style) resolver.resolver(TagResolver.resolver(key, Tag.styling(style)));
            else resolver.resolver(Placeholder.unparsed(key, String.valueOf(value)));
        });
        return FORMAT.deserialize(text, resolver.build());
    }

    Optional<Title> title(String name, Map<String, ?> values) {
        String root = "titles." + name;
        if (!enabled(root + ".enabled")) return Optional.empty();
        return Optional.of(Title.title(component(root + ".title", values), component(root + ".subtitle", values),
                Title.Times.times(Duration.ofMillis(millis(root + ".fade-in-ms")),
                        Duration.ofMillis(millis(root + ".stay-ms")), Duration.ofMillis(millis(root + ".fade-out-ms")))));
    }

    void showTitle(Player player, String name, Map<String, ?> values) {
        title(name, values).ifPresent(player::showTitle);
    }

    void send(CommandSender sender, String path, Map<String, ?> values) {
        Component message = component(path, values);
        if (!message.equals(Component.empty())) sender.sendMessage(message);
    }

    void broadcast(String path, Map<String, ?> values) {
        Component message = component(path, values);
        if (!message.equals(Component.empty())) Bukkit.broadcast(message);
    }

    private boolean enabled(String path) {
        Object value = config.get(path);
        if (value instanceof Boolean flag) return flag;
        warn(path, "expected true or false");
        return BUNDLED.getBoolean(path);
    }

    private long millis(String path) {
        Object value = config.get(path);
        if (value instanceof Number number) {
            double amount = number.doubleValue();
            if (Double.isFinite(amount) && amount >= 0 && amount <= 60000 && amount == Math.rint(amount)) return number.longValue();
        }
        warn(path, "expected a whole number of milliseconds from 0 to 60000");
        return BUNDLED.getLong(path);
    }

    private void warn(String path, String problem) {
        if (warned.add(path)) warning.accept("messages.yml: " + path + ": " + problem + "; using bundled default.");
    }

    private static InputStream resource() {
        InputStream stream = Messages.class.getResourceAsStream("/messages.yml");
        if (stream == null) throw new IllegalStateException("Bundled messages.yml is missing");
        return stream;
    }

    private static YamlConfiguration bundled() {
        try (InputStream stream = resource()) {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return config;
        } catch (IOException | InvalidConfigurationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
