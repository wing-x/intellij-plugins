/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.jetbrains.lang.dart.psi.impl;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import com.intellij.util.IncorrectOperationException;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import com.jetbrains.lang.dart.analyzer.DartServerData.DartNavigationRegion;
import com.jetbrains.lang.dart.analyzer.DartServerData.DartNavigationTarget;
import com.jetbrains.lang.dart.psi.DartFile;
import com.jetbrains.lang.dart.resolve.DartResolver;
import com.jetbrains.lang.dart.util.DartResolveUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Reference to a file in an import, export or part directive.
 */
public class DartFileReference implements PsiPolyVariantReference {
  private static final Resolver RESOLVER = new Resolver();

  @NotNull private final PsiElement myElement;
  @NotNull private final String myUri;
  @NotNull private final TextRange myRange;

  public DartFileReference(@NotNull final DartUriElementBase uriRefExpr, @NotNull final String uri) {
    final int offset = uriRefExpr.getText().indexOf(uri);
    assert offset >= 0 : uriRefExpr.getText() + " doesn't contain " + uri;

    myElement = uriRefExpr;
    myUri = uri;
    myRange = TextRange.create(offset, offset + uri.length());
  }

  @NotNull
  @Override
  public PsiElement getElement() {
    return myElement;
  }

  @Override
  public TextRange getRangeInElement() {
    return myRange;
  }

  @NotNull
  @Override
  public ResolveResult[] multiResolve(boolean incompleteCode) {
    return ResolveCache.getInstance(myElement.getProject()).resolveWithCaching(this, RESOLVER, true, incompleteCode);
  }

  @Nullable
  @Override
  public PsiElement resolve() {
    final ResolveResult[] resolveResults = multiResolve(false);
    return resolveResults.length == 0 ||
           resolveResults.length > 1 ||
           !resolveResults[0].isValidResult() ? null : resolveResults[0].getElement();
  }

  @NotNull
  @Override
  public String getCanonicalText() {
    return myUri;
  }

  @Override
  public PsiElement handleElementRename(final String newElementName) throws IncorrectOperationException {
    return myElement;
  }

  @Override
  public PsiElement bindToElement(@NotNull final PsiElement element) throws IncorrectOperationException {
    return element;
  }

  @Override
  public boolean isReferenceTo(final PsiElement element) {
    return element instanceof DartFile && element.equals(resolve());
  }

  @NotNull
  @Override
  public Object[] getVariants() {
    return EMPTY_ARRAY;
  }

  @Override
  public boolean isSoft() {
    return false;
  }

  private static class Resolver implements ResolveCache.PolyVariantResolver<DartFileReference> {
    @NotNull
    @Override
    public ResolveResult[] resolve(@NotNull final DartFileReference reference, final boolean incompleteCode) {
      final PsiFile refPsiFile = reference.getElement().getContainingFile();
      final int refOffset = reference.getElement().getTextOffset();
      final int refLength = reference.getElement().getTextLength();

      DartNavigationRegion region = DartResolver.findRegion(refPsiFile, refOffset, refLength);

      if (region == null) {
        // file might be not open in editor, so we do not have navigation information for it
        final VirtualFile virtualFile = DartResolveUtil.getRealVirtualFile(refPsiFile);
        if (virtualFile != null &&
            DartAnalysisServerService.getInstance().getNavigation(virtualFile).isEmpty() &&
            DartAnalysisServerService.getInstance().getHighlight(virtualFile).isEmpty()) {
          final PsiElement parent = reference.getElement().getParent();
          final int parentOffset = parent.getTextOffset();
          final int parentLength = parent.getTextLength();
          final List<DartNavigationRegion> regions =
            DartAnalysisServerService.getInstance().analysis_getNavigation(virtualFile, parentOffset, parentLength);
          if (regions != null) {
            region = DartResolver.findRegion(regions, refOffset, refLength);
          }
        }
      }

      if (region != null) {
        final List<DartNavigationTarget> targets = region.getTargets();
        if (!targets.isEmpty()) {
          final DartNavigationTarget target = targets.get(0);
          final String targetPath = target.getFile();
          final VirtualFile targetVirtualFile = LocalFileSystem.getInstance().findFileByPath(targetPath);
          if (targetVirtualFile != null) {
            final PsiFile targetFile = reference.getElement().getManager().findFile(targetVirtualFile);
            if (targetFile != null) {
              return new ResolveResult[]{new PsiElementResolveResult(targetFile)};
            }
          }
        }
      }


      return ResolveResult.EMPTY_ARRAY;
    }
  }
}
