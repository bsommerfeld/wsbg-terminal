package de.bsommerfeld.updater;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Writes a JavaFX image as an RGBA PNG - no AWT, no javafx.swing in the module graph. */
final class Png {

    private Png() {
    }

    static void write(Image image, Path out) throws IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        byte[] bgra = new byte[w * h * 4];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getByteBgraInstance(), bgra, 0, w * 4);

        byte[] raw = new byte[h * (w * 4 + 1)];
        for (int y = 0, src = 0, dst = 0; y < h; y++) {
            raw[dst++] = 0; // filter: none
            for (int x = 0; x < w; x++, src += 4) {
                raw[dst++] = bgra[src + 2];
                raw[dst++] = bgra[src + 1];
                raw[dst++] = bgra[src];
                raw[dst++] = bgra[src + 3];
            }
        }
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(idat, new Deflater(Deflater.BEST_SPEED))) {
            z.write(raw);
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(png);
        data.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(ihdr);
        header.writeInt(w);
        header.writeInt(h);
        header.write(new byte[]{8, 6, 0, 0, 0}); // 8 bit, RGBA, deflate, no filter, no interlace
        chunk(data, "IHDR", ihdr.toByteArray());
        chunk(data, "IDAT", idat.toByteArray());
        chunk(data, "IEND", new byte[0]);
        Files.createDirectories(out.getParent());
        Files.write(out, png.toByteArray());
    }

    private static void chunk(DataOutputStream out, String type, byte[] body) throws IOException {
        byte[] tag = type.getBytes();
        out.writeInt(body.length);
        out.write(tag);
        out.write(body);
        CRC32 crc = new CRC32();
        crc.update(tag);
        crc.update(body);
        out.writeInt((int) crc.getValue());
    }
}
