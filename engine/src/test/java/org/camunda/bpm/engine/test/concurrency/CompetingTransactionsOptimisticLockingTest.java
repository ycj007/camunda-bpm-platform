/*
 * Copyright © 2013-2018 camunda services GmbH and various authors (info@camunda.com)
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
package org.camunda.bpm.engine.test.concurrency;

import org.camunda.bpm.engine.OptimisticLockingException;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.ProcessEngineLogger;
import org.camunda.bpm.engine.impl.cmd.CompleteTaskCmd;
import org.camunda.bpm.engine.impl.db.sql.DbSqlSessionFactory;
import org.camunda.bpm.engine.impl.test.PluggableProcessEngineTestCase;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.util.DatabaseHelper;
import org.slf4j.Logger;

import java.sql.Connection;
import java.util.List;

/**
 * @author Nikola Koevski
 */
public class CompetingTransactionsOptimisticLockingTest extends PluggableProcessEngineTestCase{

  private static Logger LOG = ProcessEngineLogger.TEST_LOGGER.getLogger();
  static ControllableThread activeThread;

  public class TransactionThread extends ControllableThread {
    String taskId;
    ProcessEngineException exception;

    public TransactionThread(String taskId) {
      this.taskId = taskId;
    }

    @Override
    public synchronized void startAndWaitUntilControlIsReturned() {
      activeThread = this;
      super.startAndWaitUntilControlIsReturned();
    }

    @Override
    public void run() {
      try {
        processEngineConfiguration
          .getCommandExecutorTxRequired()
          .execute(new ControlledCommand(activeThread, new CompleteTaskCmd(taskId, null)));

      } catch (ProcessEngineException e) {
        this.exception = e;
      }
      LOG.debug(getName() + " ends.");
    }
  }

  @Override
  protected void runTest() throws Throwable {
    String databaseType = DatabaseHelper.getDatabaseType(processEngineConfiguration);

    if (DbSqlSessionFactory.POSTGRES.equals(databaseType)) {
      // skip test method - if database is PostgreSQL
    } else {
      // invoke the test method
      super.runTest();
    }
  }

  @Deployment
  public void testCompetingTransactionsOptimisticLocking() throws Exception {
    // given
    runtimeService.startProcessInstanceByKey("competingTransactionsProcess");
    List<Task> tasks = taskService.createTaskQuery().list();

    assertEquals(2, tasks.size());

    Task firstTask = "task1-1".equals(tasks.get(0).getTaskDefinitionKey()) ? tasks.get(0) : tasks.get(1);
    Task secondTask = "task2-1".equals(tasks.get(0).getTaskDefinitionKey()) ? tasks.get(0) : tasks.get(1);

    TransactionThread thread1 = new TransactionThread(firstTask.getId());
    thread1.startAndWaitUntilControlIsReturned();
    TransactionThread thread2 = new TransactionThread(secondTask.getId());
    thread2.startAndWaitUntilControlIsReturned();

    thread2.proceedAndWaitTillDone();
    assertNull(thread2.exception);

    thread1.proceedAndWaitTillDone();
    assertNotNull(thread1.exception);
    assertEquals(OptimisticLockingException.class, thread1.exception.getClass());
  }
}
