package cz.tul.dic.output.data;

import cz.tul.dic.ComputationException;
import cz.tul.dic.ComputationExceptionCause;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.data.task.TaskContainerUtils;
import cz.tul.dic.output.Direction;
import cz.tul.dic.output.ExportUtils;

public class ExportModeMap implements IExportMode<double[][]> {

    @Override
    public double[][] exportData(TaskContainer tc, Direction direction, int[] dataParams) throws ComputationException {
        if (dataParams == null || dataParams.length < 1) {
            throw new IllegalArgumentException("Not wnough input parameters (position required).");
        }
        final int round = dataParams[0];
        final int roundZero = TaskContainerUtils.getFirstRound(tc);
        final double[][][] results;
        switch (direction) {
            case dDx:
            case dDy:
            case dDabs:
                results = tc.getDisplacement(round, round + 1);
                break;
            case Dx:
            case Dy:
            case Dabs:
                results = TaskContainerUtils.getDisplacement(tc, roundZero, round);
                break;
            case Exx:
            case Eyy:
            case Exy:
            case Eabs:
                results = tc.getStrain(roundZero, round);
                break;
            default:
                throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "Unsupported direction.");
        }
        if (results == null || results.length == 0 || results[0].length == 0) {
            return null;
        }

        final int width = results.length;
        final int height = results[0].length;

        final double[][] result = new double[width][height];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (results[x][y] == null) {
                    result[x][y] = Double.NaN;
                    continue;
                }

                switch (direction) {
                    case dDx:
                    case dDy:
                    case dDabs:
                    case Dx:
                    case Dy:
                    case Dabs:
                        result[x][y] = ExportUtils.calculateDisplacement(results[x][y], direction);
                        break;
                    case Exx:
                    case Eyy:
                    case Exy:
                    case Eabs:
                        result[x][y] = ExportUtils.calculateStrain(results[x][y], direction);
                        break;
                    default:
                        throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "Unsupported direction.");
                }
            }
        }

        return result;
    }

}
