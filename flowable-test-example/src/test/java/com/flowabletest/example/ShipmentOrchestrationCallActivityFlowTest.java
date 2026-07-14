package com.flowabletest.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises shipment-orchestration.bpmn20.xml / carrier-dispatch.bpmn20.xml: a parent process with
 * an embedded (non-event) subProcess and a multi-instance service task, calling a second,
 * independently deployed process definition via callActivity. Proves {@link
 * ProcessTestHarness#awaitCallActivityChild} correctly locates the child process instance, and that
 * the child's boundary-timer escalation branch completes the call activity just as reliably as the
 * manual happy path.
 *
 * <p>The escalation test skips {@code carrierPickupTimeoutBoundary}'s real {@code PT4H} duration
 * via {@link ManagementService#moveTimerToExecutableJob}, but still lets the starter's own async
 * executor -- already running per {@code async-executor-activate: true} -- pick up and run the job,
 * exactly as it would in production; calling {@link ManagementService#executeJob} directly from the
 * test thread races that background executor for the same job and deadlocks under Postgres.
 */
@FlowableProcessTest
class ShipmentOrchestrationCallActivityFlowTest {

  private static final String PARENT_PROCESS_KEY = "shipmentOrchestration";
  private static final String CHILD_PROCESS_KEY = "carrierDispatch";

  @Autowired RuntimeService runtimeService;
  @Autowired HistoryService historyService;
  @Autowired ManagementService managementService;
  @Autowired ProcessTestHarness harness;

  @Test
  void warehouseConfirmsPickupBeforeTimeout_dispatchesDirectly() {
    final ProcessInstance parent = startShipment(3);
    assertPackedExactly(parent.getId(), 3);

    final HistoricProcessInstance child =
        harness.awaitCallActivityChild(parent.getId(), CHILD_PROCESS_KEY, Duration.ofSeconds(15));

    final Task pickupTask =
        harness.awaitTaskForCandidateGroup(child.getId(), "warehouse", Duration.ofSeconds(15));
    assertThat(pickupTask.getName()).isEqualTo("Await Carrier Pickup");

    harness.completeSingleTask(child.getId(), "warehouse", Map.of("carrierStatus", "DISPATCHED"));

    harness.assertThat(child.getId()).hasEndedAt("shipmentDispatchedEndEvent");
    harness.assertThat(parent.getId()).hasEndedAt("shipmentCompletedEndEvent");
  }

  @Test
  void warehouseMissesTimeout_escalatesToBackupCarrierAutomatically() {
    final ProcessInstance parent = startShipment(2);
    assertPackedExactly(parent.getId(), 2);

    final HistoricProcessInstance child =
        harness.awaitCallActivityChild(parent.getId(), CHILD_PROCESS_KEY, Duration.ofSeconds(15));

    // Deliberately never completes "Await Carrier Pickup": makes the boundary timer immediately
    // executable so the async executor picks it up on its own, proving the escalation branch
    // drives the call activity to completion without any manual task interaction.
    final Job timerJob =
        managementService.createTimerJobQuery().processInstanceId(child.getId()).singleResult();
    assertThat(timerJob).as("carrierPickupTimeoutBoundary timer job").isNotNull();
    managementService.moveTimerToExecutableJob(timerJob.getId());

    harness.awaitEnded(child.getId(), Duration.ofSeconds(15));
    harness.assertThat(child.getId()).hasEndedAt("shipmentEscalatedEndEvent");

    harness.awaitEnded(parent.getId(), Duration.ofSeconds(15));
    harness.assertThat(parent.getId()).hasEndedAt("shipmentCompletedEndEvent");
  }

  private ProcessInstance startShipment(int itemCount) {
    final String orderId = UUID.randomUUID().toString();
    return runtimeService.startProcessInstanceByKey(
        PARENT_PROCESS_KEY, Map.of("orderId", orderId, "itemCount", itemCount));
  }

  private void assertPackedExactly(String parentProcessInstanceId, int expectedItemCount) {
    final long packedInstances =
        historyService
            .createHistoricActivityInstanceQuery()
            .processInstanceId(parentProcessInstanceId)
            .activityId("packLineItemTask")
            .finished()
            .count();
    assertThat(packedInstances).isEqualTo(expectedItemCount);
  }
}
