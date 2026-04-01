package micropolisj.ai;

import micropolisj.engine.*;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Listens to game events and collects them for the AI assistant.
 * Detects critical events that should trigger an immediate AI consultation.
 */
public class AIGameListener implements CityListener {

    private final ConcurrentLinkedQueue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean hasCriticalEvent = false;
    private volatile boolean censusOccurred = false;
    private volatile boolean evaluationOccurred = false;
    private volatile boolean demandChanged = false;

    @Override
    public void cityMessage(MicropolisMessage message, CityLocation loc) {
        String locStr = loc != null ? " at (" + loc.getX() + "," + loc.getY() + ")" : "";
        messageQueue.add(message.name() + locStr);

        switch (message) {
            case FIRE_REPORT:
            case MELTDOWN_REPORT:
            case FLOOD_REPORT:
            case EARTHQUAKE_REPORT:
            case MONSTER_REPORT:
            case TORNADO_REPORT:
            case BROWNOUTS_REPORT:
            case OUT_OF_FUNDS_REPORT:
            case BLACKOUTS:
                hasCriticalEvent = true;
                break;
            default:
                break;
        }
    }

    @Override
    public void citySound(Sound sound, CityLocation loc) {
        // not forwarded to AI
    }

    @Override
    public void censusChanged() {
        censusOccurred = true;
    }

    @Override
    public void demandChanged() {
        demandChanged = true;
    }

    @Override
    public void evaluationChanged() {
        evaluationOccurred = true;
    }

    @Override
    public void fundsChanged() {
        // tracked via state observer
    }

    @Override
    public void optionsChanged() {
        // tracked via state observer
    }

    /**
     * Drain all collected messages since last call.
     */
    public String drainMessages() {
        StringBuilder sb = new StringBuilder();
        String msg;
        while ((msg = messageQueue.poll()) != null) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(msg);
        }
        return sb.toString();
    }

    public boolean hasCriticalEvent() {
        return hasCriticalEvent;
    }

    public void clearCriticalEvent() {
        hasCriticalEvent = false;
    }

    public boolean hasCensusOccurred() {
        boolean v = censusOccurred;
        censusOccurred = false;
        return v;
    }

    public boolean hasEvaluationOccurred() {
        boolean v = evaluationOccurred;
        evaluationOccurred = false;
        return v;
    }

    public boolean hasDemandChanged() {
        boolean v = demandChanged;
        demandChanged = false;
        return v;
    }

    public boolean hasAnyPendingEvent() {
        return !messageQueue.isEmpty() || hasCriticalEvent || censusOccurred;
    }
}
