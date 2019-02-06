/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.test.dunit.rules;

import static org.apache.geode.test.dunit.VM.DEFAULT_VM_COUNT;

import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.VMEventListener;
import org.apache.geode.test.dunit.internal.DUnitLauncher;
import org.apache.geode.test.dunit.internal.TestHistoryLogger;
import org.apache.geode.test.junit.rules.serializable.SerializableStatement;
import org.apache.geode.test.junit.rules.serializable.SerializableTestRule;

class AbstractDistributedRule implements SerializableTestRule {

  private final int vmCount;
  private final RemoteInvoker invoker;

  protected AbstractDistributedRule() {
    this(DEFAULT_VM_COUNT);
  }

  protected AbstractDistributedRule(final int vmCount) {
    this(vmCount, new RemoteInvoker());
  }

  protected AbstractDistributedRule(final int vmCount, final RemoteInvoker invoker) {
    this.vmCount = vmCount;
    this.invoker = invoker;
  }

  @Override
  public Statement apply(final Statement statement, final Description description) {
    return statement(statement, description);
  }

  private Statement statement(final Statement baseStatement, final Description description) {
    return new SerializableStatement() {
      @Override
      public void evaluate() throws Throwable {
        VMEventListener vmEventListener = new InternalVMEventListener();
        beforeDistributedTest(description);
        VM.addVMEventListener(vmEventListener);
        before();
        try {
          baseStatement.evaluate();
        } finally {
          after();
          VM.removeVMEventListener(vmEventListener);
          afterDistributedTest(description);
        }
      }
    };
  }

  private void beforeDistributedTest(final Description description) throws Throwable {
    TestHistoryLogger.logTestHistory(description.getTestClass().getSimpleName(),
        description.getMethodName());
    DUnitLauncher.launchIfNeeded(vmCount);
    System.out.println("\n\n[setup] START TEST " + description.getClassName() + "."
        + description.getMethodName());
  }

  private void afterDistributedTest(final Description description) throws Throwable {
    System.out.println("\n\n[setup] END TEST " + description.getTestClass().getSimpleName()
        + "." + description.getMethodName());
  }

  protected void before() throws Throwable {
    // override if needed
  }

  protected void after() throws Throwable {
    // override if needed
  }

  protected RemoteInvoker invoker() {
    return invoker;
  }

  protected int vmCount() {
    return vmCount;
  }

  protected void afterCreateVM(VM vm) {
    // override if needed
  }

  protected void beforeBounceVM(VM vm) {
    // override if needed
  }

  protected void afterBounceVM(VM vm) {
    // override if needed
  }

  private class InternalVMEventListener implements VMEventListener {

    @Override
    public void afterCreateVM(VM vm) {
      AbstractDistributedRule.this.afterCreateVM(vm);
    }

    @Override
    public void beforeBounceVM(VM vm) {
      AbstractDistributedRule.this.beforeBounceVM(vm);
    }

    @Override
    public void afterBounceVM(VM vm) {
      AbstractDistributedRule.this.afterBounceVM(vm);
    }
  }
}
