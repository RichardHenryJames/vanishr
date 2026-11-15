package app.vanishr.android;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the photos of one album go in a chat, worked out from the number of photos alone so that it can be tested without a screen. One
 * photo is a tile of its own; two stand side by side; three are one tall tile beside two small ones; four or more fill a square of four,
 * and when there are more than four the fourth tile says how many are behind it (the fourth photo and every one after it), as other
 * messengers do: six photos show "+3" on the fourth tile.
 */
final class AlbumLayout {
    private AlbumLayout() { }

    /** One tile, in pixels inside the album; {@code more} is the "+N" it shows (0 for none). */
    record Tile(int index, int left, int top, int width, int height, int more) { }

    record Plan(int width, int height, List<Tile> tiles) { }

    /** The most tiles drawn for one album. */
    static final int SHOWN = 4;

    /** How tall a single photo is drawn, as a fraction of its width: its own proportions, held between these two, 0.75 when not known. */
    static final float TALLEST = 1.35f, FLATTEST = 0.6f, UNKNOWN = 0.75f;

    static int singleHeight(int width, int photoWidth, int photoHeight) {
        float ratio = photoWidth > 0 && photoHeight > 0 ? photoHeight / (float) photoWidth : UNKNOWN;
        return Math.round(width * Math.max(FLATTEST, Math.min(TALLEST, ratio)));
    }

    /** {@code present} is how many photos can be opened; {@code width} and {@code gap} are in pixels. */
    static Plan plan(int present, int width, int gap) {
        List<Tile> tiles = new ArrayList<>();
        int half = (width - gap) / 2;
        if (present <= 1) {
            tiles.add(new Tile(0, 0, 0, width, Math.round(width * UNKNOWN), 0));
            return new Plan(width, tiles.get(0).height(), tiles);
        }
        if (present == 2) {
            int tall = Math.round(half * 1.25f);
            tiles.add(new Tile(0, 0, 0, half, tall, 0));
            tiles.add(new Tile(1, width - half, 0, half, tall, 0));
            return new Plan(width, tall, tiles);
        }
        if (present == 3) {
            tiles.add(new Tile(0, 0, 0, half, width, 0));
            tiles.add(new Tile(1, width - half, 0, half, half, 0));
            tiles.add(new Tile(2, width - half, width - half, half, half, 0));
            return new Plan(width, width, tiles);
        }
        int other = width - half;
        tiles.add(new Tile(0, 0, 0, half, half, 0));
        tiles.add(new Tile(1, other, 0, half, half, 0));
        tiles.add(new Tile(2, 0, other, half, half, 0));
        tiles.add(new Tile(3, other, other, half, half, present > SHOWN ? present - (SHOWN - 1) : 0));
        return new Plan(width, width, tiles);
    }
}
