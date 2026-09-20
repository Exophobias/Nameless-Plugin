package com.namelessmc.plugin.common.audiences;

import com.namelessmc.plugin.common.Permission;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NamelessCommandSenderTest {
    @Test
    void deliversComponentsAndComponentLikeMessagesThroughTheCurrentAudienceContract() {
        List<Component> delivered = new ArrayList<>();
        Audience platform = new Audience() {
            @Override
            public void sendMessage(Component message) {
                delivered.add(message);
            }
        };
        NamelessCommandSender sender = new NamelessCommandSender(platform) {
            @Override
            public boolean hasPermission(Permission permission) {
                return false;
            }
        };
        Component message = Component.text("Link your account", NamedTextColor.GOLD)
                .clickEvent(ClickEvent.openUrl("https://example.invalid/connections"));

        sender.sendMessage(message);
        sender.sendMessage((ComponentLike) () -> message);

        assertEquals(2, delivered.size());
        assertSame(message, delivered.get(0));
        assertSame(message, delivered.get(1));
    }
}
