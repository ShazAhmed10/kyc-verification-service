package dev.portfolio.verifyflow.reconcile;

import java.util.UUID;

/**
 * In-process signal that a reconciliation should be queued once the current transaction commits.
 *
 * <p>Published inside the transaction, consumed after it. See {@link ReconcileDispatcher} for why the
 * distinction matters.
 */
public record ReconcileRequested(UUID verificationId, String cause) {}
