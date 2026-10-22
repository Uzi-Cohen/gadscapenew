package com.zenyte.game.world.entity.masks;

import com.gadscape.game.util.HuffmanManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.DefaultByteBufHolder;

/**
 * @author Kris | 6. nov 2017 : 14:28.18
 * @author Jire
 */
public final class ChatMessage extends DefaultByteBufHolder implements AutoCloseable {

    private int effects;
    private String chatText;
    private boolean autotyper;
    private byte[] pattern;

    public ChatMessage(final ByteBuf huffmanBuf) {
        super(huffmanBuf);
    }

    public ChatMessage(final ByteBuf huffmanBuf,
                       final String chatText,
                       final int effects,
                       final boolean autotyper) {
        this(huffmanBuf);
        this.effects = effects;
        this.chatText = chatText;
        this.autotyper = autotyper;
    }

    public ChatMessage set(final String text,
                           final int effects,
                           final boolean autotyper) {
        return set(text, effects, autotyper, null);
    }

    /**
     * @param pattern the colour pattern of a public message in colour 13-20 (rev 241: one colour code per
     *                pattern step, colour - 12 bytes), or null.
     */
    public ChatMessage set(final String text,
                           final int effects,
                           final boolean autotyper,
                           final byte[] pattern) {
        this.chatText = text;
        this.effects = effects;
        this.autotyper = autotyper;
        this.pattern = pattern;

        final ByteBuf content = content();
        content.clear();
        HuffmanManager.encodeHuffmanBuf(content, text);
        return this;
    }

    public int getEffects() {
        return effects;
    }

    public String getChatText() {
        return chatText;
    }

    public boolean isAutotyper() {
        return autotyper;
    }

    /** The colour pattern of a public message in colour 13-20, or null. */
    public byte[] getPattern() {
        return pattern;
    }

    public static ChatMessage reusable() {
        return new ChatMessage(ByteBufAllocator.DEFAULT.directBuffer(
                HuffmanManager.MAX_HUFFMAN_STRING_LENGTH,
                HuffmanManager.MAX_HUFFMAN_STRING_LENGTH));
    }

    @Override
    public void close() {
        release();
    }

}
