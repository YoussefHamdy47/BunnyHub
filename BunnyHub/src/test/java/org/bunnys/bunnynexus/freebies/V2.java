package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import java.util.List;
import static org.mockito.Mockito.*;

/** Reading Components V2 messages in tests: their text, their buttons, and a mock of the posted message. */
final class V2 {
    private V2() {}

    /** Every text block, top to bottom, one per line. */
    static String text(List<? extends MessageTopLevelComponent> top) {
        return String.join("\n", MessageComponentTree.of(top).findAll(TextDisplay.class).stream().map(TextDisplay::getContent).toList());
    }

    /** Every button: in rows (top-level or inside a card) and beside text. */
    static List<Button> buttons(List<? extends MessageTopLevelComponent> top) {
        return MessageComponentTree.of(top).findAll(Button.class);
    }

    /** A message as Discord returns it after posting {@code top}. */
    static Message posted(List<? extends MessageTopLevelComponent> top) {
        Message message = mock(Message.class);
        when(message.isUsingComponentsV2()).thenReturn(true);
        when(message.getEmbeds()).thenReturn(List.of());
        doReturn(top).when(message).getComponents();
        when(message.getComponentTree()).thenReturn(MessageComponentTree.of(top));
        return message;
    }
}
