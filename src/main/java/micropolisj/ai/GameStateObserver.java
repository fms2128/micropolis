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

    private static final int[] PERIM_X = {-1, 0, 1, 2, 2, 2, 1, 0, -1, -2, -2, -2};
    private static final int[] PERIM_Y = {-2, -2, -2, -1, 0, 1, 2, 2, 2, 1, 0, -1};
    private static final String[] CITY_CLASS_NAMES = {
        "Village", "Town", "City", "Capital", "Metropolis", "Megalopolis"
    };

    private final Micropolis engine;

    private int prevScore = -1;
    private int prevPopulation = -1;
    private int prevFunds = -1;
    private int prevUnpowered = -1;
    private int prevPollution = -1;
    private int prevCrime = -1;
    private double smoothedReward = 0.0;

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

        int curUnpowered = engine.getUnpoweredZoneCount();
        int curPollution = engine.getPollutionAverage();
        int curCrime = engine.getCrimeAverage();

        if (prevScore >= 0) {
            int deltaScore = curScore - prevScore;
            int deltaPop = curPop - prevPopulation;
            int deltaFunds = curFunds - prevFunds;

            s.addProperty("delta_score", deltaScore);
            s.addProperty("delta_population", deltaPop);
            s.addProperty("delta_funds", deltaFunds);

            double scoreComponent = deltaScore * 2.0;
            double popDivisor = Math.max(100.0, curPop * 0.02);
            double popComponent = deltaPop / popDivisor;
            double fundsComponent = deltaFunds / 500.0;

            double structuralBonus = 0;
            if (prevUnpowered > 0 && curUnpowered < prevUnpowered) {
                structuralBonus += (prevUnpowered - curUnpowered) * 0.5;
            }
            if (prevPollution > 0 && curPollution < prevPollution) {
                structuralBonus += (prevPollution - curPollution) * 0.1;
            }
            if (prevCrime > 0 && curCrime < prevCrime) {
                structuralBonus += (prevCrime - curCrime) * 0.1;
            }

            double instantReward = scoreComponent + popComponent + fundsComponent + structuralBonus;
            smoothedReward = 0.3 * instantReward + 0.7 * smoothedReward;

            s.addProperty("reward_score", round2(scoreComponent));
            s.addProperty("reward_pop", round2(popComponent));
            s.addProperty("reward_funds", round2(fundsComponent));
            if (structuralBonus != 0) {
                s.addProperty("reward_structural", round2(structuralBonus));
            }
            s.addProperty("reward", round2(instantReward));
            s.addProperty("reward_trend", round2(smoothedReward));
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
        prevUnpowered = curUnpowered;
        prevPollution = curPollution;
        prevCrime = curCrime;

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

    /**
     * Matches engine's TrafficGen.roadTest: roads and rails pass, power lines fail.
     * Range: ROADBASE(64)..LASTRAIL(238) excluding POWERBASE(208)..LASTPOWER(222)-1.
     */
    private boolean engineRoadTest(int x, int y) {
        if (!engine.testBounds(x, y)) return false;
        char c = engine.getTile(x, y);
        return c >= 64 && c <= 238 && (c < 208 || c >= 222);
    }

    /**
     * Matches engine's TrafficGen.findPerimeterRoad — checks 12 diamond offsets
     * around the zone center, exactly as the engine does for traffic generation.
     */
    private boolean hasPerimeterRoad(int cx, int cy) {
        for (int i = 0; i < 12; i++) {
            if (engineRoadTest(cx + PERIM_X[i], cy + PERIM_Y[i])) return true;
        }
        return false;
    }

    /**
     * Quick infrastructure scan: lists unpowered buildings with coordinates.
     * Only checks zone CENTER tiles (where PWRBIT is reliable).
     */
    public JsonObject diagnoseInfrastructureQuick() {
        JsonObject result = new JsonObject();
        JsonArray warnings = new JsonArray();
        int unpoweredBuildings = 0;

        for (int y = 0; y < engine.getHeight(); y++) {
            for (int x = 0; x < engine.getWidth(); x++) {
                char tile = engine.getTile(x, y);
                if (!TileConstants.isZoneCenter(tile)) continue;
                if (tile < 693) continue;

                if (!engine.isTilePowered(x, y)) {
                    String type = classifyTile(tile);
                    unpoweredBuildings++;
                    warnings.add("[CRITICAL] " + type + " at (" + x + "," + y + ") has NO POWER");
                }
            }
        }

        result.addProperty("unpowered_buildings", unpoweredBuildings);
        result.add("warnings", warnings);
        return result;
    }

    /**
     * Full infrastructure diagnosis. Only checks zone CENTER tiles where
     * PWRBIT and road access are reliable (matching engine behavior).
     */
    public JsonObject diagnoseInfrastructure() {
        JsonObject result = new JsonObject();
        JsonArray critical = new JsonArray();
        JsonArray roadWarnings = new JsonArray();
        int unpoweredZoneCount = 0;
        int noRoadZoneCount = 0;

        for (int y = 0; y < engine.getHeight(); y++) {
            for (int x = 0; x < engine.getWidth(); x++) {
                char tile = engine.getTile(x, y);
                if (!TileConstants.isZoneCenter(tile)) continue;

                boolean powered = engine.isTilePowered(x, y);
                String type = classifyTile(tile);

                if (!powered) {
                    if (tile >= 693) {
                        critical.add(type + " at (" + x + "," + y + ") NO POWER");
                    }
                    unpoweredZoneCount++;
                }

                if (!hasPerimeterRoad(x, y)) {
                    roadWarnings.add(type + " at (" + x + "," + y + ") NO ROAD access");
                    noRoadZoneCount++;
                }
            }
        }

        result.addProperty("unpowered_zones", unpoweredZoneCount);
        result.addProperty("zones_without_road", noRoadZoneCount);
        result.add("critical_issues", critical);
        result.add("road_warnings", roadWarnings);
        result.addProperty("total_issues", critical.size() + roadWarnings.size());

        if (critical.size() == 0 && roadWarnings.size() == 0) {
            result.addProperty("status", "All infrastructure OK");
        }
        return result;
    }

    /**
     * Comprehensive city entity catalog. Scans the entire map once and returns
     * every building, zone, infrastructure count, and active problem — using
     * only zone CENTER tiles for power/road checks (matching engine behavior).
     */
    public JsonObject getCityEntities() {
        JsonObject result = new JsonObject();
        JsonArray buildings = new JsonArray();
        JsonArray unpoweredZones = new JsonArray();
        JsonArray noRoadZones = new JsonArray();
        JsonArray problems = new JsonArray();

        int totalRes = 0, poweredRes = 0, roadRes = 0;
        int totalCom = 0, poweredCom = 0, roadCom = 0;
        int totalInd = 0, poweredInd = 0, roadInd = 0;
        int roadTiles = 0, powerLineTiles = 0, railTiles = 0;

        for (int y = 0; y < engine.getHeight(); y++) {
            for (int x = 0; x < engine.getWidth(); x++) {
                char tile = engine.getTile(x, y);

                if (tile >= 64 && tile < 208) roadTiles++;
                else if (tile >= 208 && tile < 224) powerLineTiles++;
                else if (tile >= 224 && tile < 240) railTiles++;

                if (tile >= 56 && tile <= 63) {
                    addProblem(problems, "fire", x, y);
                } else if (tile >= 44 && tile <= 47) {
                    addProblem(problems, "rubble", x, y);
                } else if (tile >= 48 && tile <= 51) {
                    addProblem(problems, "flood", x, y);
                } else if (tile == 52) {
                    addProblem(problems, "radiation", x, y);
                }

                if (!TileConstants.isZoneCenter(tile)) continue;

                boolean powered = engine.isTilePowered(x, y);
                boolean hasRoad = hasPerimeterRoad(x, y);

                if (tile >= 693) {
                    JsonObject bldg = new JsonObject();
                    bldg.addProperty("type", classifyTile(tile));
                    bldg.addProperty("x", x);
                    bldg.addProperty("y", y);
                    bldg.addProperty("powered", powered);
                    bldg.addProperty("road_access", hasRoad);
                    CityDimension dim = TileConstants.getZoneSizeFor(tile);
                    if (dim != null) {
                        bldg.addProperty("size", dim.width + "x" + dim.height);
                    }
                    buildings.add(bldg);
                } else if (tile >= 240 && tile < 423) {
                    totalRes++;
                    if (powered) poweredRes++;
                    if (hasRoad) roadRes++;
                    if (!powered) addZoneIssue(unpoweredZones, "residential", x, y);
                    if (!hasRoad) addZoneIssue(noRoadZones, "residential", x, y);
                } else if (tile >= 423 && tile < 612) {
                    totalCom++;
                    if (powered) poweredCom++;
                    if (hasRoad) roadCom++;
                    if (!powered) addZoneIssue(unpoweredZones, "commercial", x, y);
                    if (!hasRoad) addZoneIssue(noRoadZones, "commercial", x, y);
                } else if (tile >= 612 && tile < 693) {
                    totalInd++;
                    if (powered) poweredInd++;
                    if (hasRoad) roadInd++;
                    if (!powered) addZoneIssue(unpoweredZones, "industrial", x, y);
                    if (!hasRoad) addZoneIssue(noRoadZones, "industrial", x, y);
                }
            }
        }

        result.add("buildings", buildings);

        JsonObject zs = new JsonObject();
        zs.addProperty("total_res", totalRes);
        zs.addProperty("powered_res", poweredRes);
        zs.addProperty("road_res", roadRes);
        zs.addProperty("total_com", totalCom);
        zs.addProperty("powered_com", poweredCom);
        zs.addProperty("road_com", roadCom);
        zs.addProperty("total_ind", totalInd);
        zs.addProperty("powered_ind", poweredInd);
        zs.addProperty("road_ind", roadInd);
        result.add("zone_summary", zs);

        if (unpoweredZones.size() > 0) result.add("unpowered_zones", unpoweredZones);
        if (noRoadZones.size() > 0) result.add("no_road_zones", noRoadZones);

        JsonObject infra = new JsonObject();
        infra.addProperty("total_road_tiles", roadTiles);
        infra.addProperty("total_power_line_tiles", powerLineTiles);
        infra.addProperty("total_rail_tiles", railTiles);
        result.add("infrastructure", infra);

        if (problems.size() > 0) result.add("problems", problems);

        return result;
    }

    private void addProblem(JsonArray arr, String type, int x, int y) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("x", x);
        p.addProperty("y", y);
        arr.add(p);
    }

    private void addZoneIssue(JsonArray arr, String type, int x, int y) {
        JsonObject z = new JsonObject();
        z.addProperty("type", type);
        z.addProperty("x", x);
        z.addProperty("y", y);
        arr.add(z);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Renders an ASCII map of the area around (cx,cy) with the given radius.
     * Returns three layered views: terrain, zones, and infrastructure.
     * Unpowered zones use lowercase letters so the agent can spot them instantly.
     */
    public JsonObject renderMapArea(int cx, int cy, int radius) {
        radius = Math.max(1, Math.min(radius, 25));
        int x0 = Math.max(0, cx - radius);
        int y0 = Math.max(0, cy - radius);
        int x1 = Math.min(engine.getWidth() - 1, cx + radius);
        int y1 = Math.min(engine.getHeight() - 1, cy + radius);
        int w = x1 - x0 + 1;
        int h = y1 - y0 + 1;

        char[][] terrain = new char[h][w];
        char[][] zones = new char[h][w];
        char[][] infra = new char[h][w];

        int unpoweredCount = 0;
        int roadCount = 0;
        int zoneCount = 0;
        JsonArray issues = new JsonArray();

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int gy = y - y0;
                int gx = x - x0;
                char tile = engine.getTile(x, y);
                boolean isCenter = TileConstants.isZoneCenter(tile);
                boolean powered = isCenter && engine.isTilePowered(x, y);

                // Terrain layer
                if (tile == 0) terrain[gy][gx] = '.';
                else if (tile >= 2 && tile <= 20) terrain[gy][gx] = '~';
                else if (tile >= 21 && tile <= 43) terrain[gy][gx] = 'T';
                else if (tile >= 44 && tile <= 47) terrain[gy][gx] = 'X';
                else if (tile >= 56 && tile <= 63) terrain[gy][gx] = '!';
                else terrain[gy][gx] = ' ';

                // Zones layer — only mark lowercase on zone CENTER tiles (PWRBIT reliable there only)
                if (tile >= 240 && tile < 423) {
                    zones[gy][gx] = (isCenter && !powered) ? 'r' : 'R';
                    zoneCount++;
                    if (isCenter && !powered) unpoweredCount++;
                } else if (tile >= 423 && tile < 612) {
                    zones[gy][gx] = (isCenter && !powered) ? 'c' : 'C';
                    zoneCount++;
                    if (isCenter && !powered) unpoweredCount++;
                } else if (tile >= 612 && tile < 693) {
                    zones[gy][gx] = (isCenter && !powered) ? 'i' : 'I';
                    zoneCount++;
                    if (isCenter && !powered) unpoweredCount++;
                } else {
                    zones[gy][gx] = '.';
                }

                // Infrastructure layer — only flag power issues on zone CENTER tiles
                if (tile >= 64 && tile < 208) {
                    infra[gy][gx] = '=';
                    roadCount++;
                } else if (tile >= 208 && tile < 224) {
                    infra[gy][gx] = '+';
                } else if (tile >= 224 && tile < 240) {
                    infra[gy][gx] = '#';
                } else if (tile >= 750 && tile < 765) {
                    infra[gy][gx] = (isCenter && !powered) ? 'p' : 'P';
                    if (isCenter && !powered) issues.add("!! Coal power plant at (" + x + "," + y + ") has NO POWER");
                } else if (tile >= 816 && tile < 827) {
                    infra[gy][gx] = (isCenter && !powered) ? 'n' : 'N';
                    if (isCenter && !powered) issues.add("!! Nuclear plant at (" + x + "," + y + ") has NO POWER");
                } else if (tile >= 765 && tile < 774) {
                    infra[gy][gx] = (isCenter && !powered) ? 'f' : 'F';
                    if (isCenter && !powered) issues.add("!! Fire station at (" + x + "," + y + ") NO POWER");
                } else if (tile >= 774 && tile < 784) {
                    infra[gy][gx] = (isCenter && !powered) ? 's' : 'S';
                    if (isCenter && !powered) issues.add("!! Police station at (" + x + "," + y + ") NO POWER");
                } else if (tile >= 693 && tile < 716) {
                    infra[gy][gx] = (isCenter && !powered) ? 'h' : 'H';
                    if (isCenter && !powered) issues.add("!! Seaport at (" + x + "," + y + ") has NO POWER");
                } else if (tile >= 716 && tile < 750) {
                    infra[gy][gx] = (isCenter && !powered) ? 'a' : 'A';
                    if (isCenter && !powered) issues.add("!! Airport at (" + x + "," + y + ") has NO POWER");
                } else if (tile >= 784 && tile < 800) {
                    infra[gy][gx] = (isCenter && !powered) ? 'd' : 'D';
                    if (isCenter && !powered) issues.add("!! Stadium at (" + x + "," + y + ") has NO POWER");
                } else if (isCenter && tile >= 240 && !powered) {
                    infra[gy][gx] = '*';
                } else {
                    infra[gy][gx] = '.';
                }

                // Road access check — only on zone center tiles
                if (isCenter && tile >= 240 && !hasPerimeterRoad(x, y)) {
                    issues.add("!! " + classifyTile(tile) + " at (" + x + "," + y + ") NO ROAD access");
                }
            }
        }

        if (unpoweredCount > 0) {
            issues.add("!! " + unpoweredCount + " unpowered zones (center tiles shown as lowercase)");
        }

        JsonObject result = new JsonObject();
        result.addProperty("center_x", cx);
        result.addProperty("center_y", cy);
        result.addProperty("x_range", x0 + "-" + x1);
        result.addProperty("y_range", y0 + "-" + y1);
        result.addProperty("zone_count", zoneCount);
        result.addProperty("unpowered_count", unpoweredCount);
        result.addProperty("road_tiles", roadCount);

        result.addProperty("terrain", renderGrid(terrain, x0, y0,
            "Legend: .=empty ~=water T=tree X=rubble !=fire"));
        result.addProperty("zones", renderGrid(zones, x0, y0,
            "Legend: R=residential C=commercial I=industrial .=none (lowercase=UNPOWERED)"));
        result.addProperty("infrastructure", renderGrid(infra, x0, y0,
            "Legend: ==road +=power #=rail P=coal N=nuclear F=fire_st S=police H=seaport A=airport D=stadium *=unpowered_zone (lowercase=NO POWER)"));

        if (issues.size() > 0) {
            result.add("issues", issues);
        }

        return result;
    }

    private String renderGrid(char[][] grid, int x0, int y0, String legend) {
        StringBuilder sb = new StringBuilder();
        sb.append(legend).append("\n");

        sb.append("    ");
        for (int x = 0; x < grid[0].length; x++) {
            if (x % 5 == 0) sb.append(String.format("%-5d", x0 + x));
        }
        sb.append("\n");

        for (int y = 0; y < grid.length; y++) {
            sb.append(String.format("%3d ", y0 + y));
            sb.append(new String(grid[y]));
            sb.append("\n");
        }
        return sb.toString();
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

    /**
     * Returns historical trend data for city metrics.
     * @param metric one of: residential, commercial, industrial, crime, pollution, money, all
     * @param period "recent" (last ~10 years, monthly) or "long_term" (120 years, yearly samples)
     */
    public JsonObject getHistoryData(String metric, String period) {
        History h = engine.getHistory();
        boolean longTerm = "long_term".equalsIgnoreCase(period);
        int offset = longTerm ? 120 : 0;
        int count = longTerm ? 120 : 120;
        int step = longTerm ? 10 : 6;

        JsonObject result = new JsonObject();
        result.addProperty("period", longTerm ? "long_term (120 years)" : "recent (10 years)");
        result.addProperty("sample_interval", longTerm ? "~10 years" : "~6 months");

        boolean all = "all".equalsIgnoreCase(metric);

        if (all || "residential".equalsIgnoreCase(metric))
            result.add("residential", buildSeries(h.getRes(), offset, count, step));
        if (all || "commercial".equalsIgnoreCase(metric))
            result.add("commercial", buildSeries(h.getCom(), offset, count, step));
        if (all || "industrial".equalsIgnoreCase(metric))
            result.add("industrial", buildSeries(h.getInd(), offset, count, step));
        if (all || "crime".equalsIgnoreCase(metric))
            result.add("crime", buildSeries(h.getCrime(), offset, count, step));
        if (all || "pollution".equalsIgnoreCase(metric))
            result.add("pollution", buildSeries(h.getPollution(), offset, count, step));
        if (all || "money".equalsIgnoreCase(metric))
            result.add("money", buildSeries(h.getMoney(), offset, count, step));

        if (all || "financial".equalsIgnoreCase(metric)) {
            result.add("financial_history", getFinancialHistoryData());
        }

        return result;
    }

    private JsonObject buildSeries(int[] data, int offset, int count, int step) {
        JsonArray values = new JsonArray();
        int sum = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int nonZeroCount = 0;

        for (int i = 0; i < count; i += step) {
            int idx = offset + i;
            if (idx >= data.length) break;
            int val = data[idx];
            values.add(val);
            if (val > 0 || nonZeroCount > 0) {
                sum += val;
                nonZeroCount++;
                min = Math.min(min, val);
                max = Math.max(max, val);
            }
        }

        JsonObject series = new JsonObject();
        series.add("values", values);
        series.addProperty("current", data[offset]);
        if (nonZeroCount > 0) {
            series.addProperty("min", min);
            series.addProperty("max", max);
            series.addProperty("avg", Math.round((double) sum / nonZeroCount));
            series.addProperty("trend", detectTrend(data, offset, Math.min(count, 30)));
        }
        return series;
    }

    private String detectTrend(int[] data, int offset, int window) {
        int recent = 0, older = 0;
        int half = window / 2;
        int recentCount = 0, olderCount = 0;

        for (int i = 0; i < half && (offset + i) < data.length; i++) {
            recent += data[offset + i];
            recentCount++;
        }
        for (int i = half; i < window && (offset + i) < data.length; i++) {
            older += data[offset + i];
            olderCount++;
        }

        if (recentCount == 0 || olderCount == 0) return "insufficient_data";

        double recentAvg = (double) recent / recentCount;
        double olderAvg = (double) older / olderCount;
        double denominator = Math.max(1.0, (recentAvg + olderAvg) / 2.0);
        double changePct = (recentAvg - olderAvg) / denominator * 100;

        if (changePct > 15) return "rising_fast";
        if (changePct > 5) return "rising";
        if (changePct < -15) return "falling_fast";
        if (changePct < -5) return "falling";
        return "stable";
    }

    private JsonArray getFinancialHistoryData() {
        java.util.List<FinancialHistory> fh = engine.getFinancialHistory();
        JsonArray arr = new JsonArray();
        int limit = Math.min(fh.size(), 20);
        for (int i = 0; i < limit; i++) {
            FinancialHistory entry = fh.get(i);
            JsonObject e = new JsonObject();
            int year = 1900 + entry.getCityTime() / 48;
            int month = (entry.getCityTime() % 48) / 4 + 1;
            e.addProperty("date", String.format("%d-%02d", year, month));
            e.addProperty("funds", entry.getTotalFunds());
            e.addProperty("tax_income", entry.getTaxIncome());
            e.addProperty("expenses", entry.getOperatingExpenses());
            e.addProperty("net", entry.getTaxIncome() - entry.getOperatingExpenses());
            arr.add(e);
        }
        return arr;
    }
}
