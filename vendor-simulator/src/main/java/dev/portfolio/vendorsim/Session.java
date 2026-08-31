package dev.portfolio.vendorsim;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory record of one simulated verification session. */
class Session {
  final String vendorRef;
  final String subjectRef;
  final Behaviour behaviour;
  final Instant createdAt;
  final AtomicInteger resultReads = new AtomicInteger();

  Session(String vendorRef, String subjectRef, Behaviour behaviour, Instant createdAt) {
    this.vendorRef = vendorRef;
    this.subjectRef = subjectRef;
    this.behaviour = behaviour;
    this.createdAt = createdAt;
  }
}
