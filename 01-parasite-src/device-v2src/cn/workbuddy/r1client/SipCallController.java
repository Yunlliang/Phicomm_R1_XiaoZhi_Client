package cn.workbuddy.r1client;

/**
 * SIP call lifecycle coordinator. Transport/media integration is intentionally
 * separate: a native PJSIP backend will report its callbacks through on*().
 *
 * All public methods are synchronized to avoid racing a SIP network callback
 * against Web control operations. No microphone is opened here.
 */
public final class SipCallController {
    public enum State { IDLE, INCOMING, OUTGOING, ACTIVE, ENDING }
    public interface Backend {
        void dial(String extension);
        void answer();
        void reject();
        void hangup();
    }
    public interface Listener {
        void onStateChanged(State state, String remote);
    }

    private Backend backend;
    private Listener listener;
    private State state = State.IDLE;
    private String remote = "";

    public synchronized void setBackend(Backend backend) { this.backend = backend; }
    public synchronized void setListener(Listener listener) { this.listener = listener; }
    public synchronized State state() { return state; }
    public synchronized String remote() { return remote; }

    private void change(State next, String peer) {
        state = next;
        remote = peer == null ? "" : peer;
        if (listener != null) listener.onStateChanged(state, remote);
    }

    private static String requireDialTarget(String target) {
        if (target == null || !target.matches("[0-9]{1,8}"))
            throw new IllegalArgumentException("Only local numeric SIP extensions supported");
        return target;
    }

    public synchronized void dial(String target) {
        if (state != State.IDLE || backend == null)
            throw new IllegalStateException("SIP line busy or unavailable");
        target = requireDialTarget(target);
        change(State.OUTGOING, target);
        try { backend.dial(target); }
        catch (RuntimeException e) { change(State.IDLE, ""); throw e; }
    }

    public synchronized void onIncoming(String caller) {
        if (state != State.IDLE) {
            // Backend must reject unexpected second calls; never overwrite active media.
            return;
        }
        change(State.INCOMING, caller);
    }

    public synchronized void answer() {
        if (state != State.INCOMING || backend == null)
            throw new IllegalStateException("No incoming call");
        backend.answer();
    }

    public synchronized void reject() {
        if (state != State.INCOMING || backend == null)
            throw new IllegalStateException("No incoming call");
        change(State.ENDING, remote);
        backend.reject();
    }

    public synchronized void hangup() {
        if (state == State.IDLE || state == State.ENDING || backend == null)
            throw new IllegalStateException("No call to hang up");
        change(State.ENDING, remote);
        backend.hangup();
    }

    /** Called only after remote acceptance and the media pipeline is ready. */
    public synchronized void onConnected() {
        if (state == State.OUTGOING || state == State.INCOMING)
            change(State.ACTIVE, remote);
    }

    /** Called for BYE, reject, failure and timeout; idempotent. */
    public synchronized void onDisconnected() {
        if (state != State.IDLE) change(State.IDLE, "");
    }
}
