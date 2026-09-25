package org.bunnys.handler.events;

import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bunnys.handler.BunnyHub;

public abstract class BunnyEvent extends ListenerAdapter {
    protected final BunnyHub client;

    public BunnyEvent(BunnyHub client) {
        this.client = client;
    }
}