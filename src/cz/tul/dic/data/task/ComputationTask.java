package cz.tul.dic.data.task;

import cz.tul.dic.data.Facet;
import cz.tul.dic.data.Image;
import java.util.List;

/**
 *
 * @author Petr Ječmen
 */
public class ComputationTask {

    private final Image imageA, imageB;
    private final List<Facet> facets;
    private final double[] deformationLimits;
    private final boolean subtask;
    private float[] results;

    public ComputationTask(Image imageA, Image imageB, List<Facet> facets, double[] deformationLimits, boolean subtask) {
        this.imageA = imageA;
        this.imageB = imageB;
        this.facets = facets;
        this.deformationLimits = deformationLimits;
        this.subtask = subtask;
    }

    public Image getImageA() {
        return imageA;
    }

    public Image getImageB() {
        return imageB;
    }

    public List<Facet> getFacets() {
        return facets;
    }

    public double[] getDeformationLimits() {
        return deformationLimits;
    }

    public float[] getResults() {
        return results;
    }

    public void setResults(float[] results) {
        this.results = results;
    }

    public boolean isSubtask() {
        return subtask;
    }

}
