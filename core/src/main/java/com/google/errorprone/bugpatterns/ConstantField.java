/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone.bugpatterns;

import static com.google.errorprone.BugPattern.Category.JDK;
import static com.google.errorprone.BugPattern.MaturityLevel.MATURE;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;

import com.google.common.base.CaseFormat;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker.VariableTreeMatcher;
import com.google.errorprone.fixes.Fix;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.fixes.SuggestedFixes;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

/** @author cushon@google.com (Liam Miller-Cushon) */
@BugPattern(
  name = "ConstantField",
  category = JDK,
  summary = "Field name is CONSTANT_CASE, but field is not static and final",
  severity = SUGGESTION,
  maturity = MATURE
)
public class ConstantField extends BugChecker implements VariableTreeMatcher {

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    Symbol.VarSymbol sym = ASTHelpers.getSymbol(tree);
    if (sym == null || sym.getKind() != ElementKind.FIELD) {
      return Description.NO_MATCH;
    }
    String name = sym.getSimpleName().toString();
    if (sym.isStatic() && sym.getModifiers().contains(Modifier.FINAL)) {
      return checkImmutable(tree, state, sym, name);
    }
    if (!name.equals(name.toUpperCase())) {
      return Description.NO_MATCH;
    }
    return buildDescription(tree)
        .addFix(SuggestedFixes.addModifiers(tree, state, Modifier.FINAL, Modifier.STATIC))
        .addFix(
            renameFix(
                tree, state, name, CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, name)))
        .build();
  }

  private Description checkImmutable(
      VariableTree tree, VisitorState state, Symbol.VarSymbol sym, String name) {
    Type type = sym.type;
    if (type == null) {
      return Description.NO_MATCH;
    }
    switch (name) {
      case "serialVersionUID":
        // mandated by the Serializable API
        return Description.NO_MATCH;
      default:
        break;
    }
    if (name.toUpperCase().equals(name)) {
      return Description.NO_MATCH;
    }
    if (state.getTypes().unboxedTypeOrType(type).isPrimitive()
        || ASTHelpers.isSameType(type, state.getSymtab().stringType, state)) {
      String constName = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, name);
      return buildDescription(tree)
          .setMessage(
              String.format(
                  "%ss are immutable, field should be named '%s'",
                  sym.type.tsym.getSimpleName(), constName))
          .addFix(renameFix(tree, state, name, constName))
          .build();
    }
    return Description.NO_MATCH;
  }

  private Fix renameFix(VariableTree tree, VisitorState state, String name, final String newName) {
    int pos = ((JCTree) tree).getStartPosition() + state.getSourceForNode(tree).indexOf(name);
    final SuggestedFix.Builder fix =
        SuggestedFix.builder().replace(pos, pos + name.length(), newName);
    final Symbol.VarSymbol sym = ASTHelpers.getSymbol(tree);
    ((JCTree) state.getPath().getCompilationUnit())
        .accept(
            new TreeScanner() {
              @Override
              public void visitIdent(JCTree.JCIdent tree) {
                if (sym.equals(ASTHelpers.getSymbol(tree))) {
                  fix.replace(tree, newName);
                }
              }
            });
    return fix.build();
  }
}
