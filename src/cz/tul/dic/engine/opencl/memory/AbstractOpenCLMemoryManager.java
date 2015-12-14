/* Copyright (C) LENAM, s.r.o. - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Petr Jecmen <petr.jecmen@tul.cz>, 2015
 */
package cz.tul.dic.engine.opencl.memory;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opencl.CLBuffer;
import com.jogamp.opencl.CLCommandQueue;
import com.jogamp.opencl.CLContext;
import com.jogamp.opencl.CLImage2d;
import com.jogamp.opencl.CLImageFormat;
import com.jogamp.opencl.CLMemory;
import com.jogamp.opencl.CLResource;
import cz.tul.dic.ComputationException;
import cz.tul.dic.data.AppSettings;
import cz.tul.dic.data.subset.AbstractSubset;
import cz.tul.dic.data.Coordinates;
import cz.tul.dic.data.Image;
import cz.tul.dic.data.deformation.DeformationUtils;
import cz.tul.dic.data.subset.SubsetUtils;
import cz.tul.dic.data.task.ComputationTask;
import cz.tul.dic.data.task.TaskContainer;
import cz.tul.dic.engine.opencl.DeviceManager;
import cz.tul.dic.engine.opencl.kernel.Kernel;
import cz.tul.dic.engine.opencl.OpenCLDataPackage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 * @author Petr Ječmen
 */
public abstract class AbstractOpenCLMemoryManager {
    
    private static final CLImageFormat.ChannelOrder IMAGE_ORDER = CLImageFormat.ChannelOrder.R;
    private static final CLImageFormat.ChannelType IMAGE_TYPE = CLImageFormat.ChannelType.UNSIGNED_INT8;
    private static final Map<Type, AbstractOpenCLMemoryManager> INSTANCES;
    protected long maxDeformationCount;
    // OpenCL entities
    protected CLMemory<ByteBuffer> clImageA, clImageB;
    protected CLBuffer<IntBuffer> clSubsetData;
    protected CLBuffer<FloatBuffer> clSubsetCenters;
    protected CLBuffer<IntBuffer> clSubsetWeights;
    protected CLBuffer<FloatBuffer> clDeformations;
    protected CLBuffer<LongBuffer> clDefStepCount;
    protected CLBuffer<FloatBuffer> clResults;
    // OpenCL context        
    protected CLCommandQueue queue;
    protected CLContext context;
    private final Lock lock;

    static {
        DeviceManager.getContext();
        DeviceManager.clearMemory();

        INSTANCES = new EnumMap<>(Type.class);
        INSTANCES.put(Type.STATIC, new StaticMemoryManager());
        INSTANCES.put(Type.DYNAMIC, new DynamicMemoryManager());
        INSTANCES.put(Type.PREFETCH, new StaticMemoryManager());
    }

    protected AbstractOpenCLMemoryManager() {
        lock = new ReentrantLock();
        context = DeviceManager.getContext();
        queue = DeviceManager.getQueue();
    }

    public static AbstractOpenCLMemoryManager getInstance() {
        return INSTANCES.get(AppSettings.getInstance().getMemManagerType());
    }

    public void assignData(final ComputationTask task, final Kernel kernel) throws ComputationException {
        lock.lock();
        context = DeviceManager.getContext();
        queue = DeviceManager.getQueue();
        maxDeformationCount = DeformationUtils.findMaxDeformationCount(task.getDeformations(), task.getOrder(), task.usesLimits());
        assignDataToGPU(task, kernel);
    }

    public abstract void assignDataToGPU(final ComputationTask task, final Kernel kernel) throws ComputationException;

    public abstract void assignTask(final TaskContainer task);

    public void unlockData() {
        lock.unlock();
    }

    protected CLImage2d<ByteBuffer> generateImage2d(final Image image) {
        return context.createImage2d(
                Buffers.newDirectByteBuffer(image.toFiltered()),
                image.getWidth(), image.getHeight(),
                new CLImageFormat(IMAGE_ORDER, IMAGE_TYPE), CLMemory.Mem.READ_ONLY);
    }

    protected CLBuffer<ByteBuffer> generateImageArray(final Image image) {
        final byte[] data = image.toBWArray();
        final CLBuffer<ByteBuffer> result = context.createByteBuffer(data.length, CLMemory.Mem.READ_ONLY);
        final ByteBuffer buffer = result.getBuffer();
        for (byte b : data) {
            buffer.put(b);
        }
        buffer.rewind();
        return result;
    }

