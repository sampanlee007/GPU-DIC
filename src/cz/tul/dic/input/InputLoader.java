package cz.tul.dic.input;

import cz.tul.dic.data.TaskContainer;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Petr Jecmen
 */
public class InputLoader {

    private static final Map<Class, IInputLoader> loaders;

    static {
        loaders = new HashMap<>();

        IInputLoader il = new VideoLoader();
        loaders.put(il.getSupporteType(), il);
        
        il = new ImageLoader();
        loaders.put(il.getSupporteType(), il);
    }

    public static void loadInput(final Object in, final TaskContainer tc) throws IOException {
        final Class cls = in.getClass();
        if (loaders.containsKey(cls)) {
            loaders.get(cls).loadData(in, tc);
        } else {
            throw new IllegalArgumentException("Unsupported type of input data - " + cls.toString());
        }
    }

}
