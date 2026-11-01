package com.no5.lumina.control;

/** Stable, parcel-level contract for signed clients. Parcelable/AIDL generation is intentionally avoided. */
public final class KnobLeaseProtocol {
  private KnobLeaseProtocol() {}
  public static final String DESCRIPTOR = "com.no5.lumina.control.IKnobLeaseV1";
  public static final int VERSION = 1;
  public static final int REQUEST_LEASE = android.os.IBinder.FIRST_CALL_TRANSACTION;
  public static final int RELEASE_LEASE = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;
  public static final int UPDATE_VISIBLE_SESSION = android.os.IBinder.FIRST_CALL_TRANSACTION + 2;
  public static final int INPUT_CALLBACK = android.os.IBinder.FIRST_CALL_TRANSACTION + 3;
  public static final int REQUEST_GATEWAY_SESSION = android.os.IBinder.FIRST_CALL_TRANSACTION + 4;
  public static final int PROVISION_GATEWAY = android.os.IBinder.FIRST_CALL_TRANSACTION + 5;
  /** Same session fields plus one optional IPv4 fallback; legacy transaction remains unchanged. */
  public static final int REQUEST_GATEWAY_SESSION_WITH_ROUTE = android.os.IBinder.FIRST_CALL_TRANSACTION + 6;
  public static final int RESULT_ACCEPTED = 1, RESULT_REJECTED = 2, RESULT_UNAVAILABLE = 3;
  /** Descriptor implemented by an app-owned callback Binder passed to REQUEST_LEASE. */
  public static final String INPUT_CALLBACK_DESCRIPTOR = "com.no5.lumina.control.IKnobInputCallbackV1";
  public static final int DELIVER_INPUT = android.os.IBinder.FIRST_CALL_TRANSACTION;
  /** Additive callback extension: request is the same; reply is result, commandId (long), reason code (int). */
  public static final int READ_COMMAND_RESULT = android.os.IBinder.FIRST_CALL_TRANSACTION+1;
  public static final int DELIVER_BUFFERED_INPUT = android.os.IBinder.FIRST_CALL_TRANSACTION+2;
  public static final int ADMISSION_QUEUED=1, ADMISSION_REJECTED=2, ADMISSION_STALE=3, ADMISSION_UNSUPPORTED=4, ADMISSION_COMPLETION_UNKNOWN=5;
  /** Apps renew an admitted lease before this 2-second hard deadline. */
  public static final long MAX_LEASE_MS = 2000L;
  /** Bit positions in REQUEST_LEASE's requested-input mask. Navigation has no bit and is never leaseable. */
  public static final int MASK_LEFT_CW=1 << 0, MASK_LEFT_CCW=1 << 1, MASK_LEFT_PUSH=1 << 2, MASK_LEFT_LONG=1 << 3,
      MASK_RIGHT_CW=1 << 4, MASK_RIGHT_CCW=1 << 5, MASK_RIGHT_PUSH=1 << 6, MASK_RIGHT_LONG=1 << 7,
      ALL_INPUTS=MASK_LEFT_CW|MASK_LEFT_CCW|MASK_LEFT_PUSH|MASK_LEFT_LONG|MASK_RIGHT_CW|MASK_RIGHT_CCW|MASK_RIGHT_PUSH|MASK_RIGHT_LONG;
  /** REQUEST_GATEWAY_SESSION writes requested role; accepted response is origin, CA PEM, N5T2 token, keyId, issuedAtMs, expiresAtMs. */
  /** PROVISION writes origin, CA PEM and the one-time legacy bootstrap key. */
  public static final int INPUT_LEFT_CW=1, INPUT_LEFT_CCW=2, INPUT_LEFT_PUSH=3, INPUT_LEFT_LONG=4,
      INPUT_RIGHT_CW=5, INPUT_RIGHT_CCW=6, INPUT_RIGHT_PUSH=7, INPUT_RIGHT_LONG=8;
  /** Callback request: session, lease ID, physical sequence, physical uptime-ms, input code. Reply: result, stable reason. */
}
