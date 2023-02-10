package change;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.Collection;
import java.util.stream.Collectors;

public class Files {
    private Files() {
    }

    public static String[] collectSrcFiles(String rootPath) {
        Collection<File> files = FileUtils.listFiles(new File(rootPath), new String[]{"c", "cpp"}, true);
        String[] strings = files.stream().map(File::getAbsolutePath).collect(Collectors.toList()).toArray(String[]::new);
        return strings;
    }
}
