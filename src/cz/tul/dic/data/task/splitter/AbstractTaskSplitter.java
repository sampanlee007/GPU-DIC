/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.data.task.splitter;

import cz.tul.dic.ComputationException;
import cz.tul.dic.ComputationExceptionCause;
import cz.tul.dic.data.Facet;
import cz.tul.dic.data.Image;
import cz.tul.dic.data.task.ComputationTask;
import java.util.Iterator;
import java.util.List;

/**
 *
 * @author Petr Ječmen
 */
public abstract class AbstractTaskSplitter implements Iterator<ComputationTask> {

    protected final Image image1, image2;
    protected final List<Facet> facets;
    protected final List<double[]> deformationLimits;

    public AbstractTaskSplitter(final Image image1, final Image image2, final List<Facet> facets, final List<double[]> deformationLimits) {
        this.image1 = image1;
        this.image2 = image2;
        this.facets = facets;
        this.deformationLimits = deformationLimits;
    }

    public static AbstractTaskSplitter prepareSplitter(final Image image1, final Image image2, final List<Facet> facets, final List<double[]> deformationLimits, final TaskSplitMethod ts, final Object taskSplitValue) throws ComputationException {
        switch (ts) {
            case NONE:
                return new NoSplit(image1, image2, facets, deformationLimits);
            case STATIC:
                return new StaticSplit(image1, image2, facets, deformationLimits, taskSplitValue);
            case DYNAMIC:
                return new OpenCLSplitter(image1, image2, facets, deformationLimits);
            default:
                throw new ComputationException(ComputationExceptionCause.ILLEGAL_TASK_DATA, "Unsupported type of task splitting - " + ts);
        }
    }

    public abstract void signalTaskSizeTooBig();    

    public abstract boolean isSplitterReady();

}