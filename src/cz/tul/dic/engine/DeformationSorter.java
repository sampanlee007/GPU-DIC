package cz.tul.dic.engine;

import cz.tul.dic.data.deformation.DeformationUtils;
import java.io.Serializable;
import java.util.Comparator;

/**
 *
 * @author Petr Ječmen
 */
public class DeformationSorter implements Comparator<double[]>, Serializable {

    @Override
    public int compare(double[] o1, double[] o2) {
        return Double.compare(DeformationUtils.getAbs(o1), DeformationUtils.getAbs(o2));
    }
    
}
