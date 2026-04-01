package micropolisj.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import micropolisj.engine.*;

/**
 * Observes the Micropolis engine and produces structured JSON
 * summaries for the AI assistant. Compresses the 120x100 tile map
 * into 12x10 sectors so that the context stays token-efficient.
 */
public class GameStateObserver {

    private static final int SECTOR_W = 10;
    private static final int SECTOR_H = 10;
    private static final String[] CITY_CLASS_NAMES = {
        "Village", "Town", "City", "Capital", "Metropolis", "Megalopolis"
    };

    private final Micropolis engine;

    private int prevScore = -1;
    private int prevPopulation = -1;
    private int prevFunds = -1;

    public GameStateObserver(Micropolis engine) {
        this.engine = engine;
    }

    public JsonObject getFullState() {
        JsonObject state = new JsonObject();
        state.add("overview", getOverview());
        state.add("budget", getBudgetInfo());
        state.add("evaluation", getEvaluation());
        state.add("infrastructure", getInfrastructure());
        state.add("demand", getDemand());
        state.add("averages", getAverages());
        state.add("map_sectors", getMapSectors());
        return state;
    }

    public String getFullStateText() {
        return getFullState().toString();
    }

    /**
     * Returns a minimal summary for the turn prompt - just the essentials
     * so the AI knows what to focus on without wasting tokens.
     */
    public JsonObject getMinimalSummary() {
        int curScore = engine.getEvaluation().getCityScore();
        int curPop = engine.getCityPopulation();
        int curFunds = engine.getBudget().getTotalFunds();

        JsonObject s = new JsonObject();
        s.addProperty("population", curPop);
        s.addProperty("funds", curFunds);
        s.addProperty("score", curScore);
        int year = 1900 + engine.getCityTime() / 48;
        int month = (engine.getCityTime() % 48) / 4 + 1;
        s.addProperty("date", String.format("%d-%02d", year, month));
        s.addProperty("res_demand", engine.getResValve());
        s.addProperty("com_demand", engine.getComValve());
        s.addProperty("ind_demand", engine.getIndValve());
        s.addProperty("powered_zones", engine.getPoweredZoneCount());
        s.addProperty("unpowered_zones", engine.getUnpoweredZoneCount());

        if (prevScore >= 0) {
            int deltaScore = curScore - prevScore;
            int deltaPop = curPop - prevPopulation;
            int deltaFunds = curFunds - prevFunds;

            s.addProperty("delta_score", deltaScore);
            s.addProperty("delta_population", deltaPop);
            s.addProperty("delta_funds", deltaFunds);

            double reward = (deltaScore * 2.0) + (deltaPop / 100.0) + (deltaFunds / 500.0);
            s.addProperty("reward", Math.round(reward * 100.0) / 100.0);
        }

        BudgetNumbers bn = engine.generateBudget();
        int monthlyExpenses = bn.getRoadFunded() + bn.getFireFunded() + bn.getPoliceFunded();
        int monthlyIncome = bn.getTaxIncome();
        s.addProperty("monthly_income", monthlyIncome);
        s.addProperty("monthly_expenses", monthlyExpenses);
        int netCashflow = monthlyIncome - monthlyExpenses;
        s.addProperty("net_cashflow", netCashflow);
        if (monthlyExpenses > 0) {
            s.addProperty("runway_months", curFunds / Math.max(1, monthlyExpenses));
        }

        prevScore = curScore;
        prevPopulation = curPop;
        prevFunds = curFunds;

        CityEval eval = engine.getEvaluation();
        CityProblem[] problems = eval.getProblemOrder();
        if (problems.length > 0) {
            JsonArray topProblems = new JsonArray();
            for (int i = 0; i < Math.min(3, problems.length); i++) {
                Integer votes = eval.getProblemVotes().get(problems[i]);
                if (votes != null && votes > 0) {
                    topProblems.add(problems[i].name() + " (" + votes + "%)");
                }
            }
            if (topProblems.size() > 0) s.add("top_problems", topProblems);
        }
        return s;
    }

