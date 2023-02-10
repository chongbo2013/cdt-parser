package change;

import org.eclipse.cdt.core.dom.ast.gnu.cpp.GPPLanguage;
import org.eclipse.cdt.core.index.IIndexFileLocation;
import org.eclipse.cdt.core.model.AbstractLanguage;
import org.eclipse.cdt.core.parser.FileContent;
import org.eclipse.cdt.core.parser.IScannerInfo;
import org.eclipse.cdt.internal.core.index.IndexFileLocation;
import org.eclipse.cdt.internal.core.pdom.AbstractIndexerTask;
import org.eclipse.cdt.internal.core.pdom.IndexerInputAdapter;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class CustomIndexerInputAdapter extends IndexerInputAdapter {
    @Override
    public IIndexFileLocation resolveASTPath(String astFilePath) {
        File file = new File(astFilePath);
        IndexFileLocation indexFileLocation = new IndexFileLocation(file.toURI(),file.getAbsolutePath());
        return indexFileLocation;
    }

    @Override
    public IIndexFileLocation resolveIncludeFile(String includePath) {
        return doesIncludeFileExist(includePath) ? resolveASTPath(includePath) : null;
    }

    @Override
    public boolean doesIncludeFileExist(String includePath) {
        return new File(includePath).exists();
    }

    @Override
    public String getASTPath(IIndexFileLocation ifl) {
        return ifl.getFullPath();
    }

    @Override
    public boolean isSource(String astFilePath) {
        return false;
    }

    @Override
    public long getFileSize(String astFilePath) {
        return 0;
    }

    @Override
    public boolean isCaseInsensitiveFileSystem() {
        return new File("a").equals(new File("A"));
    }

    @Override
    public Object getInputFile(IIndexFileLocation location) {
        return null;
    }

    @Override
    public long getLastModified(IIndexFileLocation location) {
        return 0;
    }

    @Override
    public long getFileSize(IIndexFileLocation location) {
        return 0;
    }

    @Override
    public String getEncoding(IIndexFileLocation location) {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public IIndexFileLocation resolveFile(Object tu) {
        return null;
    }

    /**
     * just consider .c/.cpp
     * @param tu
     * @return
     */
    @Override
    public boolean isSourceUnit(Object tu) {
        if (tu instanceof String string) {
            if (string.endsWith(".c") || string.endsWith(".cpp")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isFileBuildConfigured(Object tu) {
        return false;
    }

    @Override
    public boolean isIndexedOnlyIfIncluded(Object tu) {
        return false;
    }

    @Override
    public boolean isIndexedUnconditionally(IIndexFileLocation location) {
        return false;
    }

    @Override
    public int getIndexingPriority(IIndexFileLocation location) {
        return 0;
    }

    @Override
    public boolean canBePartOfSDK(IIndexFileLocation ifl) {
        return false;
    }

    /**
     * I don't know what's the difference between GCCLanguage/GPPLanguage
     * @param tu
     * @param strat
     * @return
     */
    @Override
    public AbstractLanguage[] getLanguages(Object tu, AbstractIndexerTask.UnusedHeaderStrategy strat) {
        return new AbstractLanguage[]{GPPLanguage.getDefault()};
    }

    @Override
    public IScannerInfo getBuildConfiguration(int linkageID, Object tu) {
        return null;
    }

    @Override
    public FileContent getCodeReader(Object tu) {
        if (tu instanceof String path) {
            return FileContent.createForExternalFileLocation(path,true, StandardCharsets.UTF_8.name());
        }
        return null;
    }
}
