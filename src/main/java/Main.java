import change.CustomIndexerInputAdapter;
import change.CustomIndexerTask;
import change.CustomRegistryProvider;
import org.eclipse.cdt.core.dom.ast.ASTVisitor;
import org.eclipse.cdt.core.dom.ast.IASTBinaryExpression;
import org.eclipse.cdt.core.dom.ast.IASTExpression;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws CoreException, InterruptedException {
        String rootPath = "D:\\git\\modify-cdt\\example\\zhizhixiongExample\\src";
        String libPath = "D:\\git\\modify-cdt\\example\\zhizhixiongExample\\include";
//        String libPath = "";
        String[] includes=new String[]{rootPath,libPath};
        CustomRegistryProvider.init();
        CustomIndexerInputAdapter indexerInputAdapter = new CustomIndexerInputAdapter();
        CustomIndexerTask indexerTask = new CustomIndexerTask(indexerInputAdapter,rootPath,includes);
        indexerTask.runTask(new NullProgressMonitor());
        List<IASTTranslationUnit> translationUnits = indexerTask.getTranslationUnitMap().entrySet().stream().map(Map.Entry::getValue).collect(Collectors.toList());
        for (IASTTranslationUnit translationUnit : translationUnits) {

            translationUnit.accept(new ASTVisitor(true) {
                @Override
                public int visit(IASTExpression expression) {
                    if (expression instanceof IASTBinaryExpression binaryExpression) {
                        IASTExpression operand1 = binaryExpression.getOperand1();
                        IASTExpression operand2 = binaryExpression.getOperand2();
                        System.out.println();
                    }
                    return PROCESS_CONTINUE;
                }
            });
        }
    }
}
