/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.ExpressionUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Stream;

/**
 * @author Tagir Valeev
 */
public class ExcessiveLambdaUsageInspection extends BaseJavaBatchLocalInspectionTool {
  private static final ExcessiveLambdaInfo[] INFOS = {
    new ExcessiveLambdaInfo(CommonClassNames.JAVA_UTIL_MAP, "computeIfAbsent", "putIfAbsent", 1, false),
    new ExcessiveLambdaInfo(CommonClassNames.JAVA_UTIL_OPTIONAL, "orElseGet", "orElse", 0, true),
    new ExcessiveLambdaInfo("java.util.OptionalInt", "orElseGet", "orElse", 0, true),
    new ExcessiveLambdaInfo("java.util.OptionalLong", "orElseGet", "orElse", 0, true),
    new ExcessiveLambdaInfo("java.util.OptionalDouble", "orElseGet", "orElse", 0, true),
    new ExcessiveLambdaInfo("java.util.OptionalDouble", "orElseGet", "orElse", 0, true),
    new ExcessiveLambdaInfo("com.google.common.base.Optional", "or", "or", 0, true),
    new ExcessiveLambdaInfo("java.util.Objects", "requireNonNull", "requireNonNull", 1, true)
  };

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    if (!PsiUtil.isLanguageLevel8OrHigher(holder.getFile())) {
      return PsiElementVisitor.EMPTY_VISITOR;
    }
    return new JavaElementVisitor() {
      @Override
      public void visitLambdaExpression(PsiLambdaExpression lambda) {
        PsiElement parent = lambda.getParent();
        if (!(parent instanceof PsiExpressionList)) return;
        PsiElement gParent = parent.getParent();
        if (!(gParent instanceof PsiMethodCallExpression)) return;
        if (!(lambda.getBody() instanceof PsiExpression)) return;
        PsiExpression expr = (PsiExpression)lambda.getBody();
        if (!ExpressionUtils.isSimpleExpression(expr)) return;
        if (Stream.of(lambda.getParameterList().getParameters()).anyMatch(param -> ExpressionUtils.isReferenceTo(expr, param))) return;

        for (ExcessiveLambdaInfo info : INFOS) {
          if(info.isApplicable((PsiMethodCallExpression)gParent, lambda)) {
            holder.registerProblem(lambda, InspectionsBundle.message("inspection.excessive.lambda.message"),
                                   ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                                   new TextRange(0, expr.getTextOffset() - lambda.getTextOffset()), new RemoveExcessiveLambdaFix(info));
          }
        }
      }
    };
  }

  static class RemoveExcessiveLambdaFix implements LocalQuickFix {
    private final ExcessiveLambdaInfo myInfo;

    public RemoveExcessiveLambdaFix(ExcessiveLambdaInfo info) {
      myInfo = info;
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return InspectionsBundle.message("inspection.excessive.lambda.fix.name", myInfo.myConstantMethod);
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return InspectionsBundle.message("inspection.excessive.lambda.fix.family.name");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      PsiElement element = descriptor.getStartElement();
      if(!(element instanceof PsiLambdaExpression)) return;
      PsiLambdaExpression lambda = (PsiLambdaExpression)element;
      PsiElement body = lambda.getBody();
      if(body == null) return;
      PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(lambda, PsiMethodCallExpression.class);
      if(call == null) return;

      call.getMethodExpression().handleElementRename(myInfo.myConstantMethod);
      CommentTracker ct = new CommentTracker();
      PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
      ct.replaceAndRestoreComments(lambda, factory.createExpressionFromText(ct.text(body), lambda));
    }
  }

  static class ExcessiveLambdaInfo {
    final String myClass;
    final String myLambdaMethod;
    final String myConstantMethod;
    final int myParameterIndex;
    final boolean myCanUseReturnValue;

    ExcessiveLambdaInfo(String aClass, String lambdaMethod, String constantMethod, int index, boolean canUseReturnValue) {
      myClass = aClass;
      myLambdaMethod = lambdaMethod;
      myConstantMethod = constantMethod;
      myParameterIndex = index;
      myCanUseReturnValue = canUseReturnValue;
    }

    boolean isApplicable(PsiMethodCallExpression call, PsiLambdaExpression lambda) {
      if(!myLambdaMethod.equals(call.getMethodExpression().getReferenceName())) return false;
      if(!myCanUseReturnValue && !(call.getParent() instanceof PsiExpressionStatement)) return false;
      PsiExpression[] args = call.getArgumentList().getExpressions();
      if(args.length <= myParameterIndex || args[myParameterIndex] != lambda) return false;
      PsiMethod method = call.resolveMethod();
      if(method == null) return false;
      PsiParameter[] parameters = method.getParameterList().getParameters();
      if(parameters.length <= myParameterIndex) return false;
      PsiClass fnClass = PsiUtil.resolveClassInClassTypeOnly(parameters[myParameterIndex].getType());
      return fnClass != null && LambdaUtil.getFunction(fnClass) != null &&
             InheritanceUtil.isInheritor(method.getContainingClass(), false, myClass);
    }
  }
}
