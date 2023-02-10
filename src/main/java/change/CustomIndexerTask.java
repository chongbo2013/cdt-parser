package change;

import org.eclipse.cdt.core.dom.ILinkage;
import org.eclipse.cdt.core.dom.ast.IASTComment;
import org.eclipse.cdt.core.dom.ast.IASTFileLocation;
import org.eclipse.cdt.core.dom.ast.IASTPreprocessorIncludeStatement;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPUsingDirective;
import org.eclipse.cdt.core.index.*;
import org.eclipse.cdt.core.model.AbstractLanguage;
import org.eclipse.cdt.core.model.ILanguage;
import org.eclipse.cdt.core.parser.*;
import org.eclipse.cdt.internal.core.dom.IIncludeFileResolutionHeuristics;
import org.eclipse.cdt.internal.core.index.*;
import org.eclipse.cdt.internal.core.model.DebugLogConstants;
import org.eclipse.cdt.internal.core.parser.IMacroDictionary;
import org.eclipse.cdt.internal.core.parser.ParserLogService;
import org.eclipse.cdt.internal.core.parser.ParserSettings2;
import org.eclipse.cdt.internal.core.parser.scanner.InternalFileContentProvider;
import org.eclipse.cdt.internal.core.parser.util.LRUCache;
import org.eclipse.cdt.internal.core.pdom.*;
import org.eclipse.cdt.internal.core.pdom.dom.IPDOMLinkageFactory;
import org.eclipse.cdt.internal.core.pdom.dom.c.PDOMCLinkageFactory;
import org.eclipse.cdt.internal.core.pdom.dom.cpp.PDOMCPPLinkageFactory;
import org.eclipse.cdt.internal.core.util.Canceler;
import org.eclipse.cdt.internal.core.util.ICanceler;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;

