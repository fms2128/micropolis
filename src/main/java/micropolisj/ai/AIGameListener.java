package micropolisj.ai;

import micropolisj.engine.*;

import java.util.*;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Listens to ALL game events and logs them through the ActivityLogger.
 * Acts as a full event bus: messages, financial changes, census,
 * demand shifts, evaluation updates, and sounds.
 */
public class AIGameListener implements CityListener {

    private static final int RECENT_HISTORY_SIZE = 30;

    private final ConcurrentLinkedQueue<MessageEntry> messageQueue = new ConcurrentLinkedQueue<>();
    private final LinkedList<String> recentHistory = new LinkedList<>();
    private final LinkedList<String> recentMessageIds = new LinkedList<>();
    private int messageIdCounter = 0;
    private volatile boolean hasCriticalEvent = false;
    private volatile boolean censusOccurred = false;
    private volatile boolean evaluationOccurred = false;
    private volatile boolean demandChanged = false;

    private ActivityLogger activityLogger;
    private Micropolis engine;

    private volatile int lastFunds = -1;
    private volatile int lastResValve = 0;
    private volatile int lastComValve = 0;
    private volatile int lastIndValve = 0;
    private volatile int lastScore = -1;
    private volatile long lastFundsLogTime = 0;
    private volatile int fundsAtLastLog = -1;
    private static final long FUNDS_LOG_DEBOUNCE_MS = 500;

    private static class MessageEntry {
        final MicropolisMessage message;
        final CityLocation location;

        MessageEntry(MicropolisMessage message, CityLocation location) {
            this.message = message;
            this.location = location;
        }
    }

    public void setActivityLogger(ActivityLogger logger) {
        this.activityLogger = logger;
    }

    public void setEngine(Micropolis engine) {
        this.engine = engine;
        if (engine != null) {
            this.lastFunds = engine.getBudget().getTotalFunds();
            this.lastResValve = engine.getResValve();
            this.lastComValve = engine.getComValve();
            this.lastIndValve = engine.getIndValve();
            this.lastScore = engine.getEvaluation().getCityScore();
        }
    }

    // ── CityListener callbacks ──────────────────────────────────────

    @Override
    public void cityMessage(MicropolisMessage message, CityLocation loc) {
        messageQueue.add(new MessageEntry(message, loc));

        String category;
        switch (message) {
            case FIRE_REPORT:
            case MELTDOWN_REPORT:
            case FLOOD_REPORT:
            case EARTHQUAKE_REPORT:
            case MONSTER_REPORT:
            case TORNADO_REPORT:
                category = "DISASTER";
                hasCriticalEvent = true;
                break;
            case BROWNOUTS_REPORT:
            case OUT_OF_FUNDS_REPORT:
            case BLACKOUTS:
                category = "CRITICAL";
                hasCriticalEvent = true;
                break;
            case POP_2K_REACHED:
            case POP_10K_REACHED:
            case POP_50K_REACHED:
            case POP_100K_REACHED:
            case POP_500K_REACHED:
                category = "MILESTONE";
                break;
            case NEED_RES: case NEED_COM: case NEED_IND:
            case NEED_ROADS: case NEED_POWER:
            case NEED_STADIUM: case NEED_SEAPORT: case NEED_AIRPORT:
            case NEED_FIRESTATION: case NEED_POLICE: case NEED_PARKS:
                category = "DEMAND";
                break;
            case INSUFFICIENT_FUNDS:
            case ROADS_NEED_FUNDING: case FIRE_NEED_FUNDING: case POLICE_NEED_FUNDING:
                category = "FINANCIAL";
                break;
            case HIGH_POLLUTION: case HIGH_CRIME: case HIGH_TRAFFIC:
            case HIGH_TAXES: case HIGH_UNEMPLOYMENT: case HEAVY_TRAFFIC_REPORT:
                category = "PROBLEM";
                break;
            default:
                category = "INFO";
                break;
        }

        if (activityLogger != null) {
            String locStr = loc != null ? " at (" + loc.getX() + "," + loc.getY() + ")" : "";
            activityLogger.logEvent(category, message.name() + locStr);
        }
    }

