package game.data.chunk.version;

import config.Version;
import game.data.chunk.Chunk;
import game.data.chunk.palette.Palette;
import se.llbit.nbt.*;

import java.util.List;

public class ChunkSection_1_18 extends ChunkSection_1_17 {
    long[] biomes;
    Palette biomePalette;

    public static final Version VERSION = Version.V1_18;
    @Override
    public int getDataVersion() {
        return VERSION.dataVersion;
    }

    public ChunkSection_1_18(byte y, Palette palette, Chunk chunk) {
        super(y, palette, chunk);
    }

    public ChunkSection_1_18(int sectionY, Tag nbt) {
        super(sectionY, nbt);
    }

    public void setBiomes(long[] biomes) {
        this.biomes = biomes;
    }

    public void setBiomePalette(Palette biomePalette) {
        this.biomePalette = biomePalette;
        this.biomePalette.biomePalette();
    }

    @Override
    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();

        tag.add("biomes", getPalettedCompound(biomePalette, Tag.TAG_STRING, biomes));
        tag.add("block_states", getPalettedCompound(palette, Tag.TAG_COMPOUND, blocks));

        tag.add("Y", new ByteTag(y));
        if (blockLight != null && blockLight.length > 0) {
            tag.add("BlockLight", new ByteArrayTag(blockLight));
        }
        if (skyLight != null && skyLight.length > 0) {
            tag.add("SkyLight", new ByteArrayTag(skyLight));
        }

        return tag;
    }

    private CompoundTag getPalettedCompound(Palette palette, int tagType, long[] data) {
        CompoundTag tag = new CompoundTag();

        // should this even happen?
        if (palette == null) {
            return tag;
        }

        List<SpecificTag> paletteItems = palette.toNbt();
        if (!paletteItems.isEmpty()) {
            tag.add("palette", new ListTag(tagType, paletteItems));
        }
        if (data != null && data.length > 0) {
            tag.add("data", new LongArrayTag(data));
        }

        return tag;
    }
}