import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomIndexerTask extends PDOMWriter {
    private Map<String, IASTTranslationUnit> translationUnitMap = new HashMap<>();
    public IWritableIndex fIndex;
    private int fASTOptions;
    private String rootPath;
    private String[] fFilesToUpdate;
    private int fForceNumberFiles;
    private String[] includes;
    private final List<LinkageTask> fRequestsPerLinkage = new ArrayList<>();
    private final Map<IIndexFile, IndexFileContent> fIndexContentCache = new LRUCache<>(500);
    private final Map<IIndexFileLocation, IIndexFragmentFile[]> fIndexFilesCache = new LRUCache<>(5000);
    private final Map<IIndexFileLocation, LocationTask> fOneLinkageTasks = new HashMap<>();
    private long fTranslationUnitSizeLimit = 8388608;
    private long fIncludedFileSizeLimit = 16777216;
    private InternalFileContentProvider fCodeReaderFactory;
    private boolean fIndexAllHeaderVersions = false;
    private Set<String> fHeadersToIndexAllVersions = Collections.emptySet();
    private int fSwallowOutOfMemoryError = 5;
    protected final ICanceler fCancelState = new Canceler();
    private static final Pattern HEADERNAME_PATTERN = Pattern.compile("@headername\\{(?<header>[^\\}]+)\\}"); //$NON-NLS-1$
    private static final Pattern fPragmaPrivatePattern = Pattern.compile("IWYU\\s+(pragma:?\\s+)?private(,\\s+include\\s+(?<header>\\S+))?");
    public CustomIndexerTask(IndexerInputAdapter resolver,String rootPath,String[] includes) {
        this(resolver);
        this.rootPath = rootPath;
        this.fFilesToUpdate = Files.collectSrcFiles(rootPath);
        this.fForceNumberFiles = this.fFilesToUpdate.length;
        this.includes = includes;
    }

    public CustomIndexerTask(IndexerInputAdapter resolver) {
        super(resolver);
    }

    /**
     * copy from AbstractIndexerTask
     * @param monitor
     * @throws InterruptedException
     * @throws CoreException
     */
    public final void runTask(IProgressMonitor monitor) throws InterruptedException, CoreException {
        fIndex = createIndex(monitor);
        if (fIndex == null) {
            return;
        }
        fASTOptions = ILanguage.OPTION_NO_IMAGE_LOCATIONS
                | ILanguage.OPTION_SKIP_TRIVIAL_EXPRESSIONS_IN_AGGREGATE_INITIALIZERS;

        if (getSkipReferences() == SKIP_ALL_REFERENCES) {
            fASTOptions |= ILanguage.OPTION_SKIP_FUNCTION_BODIES;
        }
        fIndex.resetCacheCounters();
        fIndex.acquireReadLock();
        // Split into sources and headers, remove excluded sources.
        HashMap<Integer, List<IIndexFileLocation>> files = new HashMap<>();
        final ArrayList<IIndexFragmentFile> indexFilesToRemove = new ArrayList<>();
        extractFiles(files, indexFilesToRemove, monitor);
        int[] linkageIDs = getLinkagesToParse();
        for (int linkageID : linkageIDs) {
            final List<IIndexFileLocation> filesForLinkage = files.get(linkageID);
            if (filesForLinkage != null) {
                parseLinkage(linkageID, filesForLinkage, monitor);
            }
        }
    }
    private void parseLinkage(int linkageID, List<IIndexFileLocation> files, IProgressMonitor monitor) throws CoreException, InterruptedException {
        LinkageTask map = findRequestMap(linkageID);
        if (map == null || files == null || files.isEmpty())
            return;
        int maxPriority = Integer.MIN_VALUE;
        int minPriority = Integer.MAX_VALUE;
        Map<Integer, List<IIndexFileLocation>> filesByPriority = new HashMap<>();
        for (IIndexFileLocation file : files) {
            int priority = fResolver.getIndexingPriority(file);
            List<IIndexFileLocation> list = filesByPriority.get(priority);
            if (list == null) {
                list = new LinkedList<>();
                filesByPriority.put(priority, list);
            }
            list.add(file);

            if (maxPriority < priority)
                maxPriority = priority;
            if (minPriority > priority)
                minPriority = priority;
        }
        for (int priority = maxPriority; priority >= minPriority; priority--) {
            List<IIndexFileLocation> filesAtPriority = filesByPriority.get(priority);
            if (filesAtPriority == null)
                continue;

            // First parse the required sources.
            for (Iterator<IIndexFileLocation> it = filesAtPriority.iterator(); it.hasNext(); ) {
                IIndexFileLocation ifl = it.next();
                LocationTask locTask = map.find(ifl);
                if (locTask == null || locTask.isCompleted()) {
                    it.remove();
                } else if (locTask.fKind == UpdateKind.REQUIRED_SOURCE || locTask.fKind == UpdateKind.ONE_LINKAGE_HEADER) {
//                    if (hasUrgentTasks())
//                        return;
                    final Object tu = locTask.fTu;
                    final IScannerInfo scannerInfo = getScannerInfo(linkageID, tu);

                    parseFile(tu, getLanguage(tu, linkageID), ifl, scannerInfo, null, monitor);
                } else {
                    System.out.println();
                }
            }

            // Files with context.
            for (Iterator<IIndexFileLocation> it = filesAtPriority.iterator(); it.hasNext(); ) {
                IIndexFileLocation ifl = it.next();
                LocationTask locTask = map.find(ifl);
                if (locTask == null || locTask.isCompleted()) {
                    it.remove();
                } else {
                    for (FileVersionTask versionTask : locTask.fVersionTasks) {
                        if (versionTask.fOutdated) {
//                            if (hasUrgentTasks())
//                                return;
//                            parseVersionInContext(linkageID, map, ifl, versionTask, locTask.fTu,
//                                    new LinkedHashSet<IIndexFile>(), progress.split(1));
                        }
                    }
                }
            }

            // Files without context.
            for (Iterator<IIndexFileLocation> it = filesAtPriority.iterator(); it.hasNext(); ) {
                IIndexFileLocation ifl = it.next();
                LocationTask locTask = map.find(ifl);
                if (locTask == null || locTask.isCompleted()) {
                    it.remove();
                } else {
                    if (locTask.needsVersion()) {
//                        if (hasUrgentTasks())
//                            return;
//                        final Object tu = locTask.fTu;
//                        final IScannerInfo scannerInfo = getScannerInfo(linkageID, tu);
//                        parseFile(tu, getLanguage(tu, linkageID), ifl, scannerInfo, null, progress.split(1));
                        if (locTask.isCompleted())
                            it.remove();

                    }
                }
            }

            // Delete remaining files.
//            fIndex.acquireWriteLock(progress.split(1));
//            try {
//                for (IIndexFileLocation ifl : filesAtPriority) {
//                    LocationTask locTask = map.find(ifl);
//                    if (locTask != null && !locTask.isCompleted()) {
//                        if (!locTask.needsVersion()) {
//                            progress.split(1);
//                            if (hasUrgentTasks())
//                                return;
//                            Iterator<FileVersionTask> it = locTask.fVersionTasks.iterator();
//                            while (it.hasNext()) {
//                                FileVersionTask v = it.next();
//                                if (v.fOutdated) {
//                                    fIndex.clearFile(v.fIndexFile);
//                                    reportFile(true, locTask.fKind);
//                                    locTask.removeVersionTask(it);
//                                    fIndexContentCache.remove(v.fIndexFile);
//                                    fIndexFilesCache.remove(ifl);
//                                }
//                            }
//                        }
//                    }
//                }
//            } finally {
//                fIndex.releaseWriteLock();
//            }
        }
    }

    private InternalFileContentProvider.DependsOnOutdatedFileException parseFile(Object tu, AbstractLanguage lang, IIndexFileLocation ifl,
                                                                                 IScannerInfo scanInfo, FileContext ctx, IProgressMonitor monitor)
            throws CoreException, InterruptedException {
        SubMonitor progress = SubMonitor.convert(monitor, 21);
        boolean resultCacheCleared = false;
        Throwable th = null;
        try {
            if (fShowActivity) {
//                trace("Indexer: parsing " + path.toOSString()); //$NON-NLS-1$
            }
            FileContent codeReader = fResolver.getCodeReader(tu);

            long start = System.currentTimeMillis();
            IASTTranslationUnit ast = createAST(lang, codeReader, scanInfo, fASTOptions, ctx, monitor);
            fStatistics.fParsingTime += System.currentTimeMillis() - start;
            if (ast == null) {
                ++fStatistics.fTooManyTokensCount;
            } else {
                writeToIndex(lang.getLinkageID(), ast, codeReader, ctx, progress.split(10));
                resultCacheCleared = true; // The cache was cleared while writing to the index.
            }
            if (fShowActivity) {
                long time = System.currentTimeMillis() - start;
//                trace("Indexer: processed " + path.toOSString() + " [" + time + " ms]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            translationUnitMap.put(ifl.getFullPath(),ast);
        } catch (OperationCanceledException e) {
        } catch (RuntimeException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof InternalFileContentProvider.DependsOnOutdatedFileException)
                return (InternalFileContentProvider.DependsOnOutdatedFileException) cause;
            th = e;
        } catch (StackOverflowError | CoreException | AssertionError e) {
            th = e;
        } catch (OutOfMemoryError e) {
            if (--fSwallowOutOfMemoryError < 0)
                throw e;
            th = e;
        }
        if (th != null) {
//            swallowError(path, th);
        }

        if (!resultCacheCleared) {
            // If the result cache has not been cleared, clear it under a write lock to reduce
            // interference with index readers.
            fIndex.acquireWriteLock(progress.split(1));
            try {
                fIndex.clearResultCache();
            } finally {
                fIndex.releaseWriteLock();
            }
        }
        return null;
    }

    private final IASTTranslationUnit createAST(AbstractLanguage language, FileContent codeReader,
                                                IScannerInfo scanInfo, int options, FileContext ctx, IProgressMonitor monitor) throws CoreException {
        if (codeReader == null) {
            return null;
        }
        if (fTranslationUnitSizeLimit > 0
                && fResolver.getFileSize(codeReader.getFileLocation()) > fTranslationUnitSizeLimit) {
            if (fShowActivity) {
                trace("Indexer: Skipping large file " + codeReader.getFileLocation()); //$NON-NLS-1$
            }
            return null;
        }
        final IIndexFile[] ctx2header = ctx == null ? null : new IIndexFile[] { ctx.fContext, ctx.fOldFile };
        boolean fIsFastIndexer = true;
        if (fCodeReaderFactory == null) {
            InternalFileContentProvider fileContentProvider = createInternalFileContentProvider();

            if (fIsFastIndexer) {
                CustomIndexBasedFileContentProvider ibfcp = new CustomIndexBasedFileContentProvider(fIndex, fResolver,
                        language.getLinkageID(), fileContentProvider, this);
                ibfcp.setContextToHeaderGap(ctx2header);
                ibfcp.setFileSizeLimit(fIncludedFileSizeLimit);
                ibfcp.setHeadersToIndexAllVersions(fHeadersToIndexAllVersions);
                ibfcp.setIndexAllHeaderVersions(fIndexAllHeaderVersions);
                fCodeReaderFactory = ibfcp;
            }
//            else {
//                fCodeReaderFactory = fileContentProvider;
//            }
            fCodeReaderFactory.setIncludeResolutionHeuristics(createIncludeHeuristics());
        } else if (fIsFastIndexer) {
            final CustomIndexBasedFileContentProvider ibfcp = (CustomIndexBasedFileContentProvider) fCodeReaderFactory;
            ibfcp.setContextToHeaderGap(ctx2header);
            ibfcp.setLinkage(language.getLinkageID());
        }

        IASTTranslationUnit ast = language.getASTTranslationUnit(codeReader, scanInfo, fCodeReaderFactory, fIndex,
                options, getLogService());
        if (monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
        return ast;
    }
    protected IIncludeFileResolutionHeuristics createIncludeHeuristics() {
//        return new ProjectIndexerIncludeResolutionHeuristics(getCProject().getProject(), getInputAdapter());
        return new CustomIndexerIncludeResolutionHeuristics();
    }

    protected IParserLogService getLogService() {
        return new ParserLogService(DebugLogConstants.PARSER, fCancelState);
    }
    private InternalFileContentProvider createInternalFileContentProvider() {
        final IncludeFileContentProvider fileContentProvider = createReaderFactory();
        if (fileContentProvider instanceof InternalFileContentProvider)
            return (InternalFileContentProvider) fileContentProvider;

        throw new IllegalArgumentException("Invalid file content provider"); //$NON-NLS-1$
    }

    protected IncludeFileContentProvider createReaderFactory() {
        return new CustomIncludeFileContentProvider();
    }


    private AbstractLanguage getLanguage(Object tu, int linkageID) {
        for (AbstractLanguage language : fResolver.getLanguages(tu, AbstractIndexerTask.UnusedHeaderStrategy.useBoth)) {
            if (language.getLinkageID() == linkageID) {
                return language;
            }
        }
        return null;
    }
    private void writeToIndex(final int linkageID, IASTTranslationUnit ast, FileContent codeReader, FileContext ctx,
                              IProgressMonitor monitor) throws CoreException, InterruptedException {
        SubMonitor progress = SubMonitor.convert(monitor, 3);
        HashSet<FileContentKey> enteredFiles = new HashSet<>();
        ArrayList<FileInAST> orderedFileKeys = new ArrayList<>();

        final IIndexFileLocation topIfl = fResolver.resolveASTPath(ast.getFilePath());
        ISignificantMacros significantMacros = ast.isHeaderUnit() ? ast.getSignificantMacros()
                : ISignificantMacros.NONE;
        FileContentKey topKey = new FileContentKey(linkageID, topIfl, significantMacros);
        enteredFiles.add(topKey);
        IASTTranslationUnit.IDependencyTree tree = ast.getDependencyTree();
        IASTTranslationUnit.IDependencyTree.IASTInclusionNode[] inclusions = tree.getInclusions();
        for (IASTTranslationUnit.IDependencyTree.IASTInclusionNode inclusion : inclusions) {
            collectOrderedFileKeys(linkageID, inclusion, enteredFiles, orderedFileKeys);
        }

        IIndexFragmentFile newFile = selectIndexFile(linkageID, topIfl, significantMacros);
        if (ctx != null) {
            orderedFileKeys.add(new FileInAST(topKey, codeReader));
            // File can be reused
            ctx.fNewFile = newFile;
        } else if (newFile == null) {
            orderedFileKeys.add(new FileInAST(topKey, codeReader));
        }

        FileInAST[] fileKeys = orderedFileKeys.toArray(new FileInAST[orderedFileKeys.size()]);
        try {
            // The default processing is handled by the indexer task.
            PDOMWriter.Data data = new PDOMWriter.Data(ast, fileKeys, fIndex);
            int storageLinkageID = process(ast, data);
            if (storageLinkageID != ILinkage.NO_LINKAGE_ID) {
                IASTComment[] comments = ast.getComments();
                data.fReplacementHeaders = extractReplacementHeaders(comments, progress.split(1));

                addSymbols(data, storageLinkageID, ctx, progress.split(1));

                // Update task markers.
//                if (fTodoTaskUpdater != null) {
//                    Set<IIndexFileLocation> locations = new HashSet<>();
//                    for (FileInAST file : data.fSelectedFiles) {
//                        locations.add(file.fileContentKey.getLocation());
//                    }
//                    fTodoTaskUpdater.updateTasks(comments, locations.toArray(new IIndexFileLocation[locations.size()]));
//                }
            }

            // Contributed processors now have an opportunity to examine the AST.
            List<IPDOMASTProcessor> processors = PDOMASTProcessorManager.getProcessors(ast);
            progress.setWorkRemaining(processors.size());
            for (IPDOMASTProcessor processor : processors) {
                data = new PDOMWriter.Data(ast, fileKeys, fIndex);
                storageLinkageID = processor.process(ast, data);
                if (storageLinkageID != ILinkage.NO_LINKAGE_ID)
                    addSymbols(data, storageLinkageID, ctx, progress.split(1));
            }
        } catch (CoreException | RuntimeException | Error e) {
            // Avoid parsing files again, that caused an exception to be thrown.
//            withdrawRequests(linkageID, fileKeys);
            throw e;
        }
    }
    /**
     * Parses comments to extract replacement headers from <code>@headername{header}</code> and
     * {@code IWYU pragma: private}.
     *
     * @return replacement headers keyed by file paths
     */
    private Map<String, String> extractReplacementHeaders(IASTComment[] comments, IProgressMonitor monitor) {
        Map<String, String> replacementHeaders = new HashMap<>();
        StringBuilder text = new StringBuilder();
        IASTFileLocation carryoverLocation = null;
        for (int i = 0; i < comments.length; i++) {
            IASTComment comment = comments[i];
            IASTFileLocation location = comment.getFileLocation();
            if (location == null)
                continue;
            String fileName = location.getFileName();
            if (replacementHeaders.containsKey(fileName))
                continue;
            char[] commentChars = comment.getComment();
            if (commentChars.length <= 2)
                continue;
            if (carryoverLocation == null || !location.getFileName().equals(carryoverLocation.getFileName())
                    || location.getStartingLineNumber() != carryoverLocation.getEndingLineNumber() + 1) {
                text.delete(0, text.length());
            }
            carryoverLocation = null;
            text.append(commentChars, 2, commentChars.length - 2);
            // Look for @headername{header}.
            Matcher matcher = HEADERNAME_PATTERN.matcher(text);
            if (matcher.find()) {
                String header = matcher.group("header"); //$NON-NLS-1$
                if (header == null) {
                    header = ""; //$NON-NLS-1$
                } else {
                    // Normalize the header list.
                    header = header.replace(" or ", ",").replace(" ", ""); //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$//$NON-NLS-4$
                }
                replacementHeaders.put(fileName, header);
                continue;
            }
            if (fPragmaPrivatePattern != null) {
                // Look for IWYU pragma: private
                matcher = fPragmaPrivatePattern.matcher(text);
                if (matcher.find()) {
                    if (!isWhitespace(text, 0, matcher.start()))
                        continue; // Extraneous text before the pragma.
                    if (isWhitespace(text, matcher.end(), text.length())) {
                        String header = matcher.group("header"); //$NON-NLS-1$
                        if (header == null)
                            header = ""; //$NON-NLS-1$
                        replacementHeaders.put(fileName, header);
                        continue;
                    }
                    // Handle the case when a IWYU pragma is split between two comment lines as:
                    //   IWYU pragma: private,
                    //   include "header"
                    if (text.charAt(matcher.end()) == ',' && isWhitespace(text, matcher.end() + 1, text.length())) {
                        // Defer processing until the next comment, which will be appended to this one.
                        carryoverLocation = location;
                    }
                }
            }
        }
        return replacementHeaders;
    }
    private boolean isWhitespace(CharSequence text, int start, int end) {
        while (start < end) {
            if (text.charAt(start++) > ' ')
                return false;
        }
        return true;
    }
    private void collectOrderedFileKeys(final int linkageID, IASTTranslationUnit.IDependencyTree.IASTInclusionNode inclusion,
                                        Set<FileContentKey> enteredFiles, List<FileInAST> orderedFileKeys) throws CoreException {
        final IASTPreprocessorIncludeStatement include = inclusion.getIncludeDirective();
        if (include.createsAST()) {
            final IIndexFileLocation ifl = fResolver.resolveASTPath(include.getPath());
            FileContentKey fileKey = new FileContentKey(linkageID, ifl, include.getSignificantMacros());
            final boolean isFirstEntry = enteredFiles.add(fileKey);
            IASTTranslationUnit.IDependencyTree.IASTInclusionNode[] nested = inclusion.getNestedInclusions();
            for (IASTTranslationUnit.IDependencyTree.IASTInclusionNode element : nested) {
                collectOrderedFileKeys(linkageID, element, enteredFiles, orderedFileKeys);
            }
            if (isFirstEntry && selectIndexFile(linkageID, ifl, include.getSignificantMacros()) == null) {
                orderedFileKeys.add(new FileInAST(include, fileKey));
            }
        }
    }
    public IIndexFragmentFile selectIndexFile(int linkageID, IIndexFileLocation ifl, ISignificantMacros sigMacros)
            throws CoreException {
        LinkageTask map = findRequestMap(linkageID);
        if (map != null) {
            LocationTask locTask = map.find(ifl);
            if (locTask != null) {
                FileVersionTask task = locTask.findVersion(sigMacros);
                if (task != null) {
                    return task.fOutdated ? null : task.fIndexFile;
                }
            }
        }

        IIndexFragmentFile[] files = getAvailableIndexFiles(linkageID, ifl);
        for (IIndexFragmentFile file : files) {
            if (sigMacros.equals(file.getSignificantMacros()))
                return file;
        }
        return null;
    }

    public IIndexFile selectIndexFile(int linkageID, IIndexFileLocation ifl, IMacroDictionary md) throws CoreException {
        LinkageTask map = findRequestMap(linkageID);
        if (map != null) {
            LocationTask request = map.find(ifl);
            if (request != null) {
                for (FileVersionTask fileVersion : request.fVersionTasks) {
                    final IIndexFile indexFile = fileVersion.fIndexFile;
                    if (md.satisfies(indexFile.getSignificantMacros())) {
                        if (fileVersion.fOutdated)
                            return null;
                        return indexFile;
                    }
                }
            }
        }

        IIndexFile[] files = getAvailableIndexFiles(linkageID, ifl);
        for (IIndexFile indexFile : files) {
            if (md.satisfies(indexFile.getSignificantMacros())) {
                return indexFile;
            }
        }
        return null;
    }

    public IIndexFragmentFile[] getAvailableIndexFiles(int linkageID, IIndexFileLocation ifl) throws CoreException {
        IIndexFragmentFile[] files = fIndexFilesCache.get(ifl);
        if (files == null) {
            IIndexFragmentFile[] fragFiles = fIndex.getWritableFiles(linkageID, ifl);
            int j = 0;
            for (int i = 0; i < fragFiles.length; i++) {
                if (fragFiles[i].hasContent()) {
                    if (j != i)
                        fragFiles[j] = fragFiles[i];
                    j++;
                }
            }
            if (j == fragFiles.length) {
                files = fragFiles;
            } else {
                files = new IIndexFragmentFile[j];
                System.arraycopy(fragFiles, 0, files, 0, j);
            }
            fIndexFilesCache.put(ifl, files);
        }
        return files;
    }

    public final IndexFileContent getFileContent(int linkageID, IIndexFileLocation ifl, IIndexFile file)
            throws CoreException, InternalFileContentProvider.DependsOnOutdatedFileException {
        LinkageTask map = findRequestMap(linkageID);
        if (map != null) {
            LocationTask request = map.find(ifl);
            if (request != null) {
                FileVersionTask task = request.findVersion(file);
                if (task != null && task.fOutdated)
                    throw new InternalFileContentProvider.DependsOnOutdatedFileException(request.fTu, task.fIndexFile);
            }
        }
        IndexFileContent fc = fIndexContentCache.get(file);
        if (fc == null) {
            fc = new IndexFileContent(file);
            fIndexContentCache.put(file, fc);
        }
        return fc;
    }
    private IScannerInfo getScannerInfo(int linkageID, Object contextTu) {
        final IScannerInfo scannerInfo = new ExtendedScannerInfo(Collections.emptyMap(),includes);
        if (scannerInfo instanceof ExtendedScannerInfo) {
            ExtendedScannerInfo extendedScannerInfo = (ExtendedScannerInfo) scannerInfo;
            extendedScannerInfo.setIncludeExportPatterns(getIncludeExportPatterns());
            extendedScannerInfo.setParserSettings(createParserSettings());
        }
        return scannerInfo;
    }
    protected IParserSettings createParserSettings() {
        return new ParserSettings2();
    }
    private IncludeExportPatterns getIncludeExportPatterns() {
        String exportPattern = "IWYU\\s+(pragma:?\\s+)?export";
        String beginExportsPattern = "IWYU\\s+(pragma:?\\s+)?begin_exports?";
        String endExportsPattern = "IWYU\\s+(pragma:?\\s+)?end_exports?";
        return new IncludeExportPatterns(exportPattern, beginExportsPattern, endExportsPattern);
    }
    private IWritableIndex createIndex(IProgressMonitor monitor) throws CoreException {
        File root = new File(rootPath);
        URI uri = root.toURI();
        File dbFile = new File(rootPath, "niubi.pdom");
        dbFile.deleteOnExit();
        Map<String, IPDOMLinkageFactory> linkageFactoryMappings = new HashMap<>();
        linkageFactoryMappings.put(ILinkage.C_LINKAGE_NAME, new PDOMCLinkageFactory());
        linkageFactoryMappings.put(ILinkage.CPP_LINKAGE_NAME, new PDOMCPPLinkageFactory());
        URIRelativeLocationConverter locationConverter = new URIRelativeLocationConverter(uri);
        WritablePDOM pdom;
        try {
            pdom = new WritablePDOM(dbFile, locationConverter, linkageFactoryMappings);
        } catch (CoreException e) {
            System.err.println("Failed to open C/C++ index file " + dbFile.getAbsolutePath() //$NON-NLS-1$
                    + " - rebuilding the index");
            e.printStackTrace();
            dbFile.delete();
            pdom = new WritablePDOM(dbFile, locationConverter, linkageFactoryMappings);
        }
        try {
            pdom.acquireWriteLock(monitor);
            writeProjectPDOMProperties(pdom, null);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            pdom.releaseWriteLock();
        }
        pdom.setASTFilePathResolver(fResolver);
        return new WritableCIndex(pdom);
    }

    public static void writeProjectPDOMProperties(WritablePDOM pdom, IProject project) throws CoreException {
        String DELIM = "\0"; //$NON-NLS-1$
        String id = "niubi";
        pdom.setProperty(IIndexFragment.PROPERTY_FRAGMENT_ID, id);
    }


    private void extractFiles(HashMap<Integer, List<IIndexFileLocation>> files, List<IIndexFragmentFile> filesToRemove,
                              IProgressMonitor monitor) throws CoreException {
        final boolean forceAll = true;
        final boolean checkTimestamps = false;
        final boolean checkFileContentsHash = false;
        final boolean forceUnresolvedIncludes = false;
        final boolean both = false;

        int count = 0;
        int forceFirst = fForceNumberFiles;
        BitSet linkages = new BitSet();
        for (String tu : fFilesToUpdate) {
            final boolean force = forceAll || --forceFirst >= 0;
//            final IIndexFileLocation ifl = fResolver.resolveFile(tu);
            final IIndexFileLocation ifl = fResolver.resolveASTPath(tu);
            if (ifl == null) {
                continue;
            }
            final IIndexFragmentFile[] indexFiles = fIndex.getWritableFiles(ifl);
            final boolean isSourceUnit = fResolver.isSourceUnit(tu);
            linkages.clear();
            final boolean regularContent = isRequiredInIndex(tu, ifl, isSourceUnit);
            final boolean indexedUnconditionally = fResolver.isIndexedUnconditionally(ifl);
            if (regularContent || indexedUnconditionally) {
                // Headers or sources required with a specific linkage.
                final UpdateKind updateKind = isSourceUnit ? UpdateKind.REQUIRED_SOURCE
                        : regularContent && both ? UpdateKind.REQUIRED_HEADER : UpdateKind.ONE_LINKAGE_HEADER;
                if (regularContent || indexFiles.length == 0) {
                    AbstractLanguage[] langs = fResolver.getLanguages(tu, null);
                    for (AbstractLanguage lang : langs) {
                        int linkageID = lang.getLinkageID();
                        boolean foundInLinkage = false;
                        //忽略一个循环判断
                        for (int i = 0; i < indexFiles.length; i++) {
                            IIndexFragmentFile ifile = indexFiles[i];
                            if (ifile != null && ifile.getLinkageID() == linkageID && ifile.hasContent()) {
                                foundInLinkage = true;
                                indexFiles[i] = null; // Take the file.
                                boolean update = force || (forceUnresolvedIncludes && ifile.hasUnresolvedInclude())
                                        || isModified(checkTimestamps, checkFileContentsHash, ifl, tu, ifile);
                                if (update && requestUpdate(linkageID, ifl, ifile, tu, updateKind)) {
                                    count++;
                                    linkages.set(linkageID);
                                }
                            }
                        }
                        if (!foundInLinkage && requestUpdate(linkageID, ifl, null, tu, updateKind)) {
                            linkages.set(linkageID);
                            count++;
                        }
                    }


                }
            }
            for (int lid = linkages.nextSetBit(0); lid >= 0; lid = linkages.nextSetBit(lid + 1)) {
                addPerLinkage(lid, ifl, files);
            }
        }

    }

    private boolean isRequiredInIndex(Object tu, IIndexFileLocation ifl, boolean isSourceUnit) {
        // External files are never required
        return !fResolver.isIndexedOnlyIfIncluded(tu);
        // 强行改为true
// User preference to require all
//        if (fIndexHeadersWithoutContext != UnusedHeaderStrategy.skip)
//            return true;
//
//        // Source file
//        if (isSourceUnit) {
//            if (fIndexFilesWithoutConfiguration || fResolver.isFileBuildConfigured(tu))
//                return true;
//        }
//        return false;
    }


    private enum UpdateKind {
        REQUIRED_SOURCE, REQUIRED_HEADER, ONE_LINKAGE_HEADER, OTHER_HEADER
    }
    private boolean isModified(boolean checkTimestamps, boolean checkFileContentsHash, IIndexFileLocation ifl, String tu, IIndexFragmentFile ifile) {
        return false;
    }

    private boolean requestUpdate(int linkageID, IIndexFileLocation ifl, IIndexFragmentFile ifile, Object tu,
                                  UpdateKind kind) {
        LinkageTask fileMap = createRequestMap(linkageID);
        return fileMap.requestUpdate(ifl, ifile, tu, kind, fOneLinkageTasks);
    }
    private LinkageTask createRequestMap(int linkageID) {
        LinkageTask map = findRequestMap(linkageID);
        if (map == null) {
            map = new LinkageTask(linkageID);
            fRequestsPerLinkage.add(map);
        }
        return map;
    }
    private LinkageTask findRequestMap(int linkageID) {
        for (LinkageTask map : fRequestsPerLinkage) {
            if (map.fLinkageID == linkageID)
                return map;
        }
        return null;
    }
    private void addPerLinkage(int linkageID, IIndexFileLocation ifl,
                               HashMap<Integer, List<IIndexFileLocation>> files) {
        List<IIndexFileLocation> list = files.get(linkageID);
        if (list == null) {
            list = new ArrayList<>();
            files.put(linkageID, list);
        }
        list.add(ifl);
    }
    private static class LinkageTask {
        final int fLinkageID;
        private final Map<IIndexFileLocation, LocationTask> fLocationTasks;

        LinkageTask(int linkageID) {
            fLinkageID = linkageID;
            fLocationTasks = new HashMap<>();
        }

        boolean requestUpdate(IIndexFileLocation ifl, IIndexFragmentFile ifile, Object tu, UpdateKind kind,
                              Map<IIndexFileLocation, LocationTask> oneLinkageTasks) {
            LocationTask locTask = fLocationTasks.get(ifl);
            if (locTask == null) {
                locTask = new LocationTask();
                fLocationTasks.put(ifl, locTask);
            }
            boolean result = locTask.requestUpdate(ifile, tu, kind);

            // Store one-linkage tasks.
            if (kind == UpdateKind.ONE_LINKAGE_HEADER && locTask.fVersionTasks.isEmpty())
                oneLinkageTasks.put(ifl, locTask);

            return result;
        }

        LocationTask find(IIndexFileLocation ifl) {
            return fLocationTasks.get(ifl);
        }
    }

    private static class LocationTask {
        Object fTu;
        UpdateKind fKind = UpdateKind.OTHER_HEADER;
        private boolean fCountedUnknownVersion;
        private boolean fStoredAVersion;
        private List<FileVersionTask> fVersionTasks = Collections.emptyList();

        /**
         * Requests the update of a file, returns whether the total count needs to be updated.
         */
        boolean requestUpdate(IIndexFragmentFile ifile, Object tu, UpdateKind kind) {
            if (tu != null)
                fTu = tu;
            // Change fKind only if it becomes stronger as a result.
            if (fKind == null || (kind != null && kind.compareTo(fKind) < 0))
                fKind = kind;

            if (ifile == null) {
                assert fVersionTasks.isEmpty();
                final boolean countRequest = !fCountedUnknownVersion;
                fCountedUnknownVersion = true;
                return countRequest;
            }

            return addVersionTask(ifile);
        }

        /**
         * Return whether the task needs to be counted.
         */
        private boolean addVersionTask(IIndexFragmentFile ifile) {
            FileVersionTask fc = findVersion(ifile);
            if (fc != null)
                return false;

            fc = new FileVersionTask(ifile);
            boolean countRequest = true;
            if (fCountedUnknownVersion) {
                fCountedUnknownVersion = false;
                countRequest = false;
            }

            switch (fVersionTasks.size()) {
                case 0:
                    fVersionTasks = Collections.singletonList(fc);
                    break;
                case 1:
                    List<FileVersionTask> newList = new ArrayList<>(2);
                    newList.add(fVersionTasks.get(0));
                    newList.add(fc);
                    fVersionTasks = newList;
                    break;
                default:
                    fVersionTasks.add(fc);
                    break;
            }
            return countRequest;
        }

        void removeVersionTask(Iterator<FileVersionTask> it) {
            if (fVersionTasks.size() == 1) {
                fVersionTasks = Collections.emptyList();
            } else {
                it.remove();
            }
        }

        private FileVersionTask findVersion(IIndexFile ifile) {
            for (FileVersionTask fc : fVersionTasks) {
                if (fc.fIndexFile.equals(ifile))
                    return fc;
            }
            return null;
        }

        FileVersionTask findVersion(ISignificantMacros sigMacros) throws CoreException {
            for (FileVersionTask fc : fVersionTasks) {
                if (sigMacros.equals(fc.fIndexFile.getSignificantMacros()))
                    return fc;
            }
            return null;
        }

        boolean isCompleted() {
            for (FileVersionTask fc : fVersionTasks) {
                if (fc.fOutdated)
                    return false;
            }
            if (fKind == UpdateKind.OTHER_HEADER)
                return true;

            return fStoredAVersion;
        }

        public boolean needsVersion() {
            if (fKind == UpdateKind.OTHER_HEADER)
                return false;

            return !fStoredAVersion;
        }
    }

    public static class FileVersionTask {
        private final IIndexFragmentFile fIndexFile;
        private boolean fOutdated;

        FileVersionTask(IIndexFragmentFile file) {
            fIndexFile = file;
            fOutdated = true;
        }

        void setUpdated() {
            fOutdated = false;
        }
    }

    public static class IndexFileContent {
        private Object[] fPreprocessingDirectives;
        private ICPPUsingDirective[] fDirectives;

        public IndexFileContent(IIndexFile ifile) throws CoreException {
            setPreprocessorDirectives(ifile.getIncludes(), ifile.getMacros());
            setUsingDirectives(ifile.getUsingDirectives());
        }

        public static Object[] merge(IIndexInclude[] includes, IIndexMacro[] macros) throws CoreException {
            Object[] merged = new Object[includes.length + macros.length];
            int i = 0;
            int m = 0;
            int ioffset = getOffset(includes, i);
            int moffset = getOffset(macros, m);
            for (int k = 0; k < merged.length; k++) {
                if (ioffset <= moffset && i < includes.length) {
                    merged[k] = includes[i];
                    ioffset = getOffset(includes, ++i);
                } else if (m < macros.length) {
                    merged[k] = macros[m];
                    moffset = getOffset(macros, ++m);
                }
            }
            return merged;
        }

        private static int getOffset(IIndexMacro[] macros, int m) throws CoreException {
            if (m < macros.length) {
                IASTFileLocation fileLoc = macros[m].getFileLocation();
                if (fileLoc != null) {
                    return fileLoc.getNodeOffset();
                }
            }
            return Integer.MAX_VALUE;
        }

        private static int getOffset(IIndexInclude[] includes, int i) throws CoreException {
            if (i < includes.length) {
                return includes[i].getNameOffset();
            }
            return Integer.MAX_VALUE;
        }

        public Object[] getPreprocessingDirectives() throws CoreException {
            return fPreprocessingDirectives;
        }

        public ICPPUsingDirective[] getUsingDirectives() throws CoreException {
            return fDirectives;
        }

        public void setUsingDirectives(ICPPUsingDirective[] usingDirectives) {
            fDirectives = usingDirectives;
        }

        public void setPreprocessorDirectives(IIndexInclude[] includes, IIndexMacro[] macros) throws CoreException {
            fPreprocessingDirectives = merge(includes, macros);
        }
    }




    /**
     * @return array of linkage IDs that shall be parsed
     */
    protected int[] getLinkagesToParse() {
        //先默认只用它
        return PDOMManager.IDS_FOR_LINKAGES_TO_INDEX_C_FIRST;
    }
    public Map<String, IASTTranslationUnit> getTranslationUnitMap() {
        return translationUnitMap;
    }
    @Override
    protected void reportFileWrittenToIndex(FileInAST file, IIndexFragmentFile iFile) throws CoreException {

    }

}
