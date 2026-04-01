package micropolisj.engine;

import java.io.File;
import java.util.Random;

public class NYCGenerator {

    static final char DIRT = TileConstants.DIRT;
    static final char RIVER = TileConstants.RIVER;
    static final char WOODS = 37;
    static final char ROADS = 66;
    static final char LHPOWER = 210;
    static final char LVPOWER = 211;
    static final char FOUNTAIN = TileConstants.FOUNTAIN;

    static final int W = 120;
    static final int H = 100;
    static Random rand = new Random(42);

    public static void main(String[] args) throws Exception {
        Micropolis city = new Micropolis();
        city.getBudget().setTotalFunds(999999999);

        boolean[][] water = new boolean[H][W];
        buildTerrain(water);
        applyTerrain(city, water);
        buildRoads(city, water);
        buildZones(city, water);
        buildInfrastructure(city, water);
        buildPowerGrid(city, water);
        buildParks(city, water);

        city.getBudget().setTotalFunds(999999999);

        File outFile = new File("nyc.cty");
        city.save(outFile);
        System.out.println("NYC city saved to: " + outFile.getAbsolutePath());
    }

    static boolean inManhattan(int x, int y) {
        return x >= 28 && x <= 52 && y >= 8 && y <= 72;
    }

    static boolean inCentralPark(int x, int y) {
        return x >= 34 && x <= 46 && y >= 28 && y <= 42;
    }

    static boolean inBronx(int x, int y) {
        return x >= 35 && x <= 85 && y >= 0 && y <= 10;
    }

    static boolean inQueens(int x, int y) {
        return x >= 60 && x <= 110 && y >= 12 && y <= 55;
    }

    static boolean inBrooklyn(int x, int y) {
        return x >= 55 && x <= 100 && y >= 58 && y <= 88;
    }

    static boolean inStatenIsland(int x, int y) {
        return x >= 5 && x <= 22 && y >= 78 && y <= 95;
    }

    static boolean isHudsonRiver(int x, int y) {
        if (y < 8 || y > 75) return false;
        int riverLeft = 22 + (int)(Math.sin(y * 0.08) * 2);
        int riverRight = 28 + (int)(Math.sin(y * 0.08) * 2);
        return x >= riverLeft && x < riverRight;
    }

    static boolean isEastRiver(int x, int y) {
        if (y < 8 || y > 75) return false;
        int riverLeft = 52 + (int)(Math.sin(y * 0.1) * 2);
        int riverRight = 58 + (int)(Math.sin(y * 0.1) * 2);
        return x >= riverLeft && x < riverRight;
    }

    static boolean isUpperBay(int x, int y) {
        return y >= 73 && y <= 82 && x >= 22 && x <= 58;
    }

    static boolean isLowerBay(int x, int y) {
        return y >= 83 && x >= 0 && x <= 55 && !inStatenIsland(x, y);
    }

    static boolean isNJWater(int x, int y) {
        return x <= 21 && y <= 75 && y >= 0 && !(x >= 5 && x <= 20 && y <= 6);
    }

    static boolean isWater(int x, int y) {
        return isHudsonRiver(x, y) || isEastRiver(x, y) ||
               isUpperBay(x, y) || isLowerBay(x, y) || isNJWater(x, y);
    }

