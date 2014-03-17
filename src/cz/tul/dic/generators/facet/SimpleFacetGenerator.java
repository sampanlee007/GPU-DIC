package cz.tul.dic.generators.facet;

import cz.tul.dic.data.Facet;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.data.task.TaskParameter;
import cz.tul.dic.data.roi.ROI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SimpleFacetGenerator implements IFacetGenerator {

    private static final int DEFAULT_SPACING = 1;

    @Override
    public List<Facet> generateFacets(TaskContainer tc, final int round) {
        Object o = tc.getParameter(TaskParameter.FACET_GENERATOR_SPACING);
        final int spacing;
        if (o == null) {
            spacing = DEFAULT_SPACING;
        } else {
            spacing = (int) o;
        }
        final int facetSize = tc.getFacetSize();

        if (spacing >= facetSize) {
            throw new IllegalArgumentException("Spacing cant must be smaller than facet size.");
        }

        final int halfSize = facetSize / 2;
        final Set<ROI> rois = tc.getRoi(round);

        final List<Facet> result = new ArrayList<>();

        int roiW, roiH, wCount, hCount, gapX, gapY, centerX, centerY;
        for (ROI roi : rois) {
            roiW = roi.getWidth();
            roiH = roi.getHeight();

            wCount = (roiW - spacing) / (facetSize - spacing);
            hCount = (roiH - spacing) / (facetSize - spacing);

            gapX = (roiW - ((facetSize - spacing) * wCount + spacing)) / 2;
            gapY = (roiH - ((facetSize - spacing) * hCount + spacing)) / 2;

            for (int y = 0; y < hCount; y++) {
                centerY = gapY + roi.getY1() + halfSize + (y * (facetSize - spacing));

                for (int x = 0; x < wCount; x++) {
                    centerX = gapX + roi.getX1() + halfSize + (x * (facetSize - spacing));

                    if (roi.isAreaInside(centerX - halfSize, centerY - halfSize, centerX + halfSize, centerY + halfSize)) {
                        result.add(Facet.createFacet(facetSize, centerX, centerY));
                    }
                }
            }
        }

        return result;
    }

    @Override
    public FacetGeneratorMode getMode() {
        return FacetGeneratorMode.CLASSIC;
    }

}
