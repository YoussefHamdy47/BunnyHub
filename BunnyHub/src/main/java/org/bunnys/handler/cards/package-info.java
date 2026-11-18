/**
 * Discord Components V2 messages ("cards") for any bot built on this handler. Depends only on JDA, so the package can
 * be copied into another bot unchanged.
 *
 * <h2>Building</h2>
 * {@link org.bunnys.handler.cards.Card#builder()} adds parts in reading order:
 * <ul>
 *   <li>{@code above(text)}: text outside the card, where a ping line goes, with {@code mentionRoles} / {@code mentionUsers}
 *       naming exactly who may be pinged (nothing else ever pings; edits never ping);</li>
 *   <li>{@code title}, {@code header(text, thumbnailUrl)}, {@code text}, {@code field(name, value)} (consecutive
 *       fields share one text block, like embed fields), {@code section(text, button or thumbnail)};</li>
 *   <li>{@code image}, {@code gallery} (up to 10 images side by side), {@code divider}, {@code space};</li>
 *   <li>{@code buttons}, {@code row}, {@code rows} (up to 5 rows inside the card), {@code footer} (small grey line, always last);</li>
 *   <li>{@code accent(color)} for the coloured stripe; BunnyNexus cards leave it out, and {@code fromEmbed} drops embed colours.</li>
 * </ul>
 * {@code build()} checks Discord's limits (40 components, 4000 characters of text, 5 rows) and throws instead of
 * letting Discord reject the message; {@code fits()} and {@code remainingText()} let callers shrink text first.
 *
 * <h2>Sending</h2>
 * <ul>
 *   <li>commands: {@code ctx.replyCard(card)} (private or public as the command deferred);</li>
 *   <li>buttons and menus: {@link org.bunnys.handler.cards.Cards#edit} replaces the message they sit on, and falls back to a
 *       private reply when that message was sent as an embed before the switch; {@link org.bunnys.handler.cards.Cards#reply}
 *       answers with a new message;</li>
 *   <li>anywhere else: {@code channel.sendMessage(card.message())} or {@link org.bunnys.handler.cards.Cards#send}.</li>
 * </ul>
 *
 * <h2>Migrating an embed</h2>
 * Title becomes {@code title} (or {@code header} with the thumbnail), description {@code text}, fields {@code field},
 * image {@code image}, footer {@code footer}, colour {@code accent}; the button rows go inside with {@code rows}.
 * Embed-only details have no direct equivalent: side-by-side fields become lines, the author line becomes a header with
 * a thumbnail, and the timestamp becomes {@code <t:epoch:R>} in the text. A message sent as an embed stays an embed;
 * edit it as one, or reply with a card.
 */
package org.bunnys.handler.cards;
