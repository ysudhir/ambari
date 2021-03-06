/**
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
package org.apache.ambari.server.actionmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ServiceComponentNotFoundException;
import org.apache.ambari.server.agent.ActionQueue;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.fsm.InvalidStateTransitionException;
import org.apache.ambari.server.state.svccomphost.ServiceComponentHostOpFailedEvent;
import org.apache.ambari.server.utils.StageUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

//This class encapsulates the action scheduler thread.
//Action schedule frequently looks at action database and determines if
//there is an action that can be scheduled.
class ActionScheduler implements Runnable {

  private static Log LOG = LogFactory.getLog(ActionScheduler.class);
  private final long actionTimeout;
  private final long sleepTime;
  private volatile boolean shouldRun = true;
  private Thread schedulerThread = null;
  private final ActionDBAccessor db;
  private final short maxAttempts;
  private final ActionQueue actionQueue;
  private final Clusters fsmObject;

  public ActionScheduler(long sleepTimeMilliSec, long actionTimeoutMilliSec,
      ActionDBAccessor db, ActionQueue actionQueue, Clusters fsmObject,
      int maxAttempts) {
    this.sleepTime = sleepTimeMilliSec;
    this.actionTimeout = actionTimeoutMilliSec;
    this.db = db;
    this.actionQueue = actionQueue;
    this.fsmObject = fsmObject;
    this.maxAttempts = (short) maxAttempts;
  }

  public void start() {
    schedulerThread = new Thread(this);
    schedulerThread.start();
  }

  public void stop() {
    shouldRun = false;
    schedulerThread.interrupt();
  }

  @Override
  public void run() {
    while (shouldRun) {
      try {
        Thread.sleep(sleepTime);
        doWork();
      } catch (InterruptedException ex) {
        LOG.warn("Scheduler thread is interrupted going to stop", ex);
        shouldRun = false;
      } catch (Exception ex) {
        LOG.warn("Exception received", ex);
      }
    }
  }

  private void doWork() throws AmbariException {
    List<Stage> stages = db.getStagesInProgress();
    if (LOG.isDebugEnabled()) {
      LOG.info("Scheduler wakes up");
    }
    if (stages == null || stages.isEmpty()) {
      //Nothing to do
      if (LOG.isDebugEnabled()) {
        LOG.info("No stage in progress..nothing to do");
      }
      return;
    }

    for (Stage s : stages) {
      List<ExecutionCommand> commandsToSchedule = new ArrayList<ExecutionCommand>();
      Map<String, RoleStats> roleStats = processInProgressStage(s, commandsToSchedule);
      //Check if stage is failed
      boolean failed = false;
      for (String role : roleStats.keySet()) {
        RoleStats stats = roleStats.get(role);
        LOG.info("Stats for role:"+role+", stats="+stats);
        if (stats.isRoleFailed()) {
          failed = true;
          break;
        }
      }
      if (failed) {
        LOG.warn("Operation completely failed, borting request id:"
            + s.getRequestId());
        db.abortOperation(s.getRequestId());
        return;
      }

      //Schedule what we have so far
      for (ExecutionCommand cmd : commandsToSchedule) {
        try {
          scheduleHostRole(s, cmd);
        } catch (InvalidStateTransitionException e) {
          LOG.warn("Could not schedule host role "+cmd.toString(), e);
          db.abortHostRole(cmd.getHostname(), s.getRequestId(), s.getStageId(),
              cmd.getRole());
        }
      }

      //Check if ready to go to next stage
      boolean goToNextStage = true;
      for (String role: roleStats.keySet()) {
        RoleStats stats = roleStats.get(role);
        if (!stats.isSuccessFactorMet()) {
          goToNextStage = false;
          break;
        }
      }
      if (!goToNextStage) {
        return;
      }
    }
  }

  /**
   * @param commandsToSchedule
   * @return Stats for the roles in the stage. It is used to determine whether stage
   * has succeeded or failed.
   */
  private Map<String, RoleStats> processInProgressStage(Stage s,
      List<ExecutionCommand> commandsToSchedule) {
    // Map to track role status
    Map<String, RoleStats> roleStats = new TreeMap<String, RoleStats>();
    long now = System.currentTimeMillis();
    for (String host : s.getHosts()) {
      List<ExecutionCommand> commands = s.getExecutionCommands(host);
      for(ExecutionCommand c : commands) {
        String roleStr = c.getRole().toString();
        RoleStats stats = roleStats.get(roleStr);
        if (stats == null) {
          stats = new RoleStats(s.getHosts().size(), 1);
          roleStats.put(roleStr, stats);
        }
        HostRoleStatus status = s.getHostRoleStatus(host, roleStr);
        LOG.info("Last attempt time =" + s.getLastAttemptTime(host, roleStr)
            + ", actiontimeout =" + this.actionTimeout + ", current time="
            + now+", status ="+status+", attempt count="+s.getAttemptCount(host, roleStr));
        if (timeOutActionNeeded(status, s, host, roleStr, now)) {
          LOG.info("Host:" + host + ", role:" + roleStr + ", actionId:"
              + s.getActionId() + " timed out");
          if (s.getAttemptCount(host, roleStr) >= maxAttempts) {
            LOG.warn("Host:" + host + ", role:" + roleStr + ", actionId:"
                + s.getActionId() + " expired");
            db.timeoutHostRole(host, s.getRequestId(), s.getStageId(),
                c.getRole());
            //Reinitialize status
            status = s.getHostRoleStatus(host, roleStr);
            ServiceComponentHostOpFailedEvent timeoutEvent =
                new ServiceComponentHostOpFailedEvent(roleStr,
                    host, now);
            try {
              Cluster cluster = fsmObject.getCluster(s.getClusterName());
              Service svc = cluster.getService(c.getServiceName());
              ServiceComponent svcComp = svc.getServiceComponent(
                  roleStr);
              ServiceComponentHost svcCompHost =
                  svcComp.getServiceComponentHost(host);
              svcCompHost.handleEvent(timeoutEvent);
            } catch (ServiceComponentNotFoundException scnex) {
              LOG.info("Not a service component, assuming its an action", scnex);
            } catch (InvalidStateTransitionException e) {
              LOG.info("Transition failed for host: "+host+", role: "+roleStr, e);
            } catch (AmbariException ex) {
              LOG.info("Invalid live state", ex);
            }
          } else {
            commandsToSchedule.add(c);
          }
        } else if (status.equals(HostRoleStatus.PENDING)) {
          //Need to schedule first time
          commandsToSchedule.add(c);
        }
        this.updateRoleStats(status, stats);
      }
    }
    return roleStats;
  }

  private boolean timeOutActionNeeded(HostRoleStatus status, Stage stage,
      String host, String role, long currentTime) {
    LOG.info("Last attempt time =" + stage.getLastAttemptTime(host, role)
        + ", actiontimeout =" + this.actionTimeout + ", current time="
        + currentTime+", role="+role+", status="+status);

    if (( !status.equals(HostRoleStatus.QUEUED) ) &&
        ( ! status.equals(HostRoleStatus.IN_PROGRESS) )) {
      return false;
    }
    if (currentTime > stage.getLastAttemptTime(host, role)+actionTimeout) {
      return true;
    }
    return false;
  }

  private void scheduleHostRole(Stage s, ExecutionCommand cmd)
      throws InvalidStateTransitionException, AmbariException {
    long now = System.currentTimeMillis();
    String roleStr = cmd.getRole().toString();
    String hostname = cmd.getHostname();
    LOG.info("Going to schedule: "+s.toString());
    if (s.getStartTime(hostname, roleStr) < 0) {
      LOG.info("Update state machine for first attempt");
      try {
        Cluster c = fsmObject.getCluster(s.getClusterName());
        Service svc = c.getService(cmd.getServiceName());
        ServiceComponent svcComp = svc.getServiceComponent(roleStr);
        ServiceComponentHost svcCompHost =
            svcComp.getServiceComponentHost(hostname);
        svcCompHost.handleEvent(s.getFsmEvent(hostname, roleStr));
      } catch (ServiceComponentNotFoundException scnex) {
        LOG.info("Not a service component, assuming its an action", scnex);
      } catch (InvalidStateTransitionException e) {
        LOG.info(
            "Transition failed for host: " + hostname + ", role: "
                + roleStr, e);
        throw e;
      } catch (AmbariException e) {
        LOG.info("Exception in fsm: " + hostname + ", role: " + roleStr,
            e);
        throw e;
      }
      s.setStartTime(hostname,roleStr, now);
      s.setHostRoleStatus(hostname, roleStr, HostRoleStatus.QUEUED);
    }
    s.setLastAttemptTime(hostname, roleStr, now);
    s.incrementAttemptCount(hostname, roleStr);
    LOG.info("Enqueueing in action queue for host: "+hostname);
    try {
      LOG.info("Command string = " + StageUtils.jaxbToString(cmd));
    } catch (Exception e) {
      throw new AmbariException("Could not get string from jaxb",e);
    }
    actionQueue.enqueue(hostname, cmd);
    db.hostRoleScheduled(s, hostname, roleStr);
  }

  private void updateRoleStats(HostRoleStatus status, RoleStats rs) {
    switch (status) {
    case COMPLETED:
      rs.numSucceeded++;
      break;
    case FAILED:
      rs.numFailed++;
      break;
    case QUEUED:
      rs.numQueued++;
      break;
    case PENDING:
      rs.numPending++;
      break;
    case TIMEDOUT:
      rs.numTimedOut++;
      break;
    case ABORTED:
      rs.numAborted++;
      break;
    default:
      LOG.error("Unknown status " + status.name());
    }
  }

  static class RoleStats {
    int numQueued = 0;
    int numSucceeded = 0;
    int numFailed = 0;
    int numTimedOut = 0;
    int numPending = 0;
    int numAborted = 0;
    final int totalHosts;
    final float successFactor;

    RoleStats(int total, float successFactor) {
      this.totalHosts = total;
      this.successFactor = successFactor;
    }

    /**
     * Role successful means the role is successful enough to
     */
    boolean isSuccessFactorMet() {
      if (successFactor <= (1.0*numSucceeded)/totalHosts) {
        return true;
      } else {
        return false;
      }
    }

    private boolean isRoleInProgress() {
      return (numPending+numQueued > 0);
    }

    /**
     * Role failure means role is no longer in progress and success factor is
     * not met.
     */
    boolean isRoleFailed() {
      if (isRoleInProgress() || isSuccessFactorMet()) {
        return false;
      } else {
        return true;
      }
    }

    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("numQueued="+numQueued);
      builder.append(", numSucceeded="+numSucceeded);
      builder.append(", numFailed="+numFailed);
      builder.append(", numTimedOut="+numTimedOut);
      builder.append(", numPending="+numPending);
      builder.append(", numAborted="+numAborted);
      builder.append(", totalHosts="+totalHosts);
      builder.append(", successFactor="+successFactor);
      return builder.toString();
    }
  }
}
