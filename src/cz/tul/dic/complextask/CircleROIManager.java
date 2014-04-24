package cz.tul.dic.complextask;

import cz.tul.dic.ComputationException;
import cz.tul.dic.ComputationExceptionCause;
import cz.tul.dic.Utils;
import cz.tul.dic.data.Coordinates;
import cz.tul.dic.data.roi.CircularROI;
import cz.tul.dic.data.roi.ROI;
import cz.tul.dic.data.roi.RectangleROI;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.engine.cluster.Analyzer1D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.pmw.tinylog.Logger;

/**
 *
 * @author Petr Jecmen
 */
public class CircleROIManager extends ROIManager {

    private static final int MAX_SHIFT_DIFFERENCE = 3;
    private static final int ROI_CIRCLE_FS_DENOM = 3;
    private static final double PRECISION_CIRC_ZERO = 0.5;
    private static final double[] DEFAULT_DEF_LIM_CIRCLE = new double[]{-1, 1, PRECISION_CIRC_ZERO, -5, 5, PRECISION_CIRC_ZERO};
    private static final double ADJUST_COEFF_UP = 2.0;
    private static final double ADJUST_COEFF_DOWN = 0.75;
    private CircularROI topLeft, topRight, bottomLeft, bottomRight;
    private double shiftTop, shiftBottom;

    public CircleROIManager(TaskContainer tc, final int initialRound) throws ComputationException {
        super(tc);

        final List<CircularROI> cRois = new ArrayList<>(4);
        ROI rect = null;
        for (ROI r : tc.getRois(initialRound)) {
            if (r instanceof CircularROI) {
                cRois.add((CircularROI) r);
            } else if (!(r instanceof RectangleROI)) {
                throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "Unsupported type of ROI - " + r.getClass());
            } else {
                rect = r;
            }
        }

        if (cRois.size() != 4) {
            throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "4 circular ROIs needed.");
        }

        Collections.sort(cRois, new RoiSorter());
        topLeft = cRois.get(0);
        topRight = cRois.get(1);
        bottomLeft = cRois.get(2);
        bottomRight = cRois.get(3);

        defLimits = DEFAULT_DEF_LIM_CIRCLE;
        setROIs(initialRound);
        if (rect != null) {
            tc.addRoi(initialRound, rect);
        }
    }

    @Override
    public void generateNextRound(int round, int nextRound) {
        // find new position of Circle ROIs
        //// determine shifts of circle ROIs from previous round
        final double shift0 = determineROIShift(round, topLeft);
        final double shift1 = determineROIShift(round, topRight);
        final double shift2 = determineROIShift(round, bottomLeft);
        final double shift3 = determineROIShift(round, bottomRight);
        Logger.debug(shift2 + ", " + shift3);
        //// check if left equals right
        if (Math.abs(shift2 - shift3) > MAX_SHIFT_DIFFERENCE) {
            Logger.warn(ComputationExceptionCause.FIXTURES_SHIFT_MISMATCH.toString().concat("-LOWER- ").concat(Double.toString(shift2)).concat(" vs ".concat(Double.toString(shift3))));
        }
        if (Math.abs(shift1 - shift0) > MAX_SHIFT_DIFFERENCE) {
            Logger.warn(ComputationExceptionCause.FIXTURES_SHIFT_MISMATCH.toString().concat("-UPPER- ").concat(Double.toString(shift0)).concat(" vs ".concat(Double.toString(shift1))));
        }
        // generate new Circle ROIs
        topLeft = new CircularROI(topLeft.getCenterX(), topLeft.getCenterY() + shift0, topLeft.getRadius());
        topRight = new CircularROI(topRight.getCenterX(), topRight.getCenterY() + shift1, topRight.getRadius());
        bottomLeft = new CircularROI(bottomLeft.getCenterX(), bottomLeft.getCenterY() + shift2, bottomLeft.getRadius());
        bottomRight = new CircularROI(bottomRight.getCenterX(), bottomRight.getCenterY() + shift3, bottomRight.getRadius());

        if (shift0 < 0 || shift1 < 0) {
            shiftTop = Math.min(shift0, shift1);
        } else {
            shiftTop = Math.max(shift0, shift1);
        }
        if (shift2 < 0 || shift3 < 0) {
            shiftBottom = Math.min(shift2, shift3);
        } else {
            shiftBottom = Math.max(shift2, shift3);
        }

        if (haveMoved(shift2, shift3)) {
            estimateNewCircleDeformationLimits(shiftBottom);
        }

        setROIs(nextRound);
    }

    private double determineROIShift(final int round, final ROI roi) {
        final double[][][] results = tc.getDisplacement(round);
        final Analyzer1D analyzer = new Analyzer1D();
        analyzer.setPrecision(PRECISION);

        for (int x = roi.getX1(); x <= roi.getX2(); x++) {
            for (int y = roi.getY1(); y <= roi.getY2(); y++) {
                if (x > 0 && y > 0 && roi.isPointInside(x, y) && results[x][y] != null) {
                    analyzer.addValue(results[x][y][Coordinates.Y]);
                }
            }
        }

        return analyzer.findMajorValue();
    }

    private void estimateNewCircleDeformationLimits(final double shift) {
        final double[] result;
        result = new double[defLimits.length];
        System.arraycopy(defLimits, 0, result, 0, defLimits.length);

        if (shift < 0) {
            result[3] = adjustValue(shift, defLimits[3]);
            result[4] = -result[3] / 2.0;
        } else {
            result[4] = adjustValue(shift, defLimits[4]);
            result[3] = -result[4] / 2.0;
        }

        defLimits = result;
        Logger.debug("New circle limits - " + Utils.toString(defLimits));
    }

    private static double adjustValue(final double value, final double limit) {
        if (value != 0 && Math.signum(limit) != Math.signum(value)) {
            Logger.debug("Signum mismatch - {0} vs {1}", new Object[]{value, limit});
            return value;
        }
        final double val = Math.abs(value);
        final double lim = Math.abs(limit);

        final double result;
        if (val < (lim / 3.0)) {
            result = lim * ADJUST_COEFF_DOWN;
        } else if (val < (lim * 2 / 3.0)) {
            result = lim;
        } else {
            result = lim * ADJUST_COEFF_UP;
        }
        return result;
    }

    private void setROIs(final int round) {
        final Set<ROI> rois = new HashSet<>(4);
        rois.add(topLeft);
        rois.add(topRight);
        rois.add(bottomLeft);
        rois.add(bottomRight);

        tc.setROIs(round, rois);

        CircularROI cr;
        for (ROI roi : rois) {
            cr = (CircularROI) roi;
            tc.addFacetSize(round, roi, (int) (cr.getRadius() / ROI_CIRCLE_FS_DENOM));
            tc.setDeformationLimits(round, roi, defLimits);
        }
    }

    public double getShiftTop() {
        return shiftTop;
    }

    public double getShiftBottom() {
        return shiftBottom;
    }

}