/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.engine.opencl.kernels;

import cz.tul.dic.engine.opencl.kernels.sources.KernelSourcePreparator;
import cz.tul.dic.engine.opencl.memory.AbstractOpenCLMemoryManager;
import com.jogamp.opencl.CLBuffer;
import com.jogamp.opencl.CLCommandQueue;
import com.jogamp.opencl.CLContext;
import com.jogamp.opencl.CLException;
import com.jogamp.opencl.CLKernel;
import com.jogamp.opencl.CLMemory;
import com.jogamp.opencl.CLProgram;
import com.jogamp.opencl.CLResource;
import cz.tul.dic.ComputationException;
import cz.tul.dic.ComputationExceptionCause;
import cz.tul.dic.data.deformation.DeformationDegree;
import cz.tul.dic.data.deformation.DeformationUtils;
import cz.tul.dic.debug.IGPUResultsReceiver;
import cz.tul.dic.debug.Stats;
import cz.tul.dic.engine.opencl.DeviceManager;
import cz.tul.dic.data.result.CorrelationResult;
import cz.tul.dic.data.task.ComputationTask;
import cz.tul.dic.engine.opencl.WorkSizeManager;
import cz.tul.dic.data.Interpolation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.pmw.tinylog.Logger;

/**
 *
 * @author Petr Jecmen
 */
public abstract class Kernel {

    private static final String CL_MEM_ERROR = "CL_OUT_OF_RESOURCES";
    private static final String KERNEL_REDUCE = "reduce";
    private static final String KERNEL_FIND_POS = "findPos";
    private static final List<IGPUResultsReceiver> resultListeners;
    protected final CLContext context;
    protected final CLCommandQueue queue;
    protected CLKernel kernelDIC, kernelReduce, kernelFindPos;
    private final String kernelName;
    private final Set<CLResource> clMem;
    private final AbstractOpenCLMemoryManager memManager;

    static {
        resultListeners = new LinkedList<>();
    }

    protected Kernel(String kernelName, final AbstractOpenCLMemoryManager memManager) {
        this.kernelName = kernelName;
        clMem = new HashSet<>();
        this.memManager = memManager;

        queue = DeviceManager.getQueue();
        context = DeviceManager.getContext();
    }

    public static Kernel createKernel(final KernelType kernelType, final AbstractOpenCLMemoryManager memManager) {
        Kernel result;
        try {
            final Class<?> cls = Class.forName("cz.tul.dic.engine.opencl.kernels.".concat(kernelType.toString()));
            result = (Kernel) cls.getConstructor(AbstractOpenCLMemoryManager.class).newInstance(memManager);

            if (!kernelType.isSafeToUse()) {
                Logger.warn("Kernel \"{0}\" is not safe to use, results might be inprecise !!!.", kernelType);
            }
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NoSuchMethodException | SecurityException | IllegalArgumentException | InvocationTargetException ex) {
            Logger.warn("Error instantiating class {0}, using default kernel.", kernelType);
            Logger.error(ex);
            result = new CL1D_I_V_LL_MC_D(memManager);
        }
        return result;
    }

    public void prepareKernel(final int subsetSize, final DeformationDegree deg, final Interpolation interpolation) throws ComputationException {
        try {
            CLProgram program = context.createProgram(KernelSourcePreparator.prepareKernel(kernelName, subsetSize, deg, usesVectorization(), interpolation, usesImage())).build();
            clMem.add(program);
            kernelDIC = program.createCLKernel(kernelName);
            clMem.add(kernelDIC);

            try (BufferedReader bin = new BufferedReader(new InputStreamReader(WorkSizeManager.class.getResourceAsStream(KERNEL_REDUCE.concat(KernelSourcePreparator.KERNEL_EXTENSION))))) {
                final StringBuilder sb = new StringBuilder();
                while (bin.ready()) {
                    sb.append(bin.readLine());
                    sb.append("\n");
                }
                program = context.createProgram(sb.toString()).build();
                clMem.add(program);
                kernelReduce = program.createCLKernel(KERNEL_REDUCE);
                clMem.add(kernelReduce);
            }

            try (BufferedReader bin = new BufferedReader(new InputStreamReader(WorkSizeManager.class.getResourceAsStream(KERNEL_FIND_POS.concat(KernelSourcePreparator.KERNEL_EXTENSION))))) {
                final StringBuilder sb = new StringBuilder();
                while (bin.ready()) {
                    sb.append(bin.readLine());
                    sb.append("\n");
                }
                program = context.createProgram(sb.toString()).build();
                clMem.add(program);
                kernelFindPos = program.createCLKernel(KERNEL_FIND_POS);
                clMem.add(kernelFindPos);
            }
        } catch (IOException ex) {
            Logger.debug(ex);
            throw new ComputationException(ComputationExceptionCause.OPENCL_ERROR, ex);
        }
    }

