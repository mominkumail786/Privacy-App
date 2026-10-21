/// Kinds of entries in the event log. Same names as the Kotlin `EventType`.
enum EventType {
  protectionStarted('PROTECTION_STARTED'),
  protectionStopped('PROTECTION_STOPPED'),
  otherPersonAlert('OTHER_PERSON_ALERT'),
  uncertainAlert('UNCERTAIN_ALERT'),
  cameraBusy('CAMERA_BUSY'),
  cameraResumed('CAMERA_RESUMED'),
  analysisFailing('ANALYSIS_FAILING'),
  analysisRecovered('ANALYSIS_RECOVERED'),
  profileInvalid('PROFILE_INVALID'),

  /// Enrollment finished; the stored number is the time it took, in seconds.
  enrollmentDone('ENROLLMENT_DONE');

  const EventType(this.nativeName);
  final String nativeName;

  static EventType? parse(Object? value) {
    for (final t in EventType.values) {
      if (t.nativeName == value) return t;
    }
    return null;
  }
}

/// One line of the event log. Contains no pictures and no face data: only time, kind and a number.
class GuardEvent {
  const GuardEvent(this.timeMillis, this.type, this.score);

  final int timeMillis;
  final EventType type;
  final double? score;

  /// Returns null for an entry this version does not know (never crashes the log screen).
  static GuardEvent? fromMap(Map<Object?, Object?> m) {
    final type = EventType.parse(m['type']);
    final time = (m['time'] as num?)?.toInt();
    if (type == null || time == null) return null;
    return GuardEvent(time, type, (m['score'] as num?)?.toDouble());
  }
}
