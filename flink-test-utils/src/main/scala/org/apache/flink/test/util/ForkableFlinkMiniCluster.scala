/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.util

import akka.actor.{Props, ActorRef, ActorSystem}
import akka.pattern.Patterns._
import org.apache.flink.configuration.{ConfigConstants, Configuration}
import org.apache.flink.runtime.StreamingMode
import org.apache.flink.runtime.jobmanager.JobManager
import org.apache.flink.runtime.minicluster.LocalFlinkMiniCluster
import org.apache.flink.runtime.taskmanager.TaskManager
import org.apache.flink.runtime.testingUtils.{TestingUtils, TestingJobManager,
TestingMemoryArchivist, TestingTaskManager}
import org.apache.flink.runtime.webmonitor.WebMonitor

import scala.concurrent.Await

/**
 * A forkable mini cluster is a special case of the mini cluster, used for parallel test execution
 * on build servers. If multiple tests run in parallel, the cluster picks up the fork number and
 * uses it to avoid port conflicts.
 *
 * @param userConfiguration Configuration object with the user provided configuration values
 * @param singleActorSystem true, if all actors (JobManager and TaskManager) shall be run in the
 *                          same [[ActorSystem]], otherwise false.
 */
class ForkableFlinkMiniCluster(
    userConfiguration: Configuration,
    singleActorSystem: Boolean,
    streamingMode: StreamingMode)
  extends LocalFlinkMiniCluster(userConfiguration, singleActorSystem, streamingMode) {
  

  def this(userConfiguration: Configuration, singleActorSystem: Boolean) 
       = this(userConfiguration, singleActorSystem, StreamingMode.BATCH_ONLY)

  def this(userConfiguration: Configuration) = this(userConfiguration, true)
  
  // --------------------------------------------------------------------------
  
  override def generateConfiguration(userConfiguration: Configuration): Configuration = {
    val forNumberString = System.getProperty("forkNumber")

    val forkNumber = try {
      Integer.parseInt(forNumberString)
    }
    catch {
      case e: NumberFormatException => -1
    }

    val config = userConfiguration.clone()

    if (forkNumber != -1) {
      val jobManagerRPC = 1024 + forkNumber*300
      val taskManagerRPC = 1024 + forkNumber*300 + 100
      val taskManagerData = 1024 + forkNumber*300 + 200

      config.setInteger(ConfigConstants.JOB_MANAGER_IPC_PORT_KEY, jobManagerRPC)
      config.setInteger(ConfigConstants.TASK_MANAGER_IPC_PORT_KEY, taskManagerRPC)
      config.setInteger(ConfigConstants.TASK_MANAGER_DATA_PORT_KEY, taskManagerData)
    }

    super.generateConfiguration(config)
  }

  override def startJobManager(actorSystem: ActorSystem): (ActorRef, Option[WebMonitor]) = {

    val (executionContext,
      instanceManager,
      scheduler,
      libraryCacheManager,
      _,
      executionRetries,
      delayBetweenRetries,
      timeout,
      archiveCount) = JobManager.createJobManagerComponents(configuration)

    val testArchiveProps = Props(
      new TestingMemoryArchivist(archiveCount))

    val archiver = actorSystem.actorOf(testArchiveProps, JobManager.ARCHIVE_NAME)
    
    val jobManagerProps = Props(
      new TestingJobManager(
        configuration,
        executionContext,
        instanceManager,
        scheduler,
        libraryCacheManager,
        archiver,
        executionRetries,
        delayBetweenRetries,
        timeout,
        streamingMode))

    val jobManager = actorSystem.actorOf(jobManagerProps, JobManager.JOB_MANAGER_NAME)

    val webMonitorOption = startWebServer(configuration, jobManager, archiver)

    (jobManager, webMonitorOption)
  }

  override def startTaskManager(index: Int, system: ActorSystem): ActorRef = {
    val config = configuration.clone()

    val rpcPort = config.getInteger(ConfigConstants.TASK_MANAGER_IPC_PORT_KEY,
                                    ConfigConstants.DEFAULT_TASK_MANAGER_IPC_PORT)

    val dataPort = config.getInteger(ConfigConstants.TASK_MANAGER_DATA_PORT_KEY,
                                     ConfigConstants.DEFAULT_TASK_MANAGER_DATA_PORT)

    if (rpcPort > 0) {
      config.setInteger(ConfigConstants.TASK_MANAGER_IPC_PORT_KEY, rpcPort + index)
    }
    if (dataPort > 0) {
      config.setInteger(ConfigConstants.TASK_MANAGER_DATA_PORT_KEY, dataPort + index)
    }

    val localExecution = numTaskManagers == 1

    val jobManagerAkkaUrl: Option[String] = if (singleActorSystem) {
      Some(jobManagerActor.path.toString)
    } else {
      None
    }

    TaskManager.startTaskManagerComponentsAndActor(config, system, hostname,
        Some(TaskManager.TASK_MANAGER_NAME + index), jobManagerAkkaUrl, localExecution,
      streamingMode, classOf[TestingTaskManager])
  }

  def restartJobManager(): Unit = {
    val stopped = gracefulStop(jobManagerActor, TestingUtils.TESTING_DURATION)
    Await.result(stopped, TestingUtils.TESTING_DURATION)

    webMonitor foreach {
      _.stop()
    }

    jobManagerActorSystem.shutdown()
    jobManagerActorSystem.awaitTermination()

    jobManagerActorSystem = startJobManagerActorSystem()
    val (newJobManagerActor, newWebMonitor) = startJobManager(jobManagerActorSystem)

    jobManagerActor = newJobManagerActor
    webMonitor = newWebMonitor
  }

  def restartTaskManager(index: Int): Unit = {
    val stopped = gracefulStop(taskManagerActors(index), TestingUtils.TESTING_DURATION)
    Await.result(stopped, TestingUtils.TESTING_DURATION)

    taskManagerActorSystems(index).shutdown()
    taskManagerActorSystems(index).awaitTermination()

    val taskManagerActorSystem  = startTaskManagerActorSystem(index)
    val taskManagerActor = startTaskManager(index, taskManagerActorSystem)

    taskManagerActors = taskManagerActors.patch(index, Seq(taskManagerActor), 1)
    taskManagerActorSystems = taskManagerActorSystems.patch(index, Seq(taskManagerActorSystem), 1)
  }
}

object ForkableFlinkMiniCluster {

  import org.apache.flink.runtime.testingUtils.TestingUtils.DEFAULT_AKKA_ASK_TIMEOUT

  def startCluster(numSlots: Int,
                   numTaskManagers: Int,
                   timeout: String = DEFAULT_AKKA_ASK_TIMEOUT): ForkableFlinkMiniCluster = {

    val config = new Configuration()
    config.setInteger(ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS, numSlots)
    config.setInteger(ConfigConstants.LOCAL_INSTANCE_MANAGER_NUMBER_TASK_MANAGER, numTaskManagers)
    config.setString(ConfigConstants.AKKA_ASK_TIMEOUT, timeout)
    new ForkableFlinkMiniCluster(config)
  }
}
