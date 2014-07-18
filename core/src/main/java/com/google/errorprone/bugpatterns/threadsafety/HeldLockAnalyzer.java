/*
 * Copyright 2014 Google Inc. All Rights Reserved.
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

package com.google.errorprone.bugpatterns.threadsafety;

import static com.google.errorprone.matchers.Matchers.expressionMethodSelect;
import static com.google.errorprone.matchers.Matchers.isDescendantOfMethod;
import static com.google.errorprone.matchers.Matchers.methodSelect;

import com.google.errorprone.JDKCompatible;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.threadsafety.GuardedByExpression.Kind;
import com.google.errorprone.bugpatterns.threadsafety.GuardedByExpression.Select;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCExpression;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.lang.model.element.Modifier;

/**
 * Analyzes a method body, tracking the set of held locks and checking accesses to guarded members.
 *
 * @author cushon@google.com (Liam Miller-Cushon)
 */
public class HeldLockAnalyzer {

  /**
   * Listener interface for accesses to guarded members.
   */
  public interface LockEventListener {

    /**
     * Handle a guarded member access.
     *
     * @param tree The member access expression.
     * @param guard The member's guard expression.
     * @param locks The set of held locks.
     */
    void handleGuardedAccess(ExpressionTree tree, GuardedByExpression guard, HeldLockSet locks);
  }

  /**
   * Analyze a method body, tracking the set of held locks and checking accesses to guarded members.
   */
  public static void analyze(VisitorState state, LockEventListener listener) {
    new LockScanner(state, listener).scan(state.getPath(), HeldLockSet.empty());
  }

  private static class LockScanner extends TreePathScanner<Void, HeldLockSet> {

    private final VisitorState visitorState;
    private final LockEventListener listener;

    private static final GuardedByExpression.Factory F = new GuardedByExpression.Factory();

    private LockScanner(VisitorState visitorState, LockEventListener listener) {
      this.visitorState = visitorState;
      this.listener = listener;
    }

    @Override
    public Void visitMethod(MethodTree tree, HeldLockSet locks) {
      // Synchronized instance methods hold the 'this' lock; synchronized static methods
      // hold the Class lock for the enclosing class.
      Set<Modifier> mods = tree.getModifiers().getFlags();
      if (mods.contains(Modifier.SYNCHRONIZED)) {
        Symbol owner = (((JCTree.JCMethodDecl) tree).sym.owner);
        GuardedByExpression lock =
            mods.contains(Modifier.STATIC) ? F.classLiteral(owner) : F.thisliteral();
        locks = locks.plus(lock);
      }

      // @GuardedBy annotations on methods are trusted for declarations, and checked
      // for invocations.
      String guard = GuardedByUtils.getGuardValue(tree);
      if (guard != null) {
        GuardedByExpression bound;
        try {
          bound =
              GuardedByBinder.bindString(guard, GuardedBySymbolResolver.from(tree, visitorState));
          locks = locks.plus(bound);
        } catch (IllegalGuardedBy unused) {
          // Errors about bad @GuardedBy expressions are handled earlier.
        }
      }

      return super.visitMethod(tree, locks);
    }

    @Override
    public Void visitTry(TryTree tree, HeldLockSet locks) {
      // TODO(user) - recognize common try-with-resources patterns
      // Currently there is no standard implementation of an AutoCloseable lock resource to detect.
      scan(JDKCompatible.getTryTreeResources(tree), locks);

      // Cheesy try/finally heuristic: assume that all locks released in the finally
      // are held for the entirety of the try and catch statements.
      Collection<GuardedByExpression> releasedLocks =
          ReleasedLockFinder.find(tree.getFinallyBlock(), visitorState);
      scan(tree.getBlock(), locks.plusAll(releasedLocks));
      scan(tree.getCatches(), locks.plusAll(releasedLocks));

      scan(tree.getFinallyBlock(), locks);
      return null;
    }

    @Override
    public Void visitSynchronized(SynchronizedTree tree, HeldLockSet locks) {
      // The synchronized expression is held in the body of the synchronized statement:
      scan(tree.getBlock(),
          locks.plus(GuardedByBinder.bindExpression(
              ((JCTree.JCParens) tree.getExpression()).getExpression(), visitorState)));
      return null;
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree tree, HeldLockSet p) {
      checkMatch(tree, p);
      return super.visitMemberSelect(tree, p);
    }

    @Override
    public Void visitIdentifier(IdentifierTree tree, HeldLockSet p) {
      checkMatch(tree, p);
      return super.visitIdentifier(tree, p);
    }

    @Override
    public Void visitNewClass(NewClassTree tree, HeldLockSet p) {
      // Don't visit into anonymous class declarations; their method declarations
      // will be analyzed separately.
      return null;
    }

    private void checkMatch(ExpressionTree tree, HeldLockSet locks) {
      String guardString = GuardedByUtils.getGuardValue(tree);
      if (guardString == null) {
        return;
      }

      GuardedByExpression boundGuard = null;
      try {
        GuardedByExpression guard = GuardedByBinder.bindString(guardString,
            GuardedBySymbolResolver.from(tree, visitorState));
        boundGuard = ExpectedLockCalculator.from((JCTree.JCExpression) tree, guard, visitorState);
      } catch (IllegalGuardedBy unused) {
        // Errors about bad @GuardedBy expressions are handled earlier.
      }

      if (boundGuard != null) {
        listener.handleGuardedAccess(tree, boundGuard, locks);
      }

      return;
    }
  }

