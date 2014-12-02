/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cz.tul.dic.data.task.splitter;

import cz.tul.dic.data.Facet;
import cz.tul.dic.data.Image;
import cz.tul.dic.data.task.ComputationTask;
import java.util.List;

public class NoSplit extends TaskSplitter {

    private boolean hasNext;

    public NoSplit(Image image1, Image image2, final List<Facet> facets, final List<double[]> deformationLimits) {
        super(image1, image2, facets, deformationLimits);
        hasNext = true;
    }

    @Override
    public boolean hasNext() {
        return hasNext;
    }

    @Override
    public ComputationTask next() {
        hasNext = false;
        return new ComputationTask(image1, image2, facets, deformationLimits, false);
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public void signalTaskSizeTooBig() {
    }

    @Override
    public boolean isSplitterReady() {
        return hasNext;
    }

    @Override
    public void resetTaskSize() {        
    }

}
