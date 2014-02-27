package cz.tul.dic.engine.opencl;

import cz.tul.dic.data.deformation.DeformationDegree;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.data.task.TaskContainerUtils;
import cz.tul.dic.data.task.TaskParameter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 *
 * @author Petr Jecmen
 */
public class KernelSourcePreparator {

    private static final String KERNEL_EXTENSION = ".cl";
    private static final String REPLACE_FACET_SIZE = "-1";
    private static final String REPLACE_DEFORMATION_X = "%DEF_X%";
    private static final String REPLACE_DEFORMATION_Y = "%DEF_Y%";
    private static final String REPLACE_DEFORMATION_DEGREE = "%DEF_D%";
    private static final String PLUS = " + ";
    private static final String MUL = " * ";
    private final String kernelName;
    private String kernel;

    public static String prepareKernel(final String kernelName, final TaskContainer tc, final boolean usesVectorization) throws IOException {
        final KernelSourcePreparator kp = new KernelSourcePreparator(kernelName);

        kp.loadKernel();
        kp.prepareFacetSize(tc.getFacetSize());
        kp.prepareDeformations(tc, usesVectorization);

        return kp.kernel;
    }

    private KernelSourcePreparator(final String kernelName) {
        this.kernelName = kernelName;
    }

    private void loadKernel() throws IOException {
        try (BufferedReader bin = new BufferedReader(new InputStreamReader(WorkSizeManager.class.getResourceAsStream(kernelName.concat(KERNEL_EXTENSION))))) {
            final StringBuilder sb = new StringBuilder();
            while (bin.ready()) {
                sb.append(bin.readLine());
                sb.append("\n");
            }
            kernel = sb.toString();
        }
    }

    private void prepareFacetSize(final int facetSize) {
        kernel = kernel.replaceAll(REPLACE_FACET_SIZE, Integer.toString(facetSize));
    }

    private void prepareDeformations(final TaskContainer tc, final boolean usesVectorization) {
        final DeformationDegree deg = (DeformationDegree) tc.getParameter(TaskParameter.DEFORMATION_DEGREE);
        final String x, y, dx, dy;
        if (usesVectorization) {
            x = "coords.x";
            y = "coords.y";
            dx = "def.x";
            dy = "def.y";
        } else {
            x = "x";
            y = "y";
            dx = "dx";
            dy = "dy";
        }
        final StringBuilder sb = new StringBuilder();
        switch (deg) {
            case ZERO:
                sb.append(x);
                sb.append(PLUS);
                sb.append("deformations[baseIndexDeformation]");
//                kernel = kernel.replaceFirst(REPLACE_DEFORMATION_X, "coords.x + deformations[baseIndexDeformation]");
                kernel = kernel.replaceFirst(REPLACE_DEFORMATION_X, sb.toString());

                sb.setLength(0);
                sb.append(y);
                sb.append(PLUS);
                sb.append("deformations[baseIndexDeformation + 1]");
//                kernel = kernel.replaceFirst(REPLACE_DEFORMATION_Y, "coords.y + deformations[baseIndexDeformation + 1]");
                kernel = kernel.replaceFirst(REPLACE_DEFORMATION_Y, sb.toString());                
                break;
            case FIRST:
                sb.append(x);
                sb.append(PLUS);
                sb.append("deformations[baseIndexDeformation]");
                sb.append(PLUS);
                sb.append("deformations[baseIndexDeformation + 2]");
                sb.append(MUL);
                sb.append(dx);
                sb.append(PLUS);
                sb.append("deformations[baseIndexDeformation + 4]");
                sb.append(MUL);
                sb.append(dy);
//                kernel = kernel.replaceFirst(REPLACE_DEFORMATION_X, "coords.x + deformations[baseIndexDeformation] + deformations[baseIndexDeformation + 2] * def.x + deformations[baseIndexDeformation + 4] * def.y");
                kernel = kernel.replaceFirst(REPLACE_DEFORMATION_X, sb.toString());
                
                sb.setLength(0);
                sb.append(y);
                sb.append(PLUS);
                sb.append("deformations[baseIndexDeformation + 1]");
                sb.append(PLUS);
                sb.append("deformations[baseIndexDeformation + 3]");
                sb.append(MUL);
                sb.append(dx);
                sb.append(PLUS);
                sb.append("deformations[baseIndexDeformation + 5]");
                sb.append(MUL);
                sb.append(dy);
//                kernel = kernel.replaceFirst(REPLACE_DEFORMATION_Y, "coords.y + deformations[baseIndexDeformation + 1] + deformations[baseIndexDeformation + 3] * def.x + deformations[baseIndexDeformation + 5] * def.y");
                kernel = kernel.replaceFirst(REPLACE_DEFORMATION_Y, sb.toString());                                
                break;
            case SECOND:
                throw new IllegalArgumentException("Second degree not supported yet");
            default:
                throw new IllegalArgumentException("Unsupported degree of deformation");
        }
        kernel = kernel.replaceFirst(REPLACE_DEFORMATION_DEGREE, Integer.toString(TaskContainerUtils.getDeformationArrayLength(tc)));
    }
}