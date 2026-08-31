package dev.portfolio.vendorsim;

/**
 * How the simulated vendor should behave for a given session. Chosen by prefixing the subject
 * reference, so a caller can drive any scenario without an admin API.
 */
public enum Behaviour {
  /** Callback fires, result is APPROVED. */
  APPROVE,
  /** Callback fires, result is DECLINED. */
  DECLINE,
  /** Callback fires; first result read is INCONCLUSIVE, subsequent reads are APPROVED. */
  FLIP,
  /** No callback is ever sent. The result endpoint still works - this is the lost-callback case. */
  SILENT,
  /** Result endpoint fails a few times before answering - exercises retry and dead-lettering. */
  FLAKY,
  /** Result endpoint sleeps past the client read timeout. */
  SLOW,
  /** Result endpoint reports no such reference. */
  UNKNOWN;

  /**
   * Maps a subject reference to a behaviour. {@code decline-alice} declines, {@code silent-bob} never
   * calls back, anything unprefixed approves.
   */
  static Behaviour forSubject(String subjectRef) {
    if (subjectRef == null) {
      return APPROVE;
    }
    String s = subjectRef.toLowerCase();
    for (Behaviour b : values()) {
      if (s.startsWith(b.name().toLowerCase() + "-")) {
        return b;
      }
    }
    return APPROVE;
  }
}
