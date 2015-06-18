/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.engine.opencl.solvers;

import cz.tul.dic.data.result.CorrelationResult;
import com.jogamp.opencl.CLException;
import cz.tul.dic.ComputationException;
import cz.tul.dic.ComputationExceptionCause;
import cz.tul.dic.data.Facet;
import cz.tul.dic.data.deformation.DeformationDegree;
import cz.tul.dic.data.deformation.DeformationUtils;
import cz.tul.dic.data.task.ComputationTask;
import cz.tul.dic.data.task.TaskDefaultValues;
import cz.tul.dic.data.task.splitter.TaskSplitMethod;
import cz.tul.dic.data.task.splitter.AbstractTaskSplitter;
import cz.tul.dic.data.task.FullTask;
import cz.tul.dic.engine.opencl.DeviceManager;
import cz.tul.dic.engine.opencl.WorkSizeManager;
import cz.tul.dic.engine.opencl.kernels.Kernel;
import cz.tul.dic.engine.opencl.kernels.KernelType;
import cz.tul.dic.engine.opencl.interpolation.Interpolation;
import cz.tul.dic.engine.opencl.memory.AbstractOpenCLMemoryManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import org.pmw.tinylog.Logger;

/**
 *
 * @author Petr Jecmen
 */
public abstract class AbstractTaskSolver extends Observable {

    final AbstractOpenCLMemoryManager memManager;
    // dynamic
    KernelType kernelType;
    Interpolation interpolation;
    TaskSplitMethod taskSplitVariant;
    Kernel kernel;
    int facetSize;
    Object taskSplitValue;
    boolean stop;    

    protected AbstractTaskSolver() {
        memManager = AbstractOpenCLMemoryManager.init();

        kernelType = WorkSizeManager.getBestKernel();
        interpolation = TaskDefaultValues.DEFAULT_INTERPOLATION;
        taskSplitVariant = TaskDefaultValues.DEFAULT_TASK_SPLIT_METHOD;
        taskSplitValue = null;
    }
    
    public static AbstractTaskSolver initSolver(final Solver type) {
        try {
            final Class<?> cls = Class.forName("cz.tul.dic.engine.opencl.solvers.".concat(type.toString()));
            return (AbstractTaskSolver) cls.newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException ex) {
            Logger.warn("Error instantiating class {0}, using default correlation calculator.", type);
            Logger.error(ex);
            return new BruteForce();
        }
    }

    public void endTask() {
        memManager.clearMemory();
        kernel.clearMemory();
        DeviceManager.clearMemory();
    }

    public synchronized List<CorrelationResult> solve(
            final FullTask fullTask, DeformationDegree defDegree,
            int facetSize) throws ComputationException {
        stop = false;

        kernel = Kernel.createKernel(kernelType, memManager);
        Logger.trace("Kernel prepared - {0}", kernel);

        this.facetSize = facetSize;

        final List<CorrelationResult> result = solve(kernel, fullTask, defDegree);

        kernel.clearMemory();

        return result;
    }

    public abstract List<CorrelationResult> solve(
            final Kernel kernel, 
            final FullTask fullTask, DeformationDegree defDegree) throws ComputationException;

    abstract boolean needsBestResult();

