package mgi.types.clientscript;

import com.zenyte.CacheManager;
import mgi.tools.jagcached.ArchiveType;
import mgi.tools.jagcached.cache.Archive;
import mgi.tools.jagcached.cache.Cache;
import mgi.tools.jagcached.cache.File;
import mgi.tools.jagcached.cache.Group;
import mgi.types.Definitions;
import mgi.utilities.ByteBuffer;

import java.util.Arrays;

/**
 * @author Tommeh | 11/02/2020 | 11:46
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>
 */
public final class ClientScriptDefinitions implements Definitions {
    
    private static ClientScriptDefinitions[] definitions;

    @Override
    public void load() {
        final Cache cache = CacheManager.getCache();
        final Archive scripts = cache.getArchive(ArchiveType.CLIENTSCRIPTS);
        definitions = new ClientScriptDefinitions[scripts.getHighestGroupId()];
        for (int id = 0; id < scripts.getHighestGroupId(); id++) {
            final Group scriptGroup = scripts.findGroupByID(id);
            if (scriptGroup == null) {
                continue;
            }
            final File file = scriptGroup.findFileByID(0);
            if (file == null) {
                continue;
            }
            final ByteBuffer buffer = file.getData();
            if (buffer == null) {
                continue;
            }
            buffer.setPosition(0);
            definitions[id] = new ClientScriptDefinitions(id, buffer);
        }
    }

    public int id;
    private int intArgumentCount;
    private int stringArgumentCount;
    public String[] stringArgs;
    public int[] instructions;
    private int intArgsCount;
    private int stringArgsCount;
    public int[] intArgs;

    ClientScriptDefinitions(final int id, final ByteBuffer buffer) {
        this.id = id;
        try {
            decode(buffer);
        } catch (Exception e){
            if (mgi.tools.jagcached.CacheFormat.isStrict()) {
                throw new IllegalStateException("Failed to decode client script " + id, e);
            }
            System.err.println("Failed to decode client script "+id);
            e.printStackTrace();
        }
    }

    /**
     * Build 241 (rev 237+): long locals and long arguments, and long constants in the code.
     */
    private int localLongCount;
    private int longArgumentCount;
    public long[] longArgs;

    public static ClientScriptDefinitions get(final int id) {
        return definitions[id];
    }

    public ClientScriptDefinitions() {
    }

    /*
     * The rev-237 trailer (long local/argument counts, 16 bytes instead of 12) and the LCONST (61, 8-byte operand)
     * and PUSH_NULL (63, 1-byte operand) instructions follow RuneLite's ScriptLoader (RuneLite master 330e650,
     * Copyright (c) 2017, Adam <Adam@sigterm.info>, BSD 2-Clause licence; see ItemDefinitionsDecoding.kt for the
     * full licence text).
     */
    @Override
    public void decode(final ByteBuffer buffer) {
        final boolean rev237 = mgi.tools.jagcached.CacheFormat.rev237Scripts();
        if (buffer.getBuffer().length < 2 + 1 + (rev237 ? 16 : 12)) {
            // too short to hold a trailer: a placeholder (script 0 of build 241 is the two bytes 00 0a), no code
            instructions = new int[0];
            intArgs = new int[0];
            stringArgs = new String[0];
            return;
        }
        buffer.setPosition(buffer.getBuffer().length - 2);
        int switchBlocksSize = buffer.readUnsignedShort();
        int codeBlockEnd = buffer.getBuffer().length - 2 - switchBlocksSize - (rev237 ? 16 : 12);
        buffer.setPosition(codeBlockEnd);
        int codeSize = buffer.readInt();
        intArgumentCount = buffer.readUnsignedShort();
        stringArgumentCount = buffer.readUnsignedShort();
        if (rev237) {
            localLongCount = buffer.readUnsignedShort();
        }
        intArgsCount = buffer.readUnsignedShort();
        stringArgsCount = buffer.readUnsignedShort();
        if (rev237) {
            longArgumentCount = buffer.readUnsignedShort();
        }
        int someCount = buffer.readUnsignedByte();
        if (someCount > 0) {
            for (int i = 0; i < someCount; i++) {
                int i_18_ = buffer.readUnsignedShort();
                while (i_18_-- > 0) {
                    buffer.readInt();
                    buffer.readInt();
                }
            }
        }
        if (buffer.getPosition() != buffer.getBuffer().length - 2) {
            mgi.tools.jagcached.CacheFormat.problem("cs2-switch:" + id, "Client script " + id
                    + ": switch tables end at " + buffer.getPosition() + ", expected " + (buffer.getBuffer().length - 2));
        }
        buffer.setPosition(0);
        String scriptName = buffer.getBuffer()[0] == 0 ? null : buffer.readString();
        if (scriptName == null) buffer.readByte(); // readStringOrNull
        instructions = new int[codeSize];
        intArgs = new int[codeSize];
        stringArgs = new String[codeSize];
        int i_33_ = 0;
        while (buffer.getPosition() < codeBlockEnd) {
            int opcode = buffer.readUnsignedShort();
            if (opcode == 3) stringArgs[i_33_] = buffer.readString();
            else if (opcode == 61) { // LCONST
                if (longArgs == null) longArgs = new long[codeSize];
                longArgs[i_33_] = buffer.readLong();
            }
            else if (opcode < 100 && opcode != 21 && opcode != 38 && opcode != 39 && opcode != 63) intArgs[i_33_] = buffer.readInt();
            else intArgs[i_33_] = buffer.readUnsignedByte();
            instructions[i_33_++] = opcode;
        }
        if (buffer.getPosition() != codeBlockEnd || i_33_ != codeSize) {
            mgi.tools.jagcached.CacheFormat.problem("cs2-code:" + id, "Client script " + id + ": code ended at "
                    + buffer.getPosition() + " (expected " + codeBlockEnd + ") after " + i_33_ + " of " + codeSize + " instructions");
        }
    }

    public int getLocalLongCount() {
        return localLongCount;
    }

    public int getLongArgumentCount() {
        return longArgumentCount;
    }

    public int getId() {
        return id;
    }

    public int getIntArgumentCount() {
        return intArgumentCount;
    }

    public int getStringArgumentCount() {
        return stringArgumentCount;
    }

    public String[] getStringArgs() {
        return stringArgs;
    }

    public int[] getInstructions() {
        return instructions;
    }

    public int getIntArgsCount() {
        return intArgsCount;
    }

    public int getStringArgsCount() {
        return stringArgsCount;
    }

    public int[] getIntArgs() {
        return intArgs;
    }

    @Override
    public String toString() {
        return "ClientScriptDefinitions(id=" + this.getId() + ", intArgumentCount=" + this.getIntArgumentCount() + "," +
                " stringArgumentCount=" + this.getStringArgumentCount() + ", stringArgs=" + Arrays.deepToString(this.getStringArgs()) + ", instructions=" + Arrays.toString(this.getInstructions()) + ", anInt1364=" + this.getIntArgsCount() + ", anInt1365=" + this.getStringArgsCount() + ", intArgs=" + Arrays.toString(this.getIntArgs()) + ")";
    }

}
