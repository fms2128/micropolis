package micropolisj.ai;

import micropolisj.engine.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Listens to game events and collects them for the AI assistant.
 * Detects critical events that should trigger an immediate AI consultation.
 */
public class AIGameListener implements CityListener {

    private final ConcurrentLinkedQueue<MessageEntry> messageQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean hasCriticalEvent = false;
    private volatile boolean censusOccurred = false;
    private volatile boolean evaluationOccurred = false;
    private volatile boolean demandChanged = false;

    private static class MessageEntry {
        final MicropolisMessage message;
        final CityLocation location;

        MessageEntry(MicropolisMessage message, CityLocation location) {
            this.message = message;
            this.location = location;
        }
    }

    @Override
    public void cityMessage(MicropolisMessage message, CityLocation loc) {
        messageQueue.add(new MessageEntry(message, loc));

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
     * Drain all collected messages since last call as raw strings (legacy).
     */
    public String drainMessages() {
        StringBuilder sb = new StringBuilder();
        MessageEntry entry;
        while ((entry = messageQueue.poll()) != null) {
            if (sb.length() > 0) sb.append("; ");
            String locStr = entry.location != null
                ? " at (" + entry.location.getX() + "," + entry.location.getY() + ")" : "";
            sb.append(entry.message.name()).append(locStr);
        }
        return sb.toString();
    }

    /**
     * Drain all collected messages as structured, categorized system updates.
     * Deduplicates repeated messages and translates enum names into
     * human-readable, actionable descriptions with priority categories.
     */
    public String drainStructuredMessages() {
        Map<MicropolisMessage, CityLocation> seen = new LinkedHashMap<>();
        MessageEntry entry;
        while ((entry = messageQueue.poll()) != null) {
            seen.putIfAbsent(entry.message, entry.location);
        }
        if (seen.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<MicropolisMessage, CityLocation> e : seen.entrySet()) {
            String line = formatMessage(e.getKey(), e.getValue());
            if (line != null) {
                sb.append("- ").append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String formatMessage(MicropolisMessage msg, CityLocation loc) {
        String locStr = loc != null ? " at (" + loc.getX() + "," + loc.getY() + ")" : "";
        switch (msg) {
            // Demand messages
            case NEED_RES:
                return "[DEMAND] City needs more residential zones - citizens want housing";
            case NEED_COM:
                return "[DEMAND] City needs more commercial zones - businesses want to open";
            case NEED_IND:
                return "[DEMAND] City needs more industrial zones - economy needs jobs";
            case NEED_ROADS:
                return "[DEMAND] City needs more roads - zones require road access to grow";
            case NEED_RAILS:
                return "[DEMAND] City needs rail connections";
            case NEED_POWER:
                return "[CRITICAL] Zones need power - connect them to a power plant via power lines or adjacent powered zones";
            case NEED_STADIUM:
                return "[DEMAND] Citizens want a stadium (population milestone)";
            case NEED_SEAPORT:
                return "[DEMAND] Industry needs a seaport for growth";
            case NEED_AIRPORT:
                return "[DEMAND] Commerce needs an airport for growth";
            case NEED_FIRESTATION:
                return "[DEMAND] City needs fire stations for safety coverage";
            case NEED_POLICE:
                return "[DEMAND] City needs police stations to reduce crime";
            case NEED_PARKS:
                return "[DEMAND] Citizens want parks - parks boost land value and happiness";

            // Problem messages
            case HIGH_POLLUTION:
                return "[PROBLEM] High pollution" + locStr + " - separate industrial from residential, add parks";
            case HIGH_CRIME:
                return "[PROBLEM] High crime" + locStr + " - build police stations nearby";
            case HIGH_TRAFFIC:
                return "[PROBLEM] High traffic - build more roads or use rail";
            case HIGH_TAXES:
                return "[PROBLEM] Citizens complain about high taxes - consider lowering the tax rate";
            case HIGH_UNEMPLOYMENT:
                return "[PROBLEM] High unemployment - build more industrial or commercial zones";
            case BLACKOUTS:
                return "[CRITICAL] Power blackouts occurring - connect unpowered zones to power grid immediately";
            case BROWNOUTS_REPORT:
                return "[CRITICAL] Power brownouts - power plant capacity may be insufficient, build another plant or check connections";

            // Financial messages
            case OUT_OF_FUNDS_REPORT:
                return "[CRITICAL] City is out of funds! Cut spending or raise taxes immediately";
            case INSUFFICIENT_FUNDS:
                return "[FINANCIAL] Insufficient funds for that action";
            case ROADS_NEED_FUNDING:
                return "[FINANCIAL] Roads are deteriorating - increase road funding percentage";
            case FIRE_NEED_FUNDING:
                return "[FINANCIAL] Fire department underfunded - increase fire funding";
            case POLICE_NEED_FUNDING:
                return "[FINANCIAL] Police department underfunded - increase police funding";

            // Disaster messages
            case FIRE_REPORT:
                return "[DISASTER] Fire reported" + locStr + " - build fire stations for coverage";
            case FLOOD_REPORT:
                return "[DISASTER] Flooding" + locStr;
            case EARTHQUAKE_REPORT:
                return "[DISASTER] Earthquake! Check for damage and rebuild";
            case TORNADO_REPORT:
                return "[DISASTER] Tornado" + locStr + " - check for damage";
            case MONSTER_REPORT:
                return "[DISASTER] Monster attack" + locStr;
            case MELTDOWN_REPORT:
                return "[DISASTER] Nuclear meltdown" + locStr + " - severe radiation damage!";
            case PLANECRASH_REPORT:
                return "[DISASTER] Plane crash" + locStr;
            case SHIPWRECK_REPORT:
                return "[DISASTER] Shipwreck" + locStr;
            case TRAIN_CRASH_REPORT:
                return "[DISASTER] Train crash" + locStr;
            case COPTER_CRASH_REPORT:
                return "[DISASTER] Helicopter crash" + locStr;
            case EXPLOSION_REPORT:
                return "[DISASTER] Explosion" + locStr;
            case FIREBOMBING_REPORT:
                return "[DISASTER] Firebombing" + locStr;
            case RIOTING_REPORT:
                return "[DISASTER] Rioting in the city - citizens are very unhappy";

            // Milestone messages
            case POP_2K_REACHED:
                return "[MILESTONE] Population reached 2,000 - city is now a Town!";
            case POP_10K_REACHED:
                return "[MILESTONE] Population reached 10,000 - city is now a City!";
            case POP_50K_REACHED:
                return "[MILESTONE] Population reached 50,000 - city is now a Capital!";
            case POP_100K_REACHED:
                return "[MILESTONE] Population reached 100,000 - city is now a Metropolis!";
            case POP_500K_REACHED:
                return "[MILESTONE] Population reached 500,000 - city is now a Megalopolis!";
            case HEAVY_TRAFFIC_REPORT:
                return "[PROBLEM] Heavy traffic reported" + locStr;

            default:
                return "[INFO] " + msg.name() + locStr;
        }
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