    public JsonObject getOverview() {
        JsonObject o = new JsonObject();
        o.addProperty("population", engine.getCityPopulation());
        o.addProperty("city_time", engine.getCityTime());
        int year = 1900 + engine.getCityTime() / 48;
        int month = (engine.getCityTime() % 48) / 4 + 1;
        o.addProperty("date", String.format("%d-%02d", year, month));
        o.addProperty("funds", engine.getBudget().getTotalFunds());
        o.addProperty("score", engine.getEvaluation().getCityScore());
        o.addProperty("delta_score", engine.getEvaluation().getDeltaCityScore());
        int cc = engine.getEvaluation().getCityClass();
        o.addProperty("city_class", cc < CITY_CLASS_NAMES.length ? CITY_CLASS_NAMES[cc] : "Unknown");
        o.addProperty("approval", engine.getEvaluation().getCityYes());
        o.addProperty("game_level", engine.getGameLevel());
        o.addProperty("speed", engine.getSimSpeed().name());
        o.addProperty("map_width", engine.getWidth());
        o.addProperty("map_height", engine.getHeight());
        return o;
    }

    public JsonObject getBudgetInfo() {
        JsonObject b = new JsonObject();
        b.addProperty("total_funds", engine.getBudget().getTotalFunds());
        b.addProperty("tax_rate", engine.getCityTax());

        BudgetNumbers bn = engine.generateBudget();
        b.addProperty("tax_income", bn.getTaxIncome());
        b.addProperty("road_request", bn.getRoadRequest());
        b.addProperty("road_funded", bn.getRoadFunded());
        b.addProperty("road_percent", Math.round(engine.getRoadPercent() * 100));
        b.addProperty("fire_request", bn.getFireRequest());
        b.addProperty("fire_funded", bn.getFireFunded());
        b.addProperty("fire_percent", Math.round(engine.getFirePercent() * 100));
        b.addProperty("police_request", bn.getPoliceRequest());
        b.addProperty("police_funded", bn.getPoliceFunded());
        b.addProperty("police_percent", Math.round(engine.getPolicePercent() * 100));
        return b;
    }

    public JsonObject getEvaluation() {
        CityEval eval = engine.getEvaluation();
        JsonObject e = new JsonObject();
        e.addProperty("score", eval.getCityScore());
        e.addProperty("delta_score", eval.getDeltaCityScore());
        e.addProperty("population", eval.getCityPop());
        e.addProperty("delta_population", eval.getDeltaCityPop());
        e.addProperty("approval", eval.getCityYes());
        e.addProperty("assessed_value", eval.getCityAssValue());

        JsonArray problems = new JsonArray();
        CityProblem[] order = eval.getProblemOrder();
        for (CityProblem p : order) {
            JsonObject prob = new JsonObject();
            prob.addProperty("problem", p.name());
            Integer votes = eval.getProblemVotes().get(p);
            prob.addProperty("severity", votes != null ? votes : 0);
            problems.add(prob);
        }
        e.add("top_problems", problems);
        return e;
    }

    public JsonObject getInfrastructure() {
        JsonObject inf = new JsonObject();
        inf.addProperty("res_zones", engine.getResZoneCount());
        inf.addProperty("com_zones", engine.getComZoneCount());
        inf.addProperty("ind_zones", engine.getIndZoneCount());
        inf.addProperty("powered_zones", engine.getPoweredZoneCount());
        inf.addProperty("unpowered_zones", engine.getUnpoweredZoneCount());
        inf.addProperty("road_total", engine.getRoadTotal());
        inf.addProperty("rail_total", engine.getRailTotal());
        inf.addProperty("police_stations", engine.getPoliceCount());
        inf.addProperty("fire_stations", engine.getFireStationCount());
        inf.addProperty("hospitals", engine.getHospitalCount());
        inf.addProperty("churches", engine.getChurchCount());
        inf.addProperty("stadiums", engine.getStadiumCount());
        inf.addProperty("seaports", engine.getSeaportCount());
        inf.addProperty("airports", engine.getAirportCount());
        inf.addProperty("coal_plants", engine.getCoalCount());
        inf.addProperty("nuclear_plants", engine.getNuclearCount());
        inf.addProperty("need_hospital", engine.getNeedHospital());
        inf.addProperty("need_church", engine.getNeedChurch());
        return inf;
    }

    public JsonObject getDemand() {
        JsonObject d = new JsonObject();
        d.addProperty("res_valve", engine.getResValve());
        d.addProperty("com_valve", engine.getComValve());
        d.addProperty("ind_valve", engine.getIndValve());
        d.addProperty("res_capped", engine.isResCap());
        d.addProperty("com_capped", engine.isComCap());
        d.addProperty("ind_capped", engine.isIndCap());
        d.addProperty("res_pop", engine.getResPop());
        d.addProperty("com_pop", engine.getComPop());
        d.addProperty("ind_pop", engine.getIndPop());
        return d;
    }

