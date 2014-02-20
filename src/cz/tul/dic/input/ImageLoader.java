package cz.tul.dic.input;

import cz.tul.dic.data.Image;
import cz.tul.dic.data.TaskContainer;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ImageLoader implements IInputLoader {

    private static final Class TYPE = List.class;

    @Override
    public List<Image> loadData(Object in, TaskContainer tc) throws IOException {        
        if (!TYPE.isAssignableFrom(in.getClass())) {
            throw new IllegalArgumentException("ImageLoader needs a list of files as input.");
        }

        final List<File> data = (List<File>) in;
        final List<Image> result = new ArrayList<>(data.size());

        Image img;
        for (int i = 0; i < data.size(); i++) {
            img = Image.loadImageFromDisk(data.get(i));
            result.add(img);
        }

        return result;
    }

    @Override
    public Class getSupporteType() {
        return TYPE;
    }

}
