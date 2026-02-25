package ru.fatedonate.minecraft.service;

import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

public final class TopupLinkMessageService {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();
    private static final String CHECKOUT_URL_PLACEHOLDER = "{checkout_url}";

    public String checkoutUrlPlaceholder() {
        return CHECKOUT_URL_PLACEHOLDER;
    }

    public boolean send(Player player, String template, String checkoutUrl, Consumer<String> warningLogger) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(template, "template");

        final String normalizedCheckoutUrl = normalizeCheckoutUrl(checkoutUrl, warningLogger);
        if (normalizedCheckoutUrl.isBlank()) {
            return false;
        }

        final Component messageComponent = buildTopupLinkComponent(
                template,
                buildClickableUrlComponent(normalizedCheckoutUrl)
        );
        player.sendMessage(messageComponent);
        return true;
    }

    private Component buildTopupLinkComponent(String template, Component clickableUrl) {
        final int placeholderIndex = template.indexOf(CHECKOUT_URL_PLACEHOLDER);
        if (placeholderIndex < 0) {
            return LEGACY_SERIALIZER.deserialize(template)
                    .append(Component.text(" "))
                    .append(clickableUrl);
        }

        final Component before = LEGACY_SERIALIZER.deserialize(template.substring(0, placeholderIndex));
        final Component after = LEGACY_SERIALIZER.deserialize(
                template.substring(placeholderIndex + CHECKOUT_URL_PLACEHOLDER.length()));
        return before.append(clickableUrl).append(after);
    }

    private static Component buildClickableUrlComponent(String checkoutUrl) {
        return Component.text(checkoutUrl, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(checkoutUrl))
                .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы открыть ссылку")));
    }

    private String normalizeCheckoutUrl(String checkoutUrl, Consumer<String> warningLogger) {
        if (checkoutUrl == null) {
            return "";
        }

        String normalized = checkoutUrl.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }

        try {
            URI.create(normalized);
            return normalized;
        } catch (Exception exception) {
            if (warningLogger != null) {
                warningLogger.accept("Получен невалидный checkoutUrl от API: " + checkoutUrl);
            }
            return checkoutUrl.trim();
        }
    }
}
