package de.bsommerfeld.wsbg.terminal.intro;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

/**
 * How far every point of the plate is from a silhouette - the shape the
 * settling storm runs out from. A grid of cells over the plate, each holding
 * the exact Euclidean distance from its centre to the nearest cell inside the
 * silhouette (Felzenszwalb and Huttenlocher's two-pass transform), in logical
 * pixels.
 */
final class DistanceField {

    /** Cell size in logical pixels: the wave's edge is soft, a finer grid would not show. */
    private static final double CELL = 4;
    /** "No shape anywhere near": large, but far from where doubles lose the squares added to it. */
    private static final double FAR = 1e12;

    private final float[] distances;
    private final int columns;
    private final int rows;

    /**
     * @param silhouette the shape as a mask - opaque inside
     * @param placement  where the mask lies on the plate
     */
    DistanceField(Image silhouette, Rectangle2D placement, double plateWidth, double plateHeight) {
        columns = (int) Math.ceil(plateWidth / CELL);
        rows = (int) Math.ceil(plateHeight / CELL);
        double[] squared = new double[columns * rows];
        PixelReader mask = silhouette.getPixelReader();
        double scaleX = silhouette.getWidth() / placement.getWidth();
        double scaleY = silhouette.getHeight() / placement.getHeight();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                double x = ((column + 0.5) * CELL - placement.getMinX()) * scaleX;
                double y = ((row + 0.5) * CELL - placement.getMinY()) * scaleY;
                boolean inside = x >= 0 && y >= 0 && x < silhouette.getWidth() && y < silhouette.getHeight()
                        && (mask.getArgb((int) x, (int) y) >>> 24) > 127;
                squared[row * columns + column] = inside ? 0 : FAR;
            }
        }
        transform(squared, columns, rows);
        distances = new float[squared.length];
        for (int i = 0; i < squared.length; i++) {
            distances[i] = (float) (Math.sqrt(squared[i]) * CELL);
        }
    }

    float[] distances() {
        return distances;
    }

    int columns() {
        return columns;
    }

    int rows() {
        return rows;
    }

    /** The largest distance inside {@code area} of the plate. */
    double farthestWithin(Bounds area) {
        double farthest = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (area.contains((column + 0.5) * CELL, (row + 0.5) * CELL)) {
                    farthest = Math.max(farthest, distances[row * columns + column]);
                }
            }
        }
        return farthest;
    }

    /** The distance at a point of the plate, from the nearest cell. */
    double at(Point2D point) {
        int column = Math.clamp((int) (point.getX() / CELL), 0, columns - 1);
        int row = Math.clamp((int) (point.getY() / CELL), 0, rows - 1);
        return distances[row * columns + column];
    }

    /** Squared distances in cells, in place: first down every column, then along every row. */
    private static void transform(double[] grid, int columns, int rows) {
        int longest = Math.max(columns, rows);
        double[] line = new double[longest];
        double[] out = new double[longest];
        int[] hull = new int[longest];
        double[] bounds = new double[longest + 1];
        for (int column = 0; column < columns; column++) {
            for (int row = 0; row < rows; row++) {
                line[row] = grid[row * columns + column];
            }
            pass(line, rows, out, hull, bounds);
            for (int row = 0; row < rows; row++) {
                grid[row * columns + column] = out[row];
            }
        }
        for (int row = 0; row < rows; row++) {
            System.arraycopy(grid, row * columns, line, 0, columns);
            pass(line, columns, out, hull, bounds);
            System.arraycopy(out, 0, grid, row * columns, columns);
        }
    }

    /** One dimension: the lower envelope of the parabolas rooted at every sample. */
    private static void pass(double[] f, int n, double[] out, int[] hull, double[] bounds) {
        int k = 0;
        hull[0] = 0;
        bounds[0] = -FAR;
        bounds[1] = FAR;
        for (int q = 1; q < n; q++) {
            double s = intersection(f, q, hull[k]);
            while (s <= bounds[k]) {
                k--;
                s = intersection(f, q, hull[k]);
            }
            k++;
            hull[k] = q;
            bounds[k] = s;
            bounds[k + 1] = FAR;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (bounds[k + 1] < q) {
                k++;
            }
            double d = q - hull[k];
            out[q] = d * d + f[hull[k]];
        }
    }

    private static double intersection(double[] f, int q, int p) {
        return ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
    }
}