    @Override
    public void citySound(Sound sound, CityLocation loc) {
        if (activityLogger == null) return;
        String locStr = loc != null ? " at (" + loc.getX() + "," + loc.getY() + ")" : "";
        activityLogger.logEvent("SOUND", sound.name() + locStr);
    }

    @Override
    public void censusChanged() {
        censusOccurred = true;
        if (activityLogger != null && engine != null) {
            com.google.gson.JsonObject snapshot = new com.google.gson.JsonObject();
            snapshot.addProperty("population", engine.getCityPopulation());
            snapshot.addProperty("res_pop", engine.getResPop());
            snapshot.addProperty("com_pop", engine.getComPop());
            snapshot.addProperty("ind_pop", engine.getIndPop());
            snapshot.addProperty("res_zones", engine.getResZoneCount());
            snapshot.addProperty("com_zones", engine.getComZoneCount());
            snapshot.addProperty("ind_zones", engine.getIndZoneCount());
            snapshot.addProperty("powered_zones", engine.getPoweredZoneCount());
            snapshot.addProperty("unpowered_zones", engine.getUnpoweredZoneCount());
            snapshot.addProperty("funds", engine.getBudget().getTotalFunds());
            int year = 1900 + engine.getCityTime() / 48;
            int month = (engine.getCityTime() % 48) / 4 + 1;
            snapshot.addProperty("game_date", String.format("%d-%02d", year, month));
            activityLogger.logSnapshot("CENSUS", snapshot);
        }
    }

    @Override
    public void demandChanged() {
        demandChanged = true;
        if (activityLogger != null && engine != null) {
            int newRes = engine.getResValve();
            int newCom = engine.getComValve();
            int newInd = engine.getIndValve();

            com.google.gson.JsonObject snapshot = new com.google.gson.JsonObject();
            snapshot.addProperty("res_valve", newRes);
            snapshot.addProperty("com_valve", newCom);
            snapshot.addProperty("ind_valve", newInd);
            snapshot.addProperty("res_delta", newRes - lastResValve);
            snapshot.addProperty("com_delta", newCom - lastComValve);
            snapshot.addProperty("ind_delta", newInd - lastIndValve);
            snapshot.addProperty("res_capped", engine.isResCap());
            snapshot.addProperty("com_capped", engine.isComCap());
            snapshot.addProperty("ind_capped", engine.isIndCap());
            activityLogger.logSnapshot("DEMAND_CHANGE", snapshot);

            lastResValve = newRes;
            lastComValve = newCom;
            lastIndValve = newInd;
        }
    }

    @Override
    public void evaluationChanged() {
        evaluationOccurred = true;
        if (activityLogger != null && engine != null) {
            CityEval eval = engine.getEvaluation();
            com.google.gson.JsonObject snapshot = new com.google.gson.JsonObject();
            snapshot.addProperty("score", eval.getCityScore());
            snapshot.addProperty("delta_score", eval.getDeltaCityScore());
            snapshot.addProperty("population", eval.getCityPop());
            snapshot.addProperty("delta_population", eval.getDeltaCityPop());
            snapshot.addProperty("approval", eval.getCityYes());
            snapshot.addProperty("assessed_value", eval.getCityAssValue());

            int prevScore = lastScore;
            lastScore = eval.getCityScore();
            if (prevScore >= 0) {
                snapshot.addProperty("score_change_since_last", lastScore - prevScore);
            }

            com.google.gson.JsonArray problems = new com.google.gson.JsonArray();
            CityProblem[] order = eval.getProblemOrder();
            for (CityProblem p : order) {
                com.google.gson.JsonObject prob = new com.google.gson.JsonObject();
                prob.addProperty("problem", p.name());
                Integer votes = eval.getProblemVotes().get(p);
                prob.addProperty("severity", votes != null ? votes : 0);
                problems.add(prob);
            }
            snapshot.add("top_problems", problems);

            snapshot.addProperty("crime_avg", engine.getCrimeAverage());
            snapshot.addProperty("pollution_avg", engine.getPollutionAverage());
            snapshot.addProperty("land_value_avg", engine.getLandValueAverage());
            snapshot.addProperty("traffic_avg", engine.getTrafficAverage());
            snapshot.addProperty("road_effect", engine.getRoadEffect());
            snapshot.addProperty("police_effect", engine.getPoliceEffect());
            snapshot.addProperty("fire_effect", engine.getFireEffect());

            activityLogger.logSnapshot("EVALUATION", snapshot);
        }
    }