    public List<CorrelationResult> compute(final ComputationTask task, boolean findBest) throws ComputationException {
        if (task.getSubsets().isEmpty()) {
            Logger.warn("Empty subsets for computation.");
            return new ArrayList<>(0);
        }
        final int subsetCount = task.getSubsets().size();
        final int subsetSize = task.getSubsets().get(0).getSize();

        final List<CorrelationResult> result;
        try {
            memManager.assignData(task, this);
            final CLBuffer<FloatBuffer> clResults = memManager.getClResults();
            final long maxDeformationCount = memManager.getMaxDeformationCount();

            runKernel(memManager.getClImageA(), memManager.getClImageB(),
                    memManager.getClFacetData(), memManager.getClFacetCenters(),
                    memManager.getClDeformationLimits(), memManager.getClDefStepCount(),
                    clResults,
                    maxDeformationCount,
                    task.getImageA().getWidth(), subsetSize, subsetCount);
            queue.flush();

            if (!resultListeners.isEmpty()) {
                queue.putReadBuffer(clResults, true);
                final double[] results = readResultBuffer(clResults.getBuffer());
                for (IGPUResultsReceiver rr : resultListeners) {
                    rr.dumpGpuResults(results, task.getSubsets(), task.getDeformationLimits());
                }
            }

            if (findBest || Stats.getInstance().isGpuDebugEnabled()) {
                final CLBuffer<FloatBuffer> maxValuesCl = findMax(clResults, subsetCount, (int) maxDeformationCount);
                final int[] positions = findPos(clResults, subsetCount, (int) maxDeformationCount, maxValuesCl);

                result = createResults(readBuffer(maxValuesCl.getBuffer()), positions, task.getDeformationLimits());
            } else {
                result = null;
            }
            return result;
        } catch (CLException ex) {
            if (ex.getCLErrorString().contains(CL_MEM_ERROR)) {
                throw new ComputationException(ComputationExceptionCause.MEMORY_ERROR, ex);
            } else {
                throw ex;
            }
        } finally {
            memManager.unlockData();
        }
    }

    abstract void runKernel(
            final CLMemory<IntBuffer> imgA, final CLMemory<IntBuffer> imgB,
            final CLBuffer<IntBuffer> subsetData,
            final CLBuffer<FloatBuffer> subsetCenters,
            final CLBuffer<FloatBuffer> deformationLimits, final CLBuffer<LongBuffer> defStepCounts,
            final CLBuffer<FloatBuffer> results,
            final long maxDeformationCount, final int imageWidth,
            final int subsetSize, final int subsetCount);

    private CLBuffer<FloatBuffer> findMax(final CLBuffer<FloatBuffer> results, final int subsetCount, final int deformationCount) {
        final int lws0 = getMaxWorkItemSize();
        final CLBuffer<FloatBuffer> maxVal = context.createFloatBuffer(subsetCount, CLMemory.Mem.WRITE_ONLY);

        kernelReduce.rewind();
        kernelReduce.setArg(0, results);
        context.getCL().clSetKernelArg(kernelReduce.ID, 1, (long) lws0 * Float.BYTES, null);
        kernelReduce.setArg(2, maxVal);
        kernelReduce.setArg(3, deformationCount);
        kernelReduce.setArg(4, 0);
        kernelReduce.rewind();

        for (int i = 0; i < subsetCount; i++) {
            kernelReduce.setArg(4, i);
            queue.put1DRangeKernel(kernelReduce, 0, lws0, lws0);
        }
        queue.putReadBuffer(maxVal, true);
        queue.flush();

        return maxVal;
    }

