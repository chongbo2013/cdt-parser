package change;

import org.eclipse.cdt.internal.core.dom.IIncludeFileResolutionHeuristics;

public class CustomIndexerIncludeResolutionHeuristics implements IIncludeFileResolutionHeuristics {
    @Override
    public String findInclusion(String include, String currentFile) {
        return null;
        // 参见ProjectIndexerIncludeResolutionHeuristics
    }
}
