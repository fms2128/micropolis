package micropolisj.ai;

import micropolisj.engine.Micropolis;
import micropolisj.gui.TileImages;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Renders the entire Micropolis map to a PNG image and encodes it
 * as a base64 string suitable for sending to vision-capable LLMs.
 *
 * Uses 8×8 pixel tiles resulting in a 960×800 image, which is
 * within Claude Vision's recommended size limits while still
 * showing meaningful detail.
 */
public class MapScreenshot {

    private static final int TILE_SIZE = 8;

    private MapScreenshot() {}

    /**
     * Capture the full map as a base64-encoded PNG string.
     * Must be called on the AWT/Swing thread or while the engine
     * state is not being mutated.
     *
     * @return base64 PNG string, or null on failure
     */
    public static String captureBase64(Micropolis engine) {
        int mapW = engine.getWidth();
        int mapH = engine.getHeight();

        TileImages tileImages = TileImages.getInstance(TILE_SIZE);
        BufferedImage image = new BufferedImage(
            mapW * TILE_SIZE, mapH * TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        for (int y = 0; y < mapH; y++) {
            for (int x = 0; x < mapW; x++) {
                int cell = engine.getTile(x, y);
                BufferedImage tileImg = tileImages.getTileImage(cell);
                g.drawImage(tileImg, x * TILE_SIZE, y * TILE_SIZE, null);
            }
        }
        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }
}