    protected int getMaxWorkItemSize() {
        return DeviceManager.getDevice().getMaxWorkItemSizes()[0];
    }

    private int[] findPos(final CLBuffer<FloatBuffer> results, final int subsetCount, final int deformationCount, final CLBuffer<FloatBuffer> vals) {
        final int lws0 = getMaxWorkItemSize();
        final CLBuffer<IntBuffer> maxVal = context.createIntBuffer(subsetCount, CLMemory.Mem.WRITE_ONLY);

        kernelFindPos.rewind();
        kernelFindPos.setArg(0, results);
        kernelFindPos.setArg(1, vals);
        kernelFindPos.setArg(2, maxVal);
        kernelFindPos.setArg(3, deformationCount);
        kernelFindPos.setArg(4, 0);
        kernelFindPos.rewind();

        for (int i = 0; i < subsetCount; i++) {
            kernelFindPos.setArg(4, i);
            queue.put1DRangeKernel(kernelFindPos, 0, Kernel.roundUp(lws0, deformationCount), lws0);
        }
        queue.putReadBuffer(maxVal, true);
        final int[] result = readBuffer(maxVal.getBuffer());

        results.release();
        maxVal.release();
        return result;
    }

    private List<CorrelationResult> createResults(final float[] values, final int[] positions, final List<double[]> deformationLimits) throws ComputationException {
        if (values.length != positions.length) {
            throw new IllegalArgumentException("Array lengths mismatch.");
        }

        final List<CorrelationResult> result = new ArrayList<>(values.length);

        double[] limits;
        long[] counts;
        for (int f = 0; f < deformationLimits.size(); f++) {

            limits = deformationLimits.get(f);
            counts = DeformationUtils.generateDeformationCounts(limits);

            result.add(new CorrelationResult(values[f], DeformationUtils.extractDeformation(positions[f], limits, counts)));
        }

        return result;
    }

    public boolean usesMemoryCoalescing() {
        return false;
    }

    public boolean usesVectorization() {
        return false;
    }

    public boolean usesImage() {
        return false;
    }

    public void clearMemory() {
        clearMem(clMem);
    }

    private void clearMem(final Set<CLResource> mems) {
        for (CLResource mem : mems) {
            if (mem != null && !mem.isReleased()) {
                mem.release();
            }
        }
        mems.clear();
    }

    private float[] readBuffer(final FloatBuffer buffer) {
        buffer.rewind();
        final float[] result = new float[buffer.remaining()];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.get(i);
        }
        buffer.rewind();
        return result;
    }

    private int[] readBuffer(final IntBuffer buffer) {
        buffer.rewind();
        final int[] result = new int[buffer.remaining()];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.get(i);
        }
        buffer.rewind();
        return result;
    }
    
    private double[] readResultBuffer(final FloatBuffer resultsBuffer) {
        resultsBuffer.rewind();
        final double[] result = new double[resultsBuffer.remaining()];
        for (int i = 0; i < result.length; i++) {
            result[i] = resultsBuffer.get(i);
        }
        resultsBuffer.rewind();
        return result;
    }

    @Override
    public String toString() {
        return kernelName;
    }

    public abstract void stopComputation();

    static long roundUp(long groupSize, long globalSize) {
        long r = globalSize % groupSize;
        long result;
        if (r == 0) {
            result = globalSize;
        } else {
            result = globalSize + groupSize - r;
        }
        return result;
    }

    public static void registerListener(final IGPUResultsReceiver listener) {
        resultListeners.add(listener);
        Logger.debug("Registering {0} for GPU results.", listener);
    }

    public static void deregisterListener(final IGPUResultsReceiver listener) {
        resultListeners.remove(listener);
        Logger.debug("Deregistering {0} for GPU results.", listener);
    }

}