    public JsonObject getAverages() {
        JsonObject a = new JsonObject();
        a.addProperty("crime", engine.getCrimeAverage());
        a.addProperty("pollution", engine.getPollutionAverage());
        a.addProperty("land_value", engine.getLandValueAverage());
        a.addProperty("traffic", engine.getTrafficAverage());
        a.addProperty("road_effect", engine.getRoadEffect());
        a.addProperty("police_effect", engine.getPoliceEffect());
        a.addProperty("fire_effect", engine.getFireEffect());
        return a;
    }

    /**
     * Compresses the 120x100 map into 12x10 sectors, each summarizing
     * the dominant tile types and key metrics.
     */
    public JsonArray getMapSectors() {
        int w = engine.getWidth();
        int h = engine.getHeight();
        int sectorsX = (w + SECTOR_W - 1) / SECTOR_W;
        int sectorsY = (h + SECTOR_H - 1) / SECTOR_H;

        JsonArray sectors = new JsonArray();
        for (int sy = 0; sy < sectorsY; sy++) {
            for (int sx = 0; sx < sectorsX; sx++) {
                JsonObject sector = analyzeSector(
                    sx * SECTOR_W, sy * SECTOR_H,
                    Math.min((sx + 1) * SECTOR_W, w),
                    Math.min((sy + 1) * SECTOR_H, h)
                );
                sector.addProperty("sx", sx);
                sector.addProperty("sy", sy);
                sectors.add(sector);
            }
        }
        return sectors;
    }

