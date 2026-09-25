package org.bunnys.bunnynexus.commands.alerts;

public final class FreebieRemove extends FreebieSubcommand {
    public FreebieRemove(FreebieActions actions) {
        super(actions, "remove", "Remove settings at the reviewed revision; already-authorized alerts may still arrive.");
        revision(); scope(); target();
    }
}