    protected CLBuffer<IntBuffer> generateSubsetData(final List<AbstractSubset> subsets, final boolean useMemoryCoalescing) {
        final int subsetSize = subsets.get(0).getSize();
        final int subsetArea = SubsetUtils.computeSubsetCoordCount(subsetSize);
        final int dataSize = subsetArea * Coordinates.DIMENSION;
        final int[] completeData = new int[subsets.size() * dataSize];

        int pointer = 0;
        int[] subsetData;
        for (AbstractSubset f : subsets) {
            subsetData = f.getData();
            System.arraycopy(subsetData, 0, completeData, pointer, dataSize);
            pointer += dataSize;
        }

        final CLBuffer<IntBuffer> result = context.createIntBuffer(completeData.length, CLMemory.Mem.READ_ONLY);
        final IntBuffer resultBuffer = result.getBuffer();
        if (useMemoryCoalescing) {
            int index;
            for (int i = 0; i < subsetArea; i++) {
                for (int f = 0; f < subsets.size(); f++) {
                    index = f * dataSize + 2 * i;
                    resultBuffer.put(completeData[index]);
                    resultBuffer.put(completeData[index + 1]);
                }
            }
        } else {
            for (int i : completeData) {
                resultBuffer.put(i);
            }
        }
        resultBuffer.rewind();
        return result;
    }

    protected CLBuffer<IntBuffer> generateSubsetWeights(final List<Integer> weights) {
        final CLBuffer<IntBuffer> result = context.createIntBuffer(weights.size(), CLMemory.Mem.READ_ONLY);
        final IntBuffer resultBuffer = result.getBuffer();
        for (int i : weights) {
            resultBuffer.put(i);
        }
        resultBuffer.rewind();
        return result;
    }

    protected CLBuffer<FloatBuffer> generateSubsetCenters(final List<AbstractSubset> subsets) {
        final int dataSize = Coordinates.DIMENSION;
        final float[] data = new float[subsets.size() * dataSize];

        int pointer = 0;
        double[] centerData;
        for (AbstractSubset f : subsets) {
            centerData = f.getCenter();
            for (int i = 0; i < dataSize; i++) {
                data[pointer + i] = (float) centerData[i];
            }
            pointer += dataSize;
        }

        final CLBuffer<FloatBuffer> result = context.createFloatBuffer(data.length, CLMemory.Mem.READ_ONLY);
        final FloatBuffer buffer = result.getBuffer();
        for (float f : data) {
            buffer.put(f);
        }
        buffer.rewind();
        return result;
    }

    protected CLBuffer<FloatBuffer> generateDeformationLimits(final List<double[]> deformationLimits) {
        final CLBuffer<FloatBuffer> result = context.createFloatBuffer(deformationLimits.size() * deformationLimits.get(0).length, CLMemory.Mem.READ_ONLY);
        final FloatBuffer buffer = result.getBuffer();
        for (double[] dA : deformationLimits) {
            for (double d : dA) {
                buffer.put((float) d);
            }
        }
        buffer.rewind();
        return result;
    }

    protected CLBuffer<LongBuffer> generateDeformationStepCounts(final List<long[]> counts) {
        final CLBuffer<LongBuffer> result = context.createLongBuffer(counts.size() * counts.get(0).length, CLMemory.Mem.READ_ONLY);
        final LongBuffer buffer = result.getBuffer();
        for (long[] iA : counts) {
            for (long i : iA) {
                buffer.put(i);
            }
        }
        buffer.rewind();
        return result;
    }

    protected void release(final CLResource mem) {
        if (mem != null && !mem.isReleased()) {
            mem.release();
        }
    }

    public void clearMemory() {
        release(clDefStepCount);
        release(clDeformations);
        release(clSubsetCenters);
        release(clSubsetData);
        release(clImageA);
        release(clImageB);
        release(clResults);
    }

    public OpenCLDataPackage getData() {
        return new OpenCLDataPackage(
                clImageA, clImageB,
                clSubsetData, clSubsetCenters, clSubsetWeights,
                clDeformations, clDefStepCount,
                clResults);
    }

    public long getMaxDeformationCount() {
        return maxDeformationCount;
    }

    public enum Type {
        STATIC,
        DYNAMIC,
        PREFETCH
    }

}