  /**
   * Find the locks that are released in the given tree.
   * (e.g. the 'finally' clause of a try/finally)
   */
  private static class ReleasedLockFinder extends TreeScanner<Void, Void> {

    static Collection<GuardedByExpression> find(Tree tree, VisitorState state) {
      if (tree == null) {
        return Collections.emptyList();
      }
      ReleasedLockFinder finder = new ReleasedLockFinder(state);
      tree.accept(finder, null);
      return finder.locks;
    }

    private static final String LOCK_CLASS = "java.util.concurrent.locks.Lock";
    private static final String READ_WRITE_LOCK_CLASS = "java.util.concurrent.locks.ReadWriteLock";
    private static final String MONITOR_CLASS = "com.google.common.util.concurrent.Monitor";

    /** Matcher for methods that release lock resources. */
    private static final Matcher<MethodInvocationTree> LOCK_RELEASE_MATCHER = methodSelect(
        Matchers.<ExpressionTree>anyOf(
            isDescendantOfMethod(LOCK_CLASS, "unlock()"),
            isDescendantOfMethod(MONITOR_CLASS, "leave()")));

    /** Matcher for ReadWriteLock lock accessors. */
    private static final Matcher<ExpressionTree> READ_WRITE_RELEASE_MATCHER =
        expressionMethodSelect(
            Matchers.<ExpressionTree>anyOf(
                isDescendantOfMethod(READ_WRITE_LOCK_CLASS, "readLock()"),
                isDescendantOfMethod(READ_WRITE_LOCK_CLASS, "writeLock()")));

    private final VisitorState state;
    private final Set<GuardedByExpression> locks = new HashSet<GuardedByExpression>();

    private ReleasedLockFinder(VisitorState state) {
      this.state = state;
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
      if (LOCK_RELEASE_MATCHER.matches(tree, state)) {
        GuardedByExpression node = GuardedByBinder.bindExpression((JCExpression) tree, state);
        GuardedByExpression receiver = ((GuardedByExpression.Select) node).base;
        locks.add(receiver);

        // The analysis interprets members guarded by {@link ReadWriteLock}s as requiring that
        // either the read or write lock is held for all accesses, but doesn't enforce a policy
        // for which of the two is held. Technically the write lock should be required while writing
        // to the guarded member and the read lock should be used for all other accesses, but in
        // practice the write lock is frequently held while performing a mutating operation on the
        // object stored in the field (e.g. inserting into a List).
        // TODO(user): investigate a better way to specify the contract for ReadWriteLocks.
        if ((tree.getMethodSelect() instanceof MemberSelectTree)
            && READ_WRITE_RELEASE_MATCHER.matches(ASTHelpers.getReceiver(tree), state)) {
          locks.add(((Select) receiver).base);
        }
      }
      return null;
    }
  }

  static class ExpectedLockCalculator {

    private static final GuardedByExpression.Factory F = new GuardedByExpression.Factory();

    /**
     * Determine the lock expression that needs to be held when accessing a specific guarded
     * member.
     *
     * If the lock expression resolves to an instance member, the result will be a select
     * expression with the same base as the original guarded member access.
     *
     * For example:
     * <code>
     * class MyClass {
     *   final Object mu = new Object();
     *   @GuardedBy("mu")
     *   int x;
     * }
     * void m(MyClass myClass) {
     *   myClass.x++;
     * }
     * </code>
     *
     * To determine the lock that must be held when accessing myClass.x,
     * from is called with "myClass.x" and "mu", and returns "myClass.mu".
     */
    static GuardedByExpression from(
        JCTree.JCExpression guardedMemberExpression,
        GuardedByExpression guard,
        VisitorState state) {

      if (isGuardReferenceAbsolute(guard)) {
        return guard;
      }

      GuardedByExpression guardedMember =
          GuardedByBinder.bindExpression(guardedMemberExpression, state);
      GuardedByExpression memberBase = ((GuardedByExpression.Select) guardedMember).base;
      return helper(guard, memberBase);
    }

    /**
     * Returns true for guard expressions that require an 'absolute' reference, i.e. where the
     * expression to access the lock is always the same, regardless of how the guarded member
     * is accessed.
     *
     * <p>E.g.:
     * <ul><li>class object: 'TypeName.class'
     * <li>static access: 'TypeName.member'
     * <li>enclosing instance: 'Outer.this'
     * <li>enclosing instance member: 'Outer.this.member'
     * </ul>
     */
    private static boolean isGuardReferenceAbsolute(GuardedByExpression guard) {

      GuardedByExpression instance = guard.kind() == Kind.SELECT
          ? getSelectInstance(guard)
          : guard;

      return instance.kind() != Kind.THIS;
    }

    /**
     * Get the base expression of a (possibly nested) member select expression.
     */
    private static GuardedByExpression getSelectInstance(GuardedByExpression guard) {
      if (guard instanceof Select) {
        return getSelectInstance(((Select) guard).base);
      }
      return guard;
    }

    private static GuardedByExpression helper(
        GuardedByExpression lockExpression, GuardedByExpression memberAccess) {
      switch (lockExpression.kind()) {
        case SELECT: {
          GuardedByExpression.Select lockSelect = (GuardedByExpression.Select) lockExpression;
          return F.select(helper(lockSelect.base, memberAccess), lockSelect.sym());
        }
        case THIS:
          return memberAccess;
        default:
          throw new IllegalGuardedBy(lockExpression.toString());
      }
    }
  }
}
