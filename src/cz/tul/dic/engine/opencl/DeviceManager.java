package cz.tul.dic.engine.opencl;

import com.jogamp.opencl.CLContext;
import com.jogamp.opencl.CLDevice;
import com.jogamp.opencl.CLPlatform;
import com.jogamp.opencl.util.Filter;
import java.nio.ByteBuffer;

/**
 *
 * @author Petr Jecmen
 */
public class DeviceManager {

    private static final CLDevice.Type DEVICE_TYPE = CLDevice.Type.GPU;
    private static final CLPlatform platform;
    private static final CLContext context;
    private static final CLDevice device;

    static {
        @SuppressWarnings("unchecked")
        final CLPlatform tmpP = CLPlatform.getDefault((Filter<CLPlatform>) (CLPlatform i) -> i.getMaxFlopsDevice(CLDevice.Type.GPU) != null && i.listCLDevices(CLDevice.Type.CPU).length == 0);
        if (tmpP == null) {
            platform = CLPlatform.getDefault();
        } else {
            platform = tmpP;
        }

        final CLDevice tmpD = platform.getMaxFlopsDevice(DEVICE_TYPE);
        if (tmpD == null) {
            device = platform.getMaxFlopsDevice();
        } else {
            device = tmpD;
        }
        System.out.println("Using " + device);

        context = CLContext.create(device);
        context.addCLErrorHandler((String string, ByteBuffer bb, long l) -> {
            System.err.println("CLError - " + string);
        });
    }

    public static CLPlatform getPlatform() {
        return platform;
    }

    public static CLContext getContext() {
        return context;
    }

    public static CLDevice getDevice() {
        return device;
    }

}
