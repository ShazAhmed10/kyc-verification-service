package dev.portfolio.verifyflow.reconcile;

import java.util.UUID;

/**
 * What travels on the queue: an identifier and nothing else.
 *
 * <p>Deliberately not the verification's state, the vendor reference, or the callback payload. The
 * consumer re-reads everything it needs from the database when the message is delivered, so a message
 * that sat in the queue for ten minutes - or is being redelivered after a failure - is evaluated
 * against the request as it is now, not as it was when the message was written. A frozen payload would
 * let a retry act on a state that has since moved on.
 *
 * <p>The cost is an extra read per delivery. That is the right trade here: correctness under
 * redelivery matters more than saving a primary-key lookup.
 */
public record ReconcileTask(UUID verificationId, String cause) {}