    static void buildTerrain(boolean[][] water) {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                water[y][x] = isWater(x, y);
            }
        }
    }

    static void applyTerrain(Micropolis city, boolean[][] water) {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (water[y][x]) {
                    city.setTile(x, y, RIVER);
                } else {
                    city.setTile(x, y, DIRT);
                }
            }
        }
    }

    static boolean canPlace(boolean[][] water, int x, int y) {
        return x >= 0 && x < W && y >= 0 && y < H && !water[y][x];
    }

    static boolean canPlaceZone(boolean[][] water, int x, int y, int size) {
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                if (!canPlace(water, x + dx, y + dy)) return false;
            }
        }
        return true;
    }

    static void placeBuilding(Micropolis city, boolean[][] water, MicropolisTool tool, int x, int y) {
        if (!canPlaceZone(water, x, y, tool.getSize())) return;
        for (int dy = 0; dy < tool.getSize(); dy++) {
            for (int dx = 0; dx < tool.getSize(); dx++) {
                char t = city.getTile(x + dx, y + dy);
                if (t != DIRT && t != RIVER) return;
            }
        }
        ToolStroke stroke = tool.beginStroke(city, x, y);
        stroke.dragTo(x, y);
        stroke.apply();
    }

    static void placeRoad(Micropolis city, int x, int y) {
        if (x < 0 || x >= W || y < 0 || y >= H) return;
        ToolStroke stroke = MicropolisTool.ROADS.beginStroke(city, x, y);
        stroke.dragTo(x, y);
        stroke.apply();
    }

    static void buildRoads(Micropolis city, boolean[][] water) {
        // Manhattan grid
        for (int x = 28; x <= 52; x++) {
            for (int y = 8; y <= 72; y++) {
                if (inCentralPark(x, y)) continue;
                if (!canPlace(water, x, y)) continue;
                boolean isAvenue = (x - 28) % 4 == 0;
                boolean isStreet = (y - 8) % 4 == 0;
                if (isAvenue || isStreet) placeRoad(city, x, y);
            }
        }

        // Bronx grid
        for (int x = 36; x <= 84; x++) {
            for (int y = 0; y <= 10; y++) {
                if (!canPlace(water, x, y)) continue;
                if ((x - 36) % 5 == 0 || y % 5 == 0) placeRoad(city, x, y);
            }
        }

        // Queens grid
        for (int x = 60; x <= 110; x++) {
            for (int y = 12; y <= 55; y++) {
                if (!canPlace(water, x, y)) continue;
                if (x >= 90 && x <= 108 && y >= 38 && y <= 52) continue;
                if ((x - 60) % 5 == 0 || (y - 12) % 5 == 0) placeRoad(city, x, y);
            }
        }

        // Brooklyn grid
        for (int x = 55; x <= 100; x++) {
            for (int y = 58; y <= 88; y++) {
                if (!canPlace(water, x, y)) continue;
                if ((x - 55) % 5 == 0 || (y - 58) % 5 == 0) placeRoad(city, x, y);
            }
        }

        // Staten Island
        for (int x = 5; x <= 22; x++) {
            for (int y = 78; y <= 95; y++) {
                if (!canPlace(water, x, y)) continue;
                if ((x - 5) % 6 == 0 || (y - 78) % 6 == 0) placeRoad(city, x, y);
            }
        }

        // Bridges (place road tiles over water)
        for (int y = 73; y <= 82; y++) { city.setTile(40, y, ROADS); city.setTile(45, y, ROADS); }
        for (int y = 6; y <= 8; y++) { city.setTile(36, y, ROADS); city.setTile(42, y, ROADS); }
        for (int x = 52; x <= 60; x++) { city.setTile(x, 30, ROADS); city.setTile(x, 45, ROADS); city.setTile(x, 55, ROADS); }
    }

    static void buildZones(Micropolis city, boolean[][] water) {
        // Manhattan: Dense commercial
        for (int x = 28; x <= 50; x += 4) {
            for (int y = 8; y <= 70; y += 4) {
                if (inCentralPark(x + 1, y + 1)) continue;
                placeBuilding(city, water, MicropolisTool.COMMERCIAL, x + 1, y + 1);
            }
        }

        // Bronx: residential + industrial
        for (int x = 36; x <= 82; x += 5) {
            for (int y = 0; y <= 8; y += 5) {
                MicropolisTool t = rand.nextFloat() < 0.6f ? MicropolisTool.RESIDENTIAL : MicropolisTool.INDUSTRIAL;
                placeBuilding(city, water, t, x + 1, y + 1);
            }
        }

        // Queens: residential + commercial + industrial
        for (int x = 60; x <= 108; x += 5) {
            for (int y = 12; y <= 53; y += 5) {
                if (x >= 90 && x <= 108 && y >= 38 && y <= 52) continue;
                float r = rand.nextFloat();
                MicropolisTool t = r < 0.55f ? MicropolisTool.RESIDENTIAL : r < 0.8f ? MicropolisTool.COMMERCIAL : MicropolisTool.INDUSTRIAL;
                placeBuilding(city, water, t, x + 1, y + 1);
            }
        }

        // Brooklyn: residential + commercial
        for (int x = 55; x <= 98; x += 5) {
            for (int y = 58; y <= 86; y += 5) {
                MicropolisTool t = rand.nextFloat() < 0.7f ? MicropolisTool.RESIDENTIAL : MicropolisTool.COMMERCIAL;
                placeBuilding(city, water, t, x + 1, y + 1);
            }
        }

        // Staten Island: residential
        for (int x = 5; x <= 20; x += 6) {
            for (int y = 78; y <= 93; y += 6) {
                placeBuilding(city, water, MicropolisTool.RESIDENTIAL, x + 1, y + 1);
            }
        }
    }

    static void buildInfrastructure(Micropolis city, boolean[][] water) {
        placeBuilding(city, water, MicropolisTool.NUCLEAR, 30, 9);
        placeBuilding(city, water, MicropolisTool.POWERPLANT, 38, 65);
        placeBuilding(city, water, MicropolisTool.NUCLEAR, 75, 14);
        placeBuilding(city, water, MicropolisTool.POWERPLANT, 80, 70);
        placeBuilding(city, water, MicropolisTool.POWERPLANT, 8, 80);

        placeBuilding(city, water, MicropolisTool.POLICE, 35, 20);
        placeBuilding(city, water, MicropolisTool.POLICE, 40, 50);
        placeBuilding(city, water, MicropolisTool.POLICE, 70, 3);
        placeBuilding(city, water, MicropolisTool.POLICE, 80, 30);
        placeBuilding(city, water, MicropolisTool.POLICE, 70, 65);
        placeBuilding(city, water, MicropolisTool.POLICE, 95, 75);
        placeBuilding(city, water, MicropolisTool.POLICE, 12, 85);

        placeBuilding(city, water, MicropolisTool.FIRE, 32, 30);
        placeBuilding(city, water, MicropolisTool.FIRE, 45, 55);
        placeBuilding(city, water, MicropolisTool.FIRE, 55, 3);
        placeBuilding(city, water, MicropolisTool.FIRE, 90, 25);
        placeBuilding(city, water, MicropolisTool.FIRE, 75, 75);
        placeBuilding(city, water, MicropolisTool.FIRE, 15, 90);

        placeBuilding(city, water, MicropolisTool.STADIUM, 50, 1);

        placeBuilding(city, water, MicropolisTool.SEAPORT, 60, 85);
        placeBuilding(city, water, MicropolisTool.SEAPORT, 68, 85);

        placeBuilding(city, water, MicropolisTool.AIRPORT, 92, 40);
    }

    static void buildPowerGrid(Micropolis city, boolean[][] water) {
        // Manhattan power backbone
        for (int y = 9; y <= 72; y++) {
            if (inCentralPark(29, y)) continue;
            if (canPlace(water, 29, y)) {
                char t = city.getTile(29, y);
                if (t == DIRT) city.setTile(29, y, LVPOWER);
            }
        }

        // Queens vertical power lines
        for (int x = 60; x <= 110; x += 10) {
            for (int y = 12; y <= 55; y++) {
                if (canPlace(water, x, y)) {
                    char t = city.getTile(x, y);
                    if (t == DIRT) city.setTile(x, y, LVPOWER);
                }
            }
        }

        // Brooklyn vertical power lines
        for (int x = 55; x <= 100; x += 10) {
            for (int y = 58; y <= 88; y++) {
                if (canPlace(water, x, y)) {
                    char t = city.getTile(x, y);
                    if (t == DIRT) city.setTile(x, y, LVPOWER);
                }
            }
        }

        // Horizontal connector lines
        for (int x = 28; x <= 110; x++) {
            if (canPlace(water, x, 12)) {
                char t = city.getTile(x, 12);
                if (t == DIRT) city.setTile(x, 12, LHPOWER);
            }
        }
        for (int x = 28; x <= 100; x++) {
            if (canPlace(water, x, 57)) {
                char t = city.getTile(x, 57);
                if (t == DIRT) city.setTile(x, 57, LHPOWER);
            }
        }
    }

    static void buildParks(Micropolis city, boolean[][] water) {
        // Central Park
        for (int x = 34; x <= 46; x++) {
            for (int y = 28; y <= 42; y++) {
                if (canPlace(water, x, y)) {
                    city.setTile(x, y, (x + y) % 5 == 0 ? FOUNTAIN : WOODS);
                }
            }
        }

        // Borough parks
        int[][] parkSpots = {{65, 20}, {75, 25}, {85, 35}, {60, 65}, {70, 70}, {85, 80}, {10, 82}, {15, 88}};
        for (int[] spot : parkSpots) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dy = 0; dy < 3; dy++) {
                    int px = spot[0] + dx, py = spot[1] + dy;
                    if (canPlace(water, px, py) && city.getTile(px, py) == DIRT) {
                        city.setTile(px, py, WOODS);
                    }
                }
            }
        }
    }
}
