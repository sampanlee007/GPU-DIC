package cz.tul.dic.engine;

import cz.tul.dic.ComputationException;
import cz.tul.dic.data.Facet;
import cz.tul.dic.data.deformation.DeformationUtils;
import cz.tul.dic.data.roi.ROI;
import cz.tul.dic.data.task.Hint;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.data.task.TaskContainerUtils;
import cz.tul.dic.data.task.TaskParameter;
import cz.tul.dic.data.task.splitter.TaskSplitMethod;
import cz.tul.dic.engine.displacement.DisplacementCalculator;
import cz.tul.dic.engine.opencl.KernelType;
import cz.tul.dic.engine.opencl.interpolation.Interpolation;
import cz.tul.dic.engine.strain.StrainEstimation;
import cz.tul.dic.generators.facet.FacetGenerator;
import cz.tul.dic.output.data.ExportMode;
import cz.tul.dic.output.ExportTask;
import cz.tul.dic.output.Exporter;
import cz.tul.dic.output.NameGenerator;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Set;
import org.pmw.tinylog.Logger;

/**
 *
 * @author Petr Ječmen
 */
public class Engine extends Observable {

    private static final float LIMIT_QUALITY = 0.5f;
    private static final Engine instance;
    private final CorrelationCalculator correlation;
    private final StrainEstimation strain;
    private final FineLocalSearch fls;

    static {
        instance = new Engine();
    }

    public static Engine getInstance() {
        return instance;
    }

    private Engine() {
        correlation = new CorrelationCalculator();
        fls = new FineLocalSearch();
        strain = new StrainEstimation();
    }

    public void computeTask(final TaskContainer tc) throws ComputationException, IOException {
        setChanged();
        notifyObservers(0);

        tc.clearResultData();
        TaskContainerUtils.checkTaskValidity(tc);

        final Set<Hint> hints = tc.getHints();
        int r, nextR, currentRound = 0;
        for (Map.Entry<Integer, Integer> e : TaskContainerUtils.getRounds(tc).entrySet()) {
            r = e.getKey();
            nextR = e.getValue();

            computeRound(tc, r, nextR);

            if (!hints.contains(Hint.NO_CUMULATIVE)) {
                setChanged();
                notifyObservers(new Object[]{currentRound, CumulativeResultsCounter.class});
                tc.setCumulativeDisplacements(CumulativeResultsCounter.calculate(tc, tc.getDisplacements()));
                tc.setCumulativeStrain(CumulativeResultsCounter.calculate(tc, tc.getStrains()));
            }

            exportRound(tc, r);

            currentRound++;
            setChanged();
            notifyObservers(currentRound);
        }

        Exporter.export(tc);
        TaskContainerUtils.serializeTaskToBinary(tc, new File(NameGenerator.generateBinary(tc)));
    }

    private void exportRound(final TaskContainer tc, final int round) throws IOException, ComputationException {
        Iterator<ExportTask> it = tc.getExports().iterator();
        ExportTask et;
        while (it.hasNext()) {
            et = it.next();
            if (et.getMode().equals(ExportMode.MAP) && et.getDataParams()[0] == round) {
                Exporter.export(tc, et);
            }
        }
    }

    public void computeRound(final TaskContainer tc, final int index1, final int index2) throws ComputationException {
        Logger.trace("Computing round {0} - {1}.", index1, tc);
        final Set<Hint> hints = tc.getHints();

        setChanged();
        notifyObservers(TaskContainerUtils.class);
        TaskContainerUtils.checkTaskValidity(tc);

        // prepare parameters
        correlation.setKernel((KernelType) tc.getParameter(TaskParameter.KERNEL));
        correlation.setInterpolation((Interpolation) tc.getParameter(TaskParameter.INTERPOLATION));
        final TaskSplitMethod taskSplit = (TaskSplitMethod) tc.getParameter(TaskParameter.TASK_SPLIT_METHOD);
        final Object taskSplitValue = tc.getParameter(TaskParameter.TASK_SPLIT_PARAM);
        correlation.setTaskSplitVariant(taskSplit);

        // prepare data
        setChanged();
        notifyObservers(FacetGenerator.class);
        final Map<ROI, List<Facet>> facets = FacetGenerator.generateFacets(tc, index1);
        Logger.trace("Facets generated.");

        // compute round        
        List<CorrelationResult> result;
        int counter;
        for (ROI roi : tc.getRois(index1)) {
            // compute and store result
            setChanged();
            notifyObservers(CorrelationCalculator.class);
            result = correlation.computeCorrelations(
                    tc.getImage(index1), tc.getImage(index2),
                    roi, facets.get(roi),
                    tc.getDeformationLimits(index1, roi),
                    DeformationUtils.getDegreeFromLimits(tc.getDeformationLimits(index1, roi)),
                    tc.getFacetSize(index1, roi), taskSplitValue);
            tc.setResult(index1, roi, result);

            counter = 0;
            for (CorrelationResult cr : result) {
                if (cr.getValue() < LIMIT_QUALITY) {
                    counter++;
                }
            }
            if (counter > 0) {
                Logger.warn("Found {0} result with quality lower than {1} (for ROI {2}).", counter, LIMIT_QUALITY, roi);
            }
        }

        setChanged();
        notifyObservers(DisplacementCalculator.class);
        DisplacementCalculator.computeDisplacement(tc, index1, facets);

        if (!hints.contains(Hint.NO_FINE_SEARCH)) {
            setChanged();
            notifyObservers(FineLocalSearch.class);
            fls.searchForBestPosition(tc, index1, index2);
        }

        if (!hints.contains(Hint.NO_STRAIN)) {
            setChanged();
            notifyObservers(StrainEstimation.class);
            strain.computeStrain(tc, index1);
        }

        Logger.debug("Computed round {0}.", index1);
    }

}
