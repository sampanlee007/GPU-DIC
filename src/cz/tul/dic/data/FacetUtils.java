package cz.tul.dic.data;

import cz.tul.dic.data.deformation.DeformationDegree;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 *
 * @author Petr Jecmen
 */
public class FacetUtils {

    private static final Map<int[], double[]> CACHE;

    static {
        CACHE = new HashMap<>();
    }

    public static Map<int[], double[]> deformFacet(final Facet facet, final double[] deformation, final DeformationDegree degree) {
        final int[] data = facet.getData();
        final float[] center = facet.getCenter();
        final int facetArea = data.length / Coordinates.DIMENSION;

        if (CACHE.size() != facetArea) {
            if (CACHE.size() > facetArea) {
                CACHE.clear();
            }

            while (CACHE.size() < facetArea) {
                CACHE.put(new int[Coordinates.DIMENSION], new double[Coordinates.DIMENSION]);
            }
        }

        int x, y, i = 0;
        float dx, dy;
        final float[] newCoords = new float[Coordinates.DIMENSION];
        int[] pos;
        double[] def;
        for (Entry<int[], double[]> e : CACHE.entrySet()) {
            x = data[i * 2];
            y = data[i * 2 + 1];

            dx = x - center[Coordinates.X];
            dy = y - center[Coordinates.Y];

            deform(x, y, dx, dy, deformation, newCoords, degree);

            pos = e.getKey();
            pos[0] = x;
            pos[1] = y;

            def = e.getValue();
            def[0] = newCoords[Coordinates.X] - x;
            def[1] = newCoords[Coordinates.Y] - y;

            i++;
        }

        return CACHE;
    }

    private static void deform(final int x, final int y, final float dx, final float dy, final double[] deformation, final float[] result, final DeformationDegree degree) {
        result[Coordinates.X] = x;
        result[Coordinates.Y] = y;
        switch (degree) {
            case FIRST:
                result[Coordinates.X] += deformation[2] * dx + deformation[4] * dy;
                result[Coordinates.Y] += deformation[3] * dx + deformation[5] * dy;
            case ZERO:
                result[Coordinates.X] += deformation[0];
                result[Coordinates.Y] += deformation[1];
                break;
            case SECOND:
            default:
                throw new IllegalArgumentException("Unsupported degree of deformation.");

        }
    }

}