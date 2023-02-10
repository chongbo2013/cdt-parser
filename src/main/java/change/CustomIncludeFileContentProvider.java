package change;

import org.eclipse.cdt.core.index.IIndexFileLocation;
import org.eclipse.cdt.core.parser.FileContent;
import org.eclipse.cdt.internal.core.parser.IMacroDictionary;
import org.eclipse.cdt.internal.core.parser.scanner.InternalFileContent;
import org.eclipse.cdt.internal.core.parser.scanner.InternalFileContentProvider;

import java.nio.charset.StandardCharsets;

public class CustomIncludeFileContentProvider extends InternalFileContentProvider {
    private final String encoding = StandardCharsets.UTF_8.name();
    @Override
    public InternalFileContent getContentForInclusion(String filePath, IMacroDictionary macroDictionary) {
        if (!getInclusionExists(filePath)) {
            return null;
        }
        return (InternalFileContent) FileContent.createForExternalFileLocation(
                filePath, true, encoding);
    }

    @Override
    public InternalFileContent getContentForInclusion(IIndexFileLocation ifl, String astPath) {
        return (InternalFileContent) FileContent.createForExternalFileLocation(
                ifl.getURI().getPath(), true, encoding);
    }
}
