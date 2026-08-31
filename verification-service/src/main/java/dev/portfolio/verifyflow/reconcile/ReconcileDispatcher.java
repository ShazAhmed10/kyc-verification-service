package dev.portfolio.verifyflow.reconcile;

import dev.portfolio.verifyflow.config.VerifyFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Puts reconciliation tasks on the queue - but only after the transaction that asked for one has
 * actually committed.
 *
 * <p>This is the whole reason {@code AFTER_COMMIT} is here rather than a plain {@code @EventListener}
 * or a direct {@code rabbitTemplate.send} in the service method. The database transaction and the
 * broker are two separate systems with no shared transaction. If the message is published inline and
 * the transaction then rolls back, the message still exists: the consumer starts, reads the row it was
 * told about, and either finds it absent or finds it in the pre-transaction state. Either way the rest
 * of the system now believes something that never happened, and the failure surfaces somewhere else
 * entirely, which makes it expensive to diagnose.
 *
 * <p>Publishing after commit inverts the failure: if the broker is unreachable at that moment the
 * message is lost while the database change stands, which leaves a request that is correct but not yet
 * reconciled. That is recoverable - the expiry sweeper and a manual retrigger both pick it up - and it
 * is strictly the better direction to fail in.
 *
 * <p>The remaining gap, stated plainly because an interviewer will ask: this is not an outbox. A true
 * outbox would write the intent to a table in the same transaction and have a separate poller drain
 * it, which removes the lost-message window entirely. That is the next thing this design would need
 * to be production-grade.
 */
@Component
public class ReconcileDispatcher {

  private static final Logger log = LoggerFactory.getLogger(ReconcileDispatcher.class);

  private final RabbitTemplate rabbit;
  private final VerifyFlowProperties.Reconcile cfg;

  public ReconcileDispatcher(RabbitTemplate rabbit, VerifyFlowProperties props) {
    this.rabbit = rabbit;
    this.cfg = props.reconcile();
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onReconcileRequested(ReconcileRequested event) {
    ReconcileTask task = new ReconcileTask(event.verificationId(), event.cause());
    try {
      rabbit.convertAndSend(cfg.exchange(), cfg.routingKey(), task);
      log.info("Queued reconciliation for {} (cause={})", event.verificationId(), event.cause());
    } catch (Exception e) {
      // Swallowed on purpose. The state change is already committed and is correct; failing here
      // must not propagate back into the request that caused it. The sweeper is the safety net.
      log.error(
          "Failed to queue reconciliation for {} - request is committed but unreconciled, "
              + "the expiry sweeper will surface it",
          event.verificationId(),
          e);
    }
  }
}
