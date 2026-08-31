package dev.portfolio.verifyflow.reconcile;

import dev.portfolio.verifyflow.vendor.VendorCallFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The queue consumer - the stand-in for a managed function in this prototype.
 *
 * <p>It is intentionally thin. Everything interesting is in {@link ReconciliationService}, which is a
 * plain transactional bean and therefore testable without a broker. The consumer's only jobs are to
 * accept a delivery and to decide what a failure means.
 *
 * <p>A {@link VendorCallFailedException} is rethrown so the container's retry policy applies and, once
 * exhausted, the message dead-letters. Anything else is also rethrown - silently acking a message we
 * failed to process would strand the verification with nothing pointing at it.
 */
@Component
public class ReconcileConsumer {

  private static final Logger log = LoggerFactory.getLogger(ReconcileConsumer.class);

  private final ReconciliationService reconciliation;

  public ReconcileConsumer(ReconciliationService reconciliation) {
    this.reconciliation = reconciliation;
  }

  @RabbitListener(queues = "${verifyflow.reconcile.queue}")
  public void onTask(ReconcileTask task) {
    log.debug("Reconciling {} (cause={})", task.verificationId(), task.cause());
    ReconciliationService.ReconcileOutcome outcome = reconciliation.reconcile(task.verificationId());
    log.info("Reconciled {} -> {}", task.verificationId(), outcome);
  }
}