    private JsonObject analyzeSector(int x0, int y0, int x1, int y1) {
        int water = 0, dirt = 0, road = 0, residential = 0;
        int commercial = 0, industrial = 0, trees = 0, other = 0;
        int powered = 0, unpowered = 0;

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                char tile = engine.getTile(x, y);
                if (tile >= 240 && tile < 423) residential++;
                else if (tile >= 423 && tile < 612) commercial++;
                else if (tile >= 612 && tile < 693) industrial++;
                else if (tile >= 64 && tile < 208) road++;
                else if (tile >= 21 && tile <= 43) trees++;
                else if (tile >= 2 && tile <= 20) water++;
                else if (tile == 0) dirt++;
                else other++;

                if (engine.isTilePowered(x, y)) powered++;
                else if (tile >= 240) unpowered++;
            }
        }

        int total = (x1 - x0) * (y1 - y0);
        JsonObject s = new JsonObject();

        String dominant;
        int max = Math.max(water, Math.max(dirt, Math.max(road,
            Math.max(residential, Math.max(commercial,
                Math.max(industrial, Math.max(trees, other)))))));
        if (max == water) dominant = "water";
        else if (max == residential) dominant = "residential";
        else if (max == commercial) dominant = "commercial";
        else if (max == industrial) dominant = "industrial";
        else if (max == road) dominant = "road";
        else if (max == trees) dominant = "trees";
        else if (max == dirt) dominant = "empty";
        else dominant = "mixed";

        s.addProperty("dominant", dominant);
        s.addProperty("developed_pct", Math.round((residential + commercial + industrial + road) * 100.0 / total));
        s.addProperty("empty_pct", Math.round((dirt + trees) * 100.0 / total));
        if (unpowered > 0) s.addProperty("unpowered_zones", unpowered);
        return s;
    }

    public JsonObject inspectArea(int cx, int cy, int radius) {
        JsonObject area = new JsonObject();
        area.addProperty("center_x", cx);
        area.addProperty("center_y", cy);
        area.addProperty("radius", radius);

        int x0 = Math.max(0, cx - radius);
        int y0 = Math.max(0, cy - radius);
        int x1 = Math.min(engine.getWidth() - 1, cx + radius);
        int y1 = Math.min(engine.getHeight() - 1, cy + radius);

        JsonArray emptyTiles = new JsonArray();
        JsonArray occupiedTiles = new JsonArray();
        int emptyCount = 0;

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                char tile = engine.getTile(x, y);
                if (tile == 0) {
                    emptyCount++;
                    JsonObject t = new JsonObject();
                    t.addProperty("x", x);
                    t.addProperty("y", y);
                    emptyTiles.add(t);
                } else {
                    JsonObject t = new JsonObject();
                    t.addProperty("x", x);
                    t.addProperty("y", y);
                    t.addProperty("tile", (int) tile);
                    t.addProperty("type", classifyTile(tile));
                    occupiedTiles.add(t);
                }
            }
        }
        area.addProperty("empty_tile_count", emptyCount);
        area.addProperty("occupied_tile_count", occupiedTiles.size());
        area.add("empty_tiles", emptyTiles);
        area.add("occupied_tiles", occupiedTiles);
        return area;
    }

    /**
     * Scans the map to find a contiguous rectangular area of empty (dirt) tiles
     * large enough for a building of the given size. Returns the top-left
     * corner of the first suitable area found, preferring locations near roads.
     */
    public JsonObject findEmptyArea(int width, int height) {
        int bestX = -1, bestY = -1;
        int bestRoadDist = Integer.MAX_VALUE;

        for (int y = 0; y <= engine.getHeight() - height; y++) {
            for (int x = 0; x <= engine.getWidth() - width; x++) {
                if (isAreaEmpty(x, y, width, height)) {
                    if (bestX < 0) {
                        bestX = x;
                        bestY = y;
                    }
                    int roadDist = distanceToNearestRoad(x, y, width, height);
                    if (roadDist < bestRoadDist) {
                        bestRoadDist = roadDist;
                        bestX = x;
                        bestY = y;
                        if (roadDist <= 1) {
                            return makeFoundResult(bestX, bestY, width, height, bestRoadDist);
                        }
                    }
                }
            }
        }

        if (bestX >= 0) {
            return makeFoundResult(bestX, bestY, width, height, bestRoadDist);
        }
        JsonObject result = new JsonObject();
        result.addProperty("found", false);
        result.addProperty("message", "No empty " + width + "x" + height + " area found on the map. All land is occupied by water or non-bulldozable structures.");
        return result;
    }

    private JsonObject makeFoundResult(int x, int y, int w, int h, int roadDist) {
        JsonObject r = new JsonObject();
        r.addProperty("found", true);
        r.addProperty("x", x);
        r.addProperty("y", y);
        r.addProperty("width", w);
        r.addProperty("height", h);
        r.addProperty("distance_to_road", roadDist);
        return r;
    }

    private boolean isAreaEmpty(int x0, int y0, int w, int h) {
        boolean autoBulldoze = engine.isAutoBulldoze();
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                char tile = engine.getTile(x, y);
                if (tile == 0) continue;
                if (autoBulldoze && TileConstants.canAutoBulldozeZ(tile)) continue;
                return false;
            }
        }
        return true;
    }

    private int distanceToNearestRoad(int x0, int y0, int w, int h) {
        int best = Integer.MAX_VALUE;
        for (int d = 1; d <= 10; d++) {
            for (int x = x0 - d; x <= x0 + w - 1 + d; x++) {
                if (isRoadAt(x, y0 - d) || isRoadAt(x, y0 + h - 1 + d)) return d;
            }
            for (int y = y0 - d; y <= y0 + h - 1 + d; y++) {
                if (isRoadAt(x0 - d, y) || isRoadAt(x0 + w - 1 + d, y)) return d;
            }
        }
        return best;
    }

    private boolean isRoadAt(int x, int y) {
        if (!engine.testBounds(x, y)) return false;
        char t = engine.getTile(x, y);
        return t >= 64 && t < 208;
    }

    private String classifyTile(char tile) {
        if (tile >= 2 && tile <= 20) return "water";
        if (tile >= 21 && tile <= 43) return "tree";
        if (tile >= 44 && tile <= 47) return "rubble";
        if (tile >= 56 && tile <= 63) return "fire";
        if (tile >= 64 && tile < 208) return "road";
        if (tile >= 208 && tile < 224) return "power_line";
        if (tile >= 224 && tile < 240) return "rail";
        if (tile >= 240 && tile < 423) return "residential";
        if (tile >= 423 && tile < 612) return "commercial";
        if (tile >= 612 && tile < 693) return "industrial";
        if (tile >= 693 && tile < 716) return "seaport";
        if (tile >= 716 && tile < 750) return "airport";
        if (tile >= 750 && tile < 765) return "coal_power";
        if (tile >= 765 && tile < 774) return "fire_station";
        if (tile >= 774 && tile < 784) return "police_station";
        if (tile >= 784 && tile < 800) return "stadium";
        if (tile >= 816 && tile < 827) return "nuclear_power";
        if (tile == 840) return "fountain";
        return "other";
    }
}
