package com.wipetogether;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.lang.reflect.Proxy;
import java.util.*;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.mockito.MockedStatic;

/** Minimal registry fixtures for API-only tests; no Minecraft terrain generation is simulated. */
final class RegistryTestSupport implements AutoCloseable {
    private final MockedStatic<RegistryAccess> access = mockStatic(RegistryAccess.class);
    private final Map<Object, Registry<?>> registries = new HashMap<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    RegistryTestSupport() {
        RegistryAccess provider = mock(RegistryAccess.class);
        access.when(RegistryAccess::registryAccess).thenReturn(provider);
        when(provider.getRegistry(any(Class.class))).thenAnswer(i -> forType(i.getArgument(0)));
        when(provider.getRegistry(any(RegistryKey.class))).thenAnswer(i -> {
            RegistryKey<?> key = i.getArgument(0);
            Class<?> type = switch(key.key().value()) {
                case "attribute" -> org.bukkit.attribute.Attribute.class;
                case "sound_event" -> org.bukkit.Sound.class;
                default -> Keyed.class;
            };
            return forType(type);
        });
    }

    private Registry<?> forType(Class<?> type) {
        // Registry's default methods initialize the interface during proxy construction.
        // Reentrant initialization is allowed on the same thread; do not use computeIfAbsent here.
        Registry<?> existing = registries.get(type);
        if (existing != null) return existing;
        Map<Object, Object> values = new HashMap<>();
        Registry<?> result = (Registry<?>)Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Registry.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("get") || name.equals("getOrThrow")) {
                Object key = args[0];
                Object value = values.get(key);
                if (value == null) {
                    value = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{type}, (item, operation, arguments) -> switch(operation.getName()) {
                        case "getKey", "key" -> key;
                        case "name" -> key.toString().replace("minecraft:", "").toUpperCase(Locale.ROOT).replace('.', '_');
                        case "toString", "translationKey", "getTranslationKey" -> key.toString();
                        case "equals" -> item == arguments[0];
                        case "hashCode" -> System.identityHashCode(item);
                        case "ordinal", "compareTo" -> 0;
                        default -> null;
                    });
                    values.put(key, value);
                }
                return value;
            }
            return switch(name) {
                case "iterator" -> values.values().iterator();
                case "stream" -> values.values().stream();
                case "getTags" -> List.of();
                case "size" -> values.size();
                case "hasTag" -> false;
                case "toString" -> "TestRegistry[" + type.getSimpleName() + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        });
        registries.put(type, result);
        return result;
    }

    @Override public void close() { access.close(); }
}