    @Override
    public void fundsChanged() {
        if (engine != null) {
            lastFunds = engine.getBudget().getTotalFunds();
        }
        if (activityLogger != null && engine != null) {
            long now = System.currentTimeMillis();
            if (now - lastFundsLogTime >= FUNDS_LOG_DEBOUNCE_MS) {
                int currentFunds = engine.getBudget().getTotalFunds();
                int delta = (fundsAtLastLog >= 0) ? currentFunds - fundsAtLastLog : 0;
                com.google.gson.JsonObject snapshot = new com.google.gson.JsonObject();
                snapshot.addProperty("funds", currentFunds);
                if (delta != 0) {
                    snapshot.addProperty("delta", delta);
                    snapshot.addProperty("source", delta > 0 ? "income" : "expense");
                }
                activityLogger.logSnapshot("FUNDS_CHANGE", snapshot);
                lastFundsLogTime = now;
                fundsAtLastLog = currentFunds;
            }
        }
    }

    @Override
    public void optionsChanged() {
        if (activityLogger != null && engine != null) {
            com.google.gson.JsonObject snapshot = new com.google.gson.JsonObject();
            snapshot.addProperty("auto_bulldoze", engine.isAutoBulldoze());
            snapshot.addProperty("auto_budget", engine.isAutoBudget());
            snapshot.addProperty("no_disasters", engine.isNoDisasters());
            snapshot.addProperty("speed", engine.getSimSpeed().name());
            snapshot.addProperty("tax_rate", engine.getCityTax());
            activityLogger.logSnapshot("OPTIONS_CHANGE", snapshot);
        }
    }

    // ── Message formatting for the AI agent ─────────────────────────

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

    public String drainStructuredMessages() {
        MessageEntry entry;
        while ((entry = messageQueue.poll()) != null) {
            String line = formatMessage(entry.message, entry.location);
            if (line != null) {
                String msgId = "m" + (++messageIdCounter);
                synchronized (recentHistory) {
                    recentHistory.addLast(line);
                    recentMessageIds.addLast(msgId);
                    while (recentHistory.size() > RECENT_HISTORY_SIZE) {
                        recentHistory.removeFirst();
                        recentMessageIds.removeFirst();
                    }
                }
            }
        }

        synchronized (recentHistory) {
            if (recentHistory.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            Iterator<String> idIter = recentMessageIds.iterator();
            for (String line : recentHistory) {
                String id = idIter.hasNext() ? idIter.next() : "m?";
                sb.append("- [").append(id).append("] ").append(line).append("\n");
            }
            return sb.toString();
        }
    }

    public List<String> getCurrentMessageIds() {
        synchronized (recentHistory) {
            return new ArrayList<>(recentMessageIds);
        }
    }

    private String formatMessage(MicropolisMessage msg, CityLocation loc) {
        String locStr = loc != null ? " at (" + loc.getX() + "," + loc.getY() + ")" : "";
        switch (msg) {
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

    // ── State queries ───────────────────────────────────────────────

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