    protected synchronized List<CorrelationResult> computeTask(
            final Kernel kernel, final FullTask fullTask, DeformationDegree defDegree) throws ComputationException {
        final List<CorrelationResult> result = new ArrayList<>(fullTask.getFacets().size());
        for (int i = 0; i < fullTask.getFacets().size(); i++) {
            result.add(null);
        }

        try {
            kernel.prepareKernel(facetSize, defDegree, interpolation);

            AbstractTaskSplitter ts = AbstractTaskSplitter.prepareSplitter(fullTask, taskSplitVariant, taskSplitValue);
            boolean finished = false;
            Exception lastEx = null;
            while (ts.isSplitterReady() && !finished) {
                try {
                    ComputationTask ct;
                    CorrelationResult bestSubResult = null;
                    while (ts.hasNext()) {
                        if (stop) {
                            return result;
                        }

                        ct = ts.next();
                        ct.setResults(kernel.compute(ct, needsBestResult()));
                        // pick best results for this computation task and discard ct data 
                        if (ct.isSubtask()) {
                            bestSubResult = pickBetterResult(bestSubResult, ct.getResults().get(0));
                        } else if (bestSubResult != null) {
                            bestSubResult = pickBetterResult(bestSubResult, ct.getResults().get(0));
                            // store result
                            final int globalFacetIndex = fullTask.getFacets().indexOf(ct.getFacets().get(0));
                            if (globalFacetIndex < 0) {
                                throw new IllegalArgumentException("Local facet not found in global registry.");
                            }
                            result.set(globalFacetIndex, bestSubResult);
                            bestSubResult = null;
                        } else {
                            pickBestResultsForTask(ct, result, fullTask.getFacets());
                        }
                    }

                    finished = true;
                } catch (ComputationException ex) {
                    memManager.clearMemory();
                    if (ex instanceof ComputationException) {                        
                        if (ex.getExceptionCause().equals(ComputationExceptionCause.MEMORY_ERROR)) {
                            Logger.warn(ex);
                            ts.signalTaskSizeTooBig();
                            ts = AbstractTaskSplitter.prepareSplitter(fullTask, taskSplitVariant, taskSplitValue);
                            lastEx = ex;
                        } else {
                            throw ex;
                        }
                    } else {
                        throw ex;
                    }
                }
            }

            if (!finished && lastEx != null) {
                memManager.clearMemory();
                throw new ComputationException(ComputationExceptionCause.OPENCL_ERROR, lastEx);
            }
        } catch (CLException ex) {
            memManager.clearMemory();
            Logger.debug(ex);
            throw new ComputationException(ComputationExceptionCause.OPENCL_ERROR, ex);
        }

        Logger.trace("Found solution for {0} facets.", result.size());

        return result;
    }

    private CorrelationResult pickBetterResult(final CorrelationResult r1, final CorrelationResult r2) {
        final CorrelationResult result;
        if (r1 == null) {
            result = r2;
        } else if (Float.compare(r1.getValue(), r2.getValue()) == 0) {
            result = DeformationUtils.getAbs(r1.getDeformation()) < DeformationUtils.getAbs(r2.getDeformation()) ? r1 : r2;
        } else {
            result = r1.getValue() > r2.getValue() ? r1 : r2;
        }
        return result;
    }

    private void pickBestResultsForTask(final ComputationTask task, final List<CorrelationResult> bestResults, final List<Facet> globalFacets) throws ComputationException {
        final List<Facet> localFacets = task.getFacets();
        final int facetCount = localFacets.size();

        int globalFacetIndex;
        final List<CorrelationResult> taskResults = task.getResults();
        for (int localFacetIndex = 0; localFacetIndex < facetCount; localFacetIndex++) {
            globalFacetIndex = globalFacets.indexOf(localFacets.get(localFacetIndex));
            if (globalFacetIndex < 0) {
                throw new IllegalArgumentException("Local facet not found in global registry.");
            }

            if (localFacetIndex >= taskResults.size()) {
                Logger.warn("No best value found for facet nr." + globalFacetIndex);
                bestResults.set(globalFacetIndex, new CorrelationResult(-1, null));
            } else {
                bestResults.set(globalFacetIndex, taskResults.get(localFacetIndex));
            }
        }
    }

    public void setKernel(KernelType kernel) {
        this.kernelType = kernel;
    }

    public void setInterpolation(Interpolation interpolation) {
        this.interpolation = interpolation;
    }

    public void setTaskSplitVariant(TaskSplitMethod taskSplitVariant, Object taskSplitValue) {
        this.taskSplitVariant = taskSplitVariant;
        this.taskSplitValue = taskSplitValue;
    }

    public void stop() {
        stop = true;
        if (kernel != null) {
            kernel.stopComputation();
        }
        endTask();
        Logger.debug("Stopping correlation counter.");
    }

}