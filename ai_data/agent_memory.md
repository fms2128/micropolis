# Agent Memory - Micropolis AI Cheatsheet

> This is your long-term memory. It persists across sessions and games.
> Use it to record what works, what doesn't, and refine your strategy over time.
> The reward signal tells you HOW you're doing. This file tells you WHY and WHAT TO DO ABOUT IT.

## Strategies That Work

- Check demand valves FIRST each turn. Build what the city actually needs (positive valve = build more).
- Residential first after power plant: citizens need homes before anything else grows. Place in map center for best land value.
- Follow the Three-Ring Model: industrial at map edges (pollution spills off), commercial as buffer, residential in center.
- Use two-zone strip pattern: two rows of zones with one road between them. 25% denser than donut layout.
- Chain zones together (touching) so power propagates freely. Only use wires to bridge road gaps.
- When residential demand is maxed, a single cheap zone in a proven corridor with road access reliably grows population.
- After a negative reward from expensive purchases, pivot to cheap single-zone placements to recover quickly.
- Fixing infrastructure (power, roads) in an existing dense neighborhood often outperforms building new zones in unproven areas.
- When one zone type stalls, pivot to a different demand-matching type in the same proven corridor.
- Lower tax from 7% to 4-5% once population is growing steadily. Tax reduction can produce immediate score rebounds.

## Strategies That Don't Work

- Buying coal plants as "quick fix" when budget is tight. The cash hit dominates the reward, and coal creates pollution.
- Industrial spam without prior residential: pollution skyrockets, residential demand goes negative, score stalls.
- Placing residential directly next to industrial or power plants: pollution destroys land value and growth.
- Extending zones into areas that repeatedly query as unpowered. Stop, fix power, then expand.
- Repeating the same failing action for 2-3 turns. After 2 flat turns, pivot to a new area or different lever.
- Adding expensive service buildings ($500+) consecutively when the underlying problem isn't services.
- Lowering taxes below 4% before meaningful population exists. Keep 5-7% until tax income covers expenses.
- Placing industrial directly adjacent to dense residential corridors. Always maintain buffer distance.
- Building roads around zones instead of between them. Wastes money and creates traffic.
- Investing in remote isolated pockets far from existing infrastructure. Prefer infill near proven growth.

## Placement Rules Learned

- x,y coordinates are the TOP-LEFT corner of the building footprint.
- Build roads and power lines ADJACENT to zone footprints, not through the 3x3 area (blocks placement).
- Roads do NOT conduct power. If a road separates plant from zones, build a wire across it.
- Zones touching each other propagate power freely — no wire needed between adjacent zones.
- find_empty_area can suggest spots near water. Always verify with inspect_area before committing.
- Water permanently blocks placement and breaks power connections.
- When a 3x3 placement is rejected on a clear-looking spot, shift by 1-2 tiles and retry.
- Power propagates through zone tiles but NOT through roads, rails, parks, or empty land.

## Financial Management

- Start at 7% tax. Lower to 5% once population is growing. Go to 3-4% only when income comfortably covers expenses.
- Road funding at 50-80% early (small network). Full funding for larger networks to maintain roadEffect=32.
- One power plant supports roughly 20-25 zones (coal) or 50-60 zones (nuclear).
- Nuclear ($5,000, 2000 power) is 2.86x better power/dollar than coal ($3,000, 700 power) with no pollution.
- Monthly expenses = road + fire + police funding. Keep net cashflow positive.
- If runway_months < 12, cut spending or raise taxes immediately. Don't build anything expensive.
- Higher land value = more tax per capita. Parks and police investment increases tax revenue.

## Common Mistakes

- Placing zones without road access: no road = no growth, wasted money.
- Placing zones without power: unpowered zones don't develop. Verify power chain is conductive.
- Expecting roads to carry power (they DON'T — only wires, zone tiles, and road+wire crossings conduct).
- Spending too much in one turn: multiple expensive buildings crash the reward signal.
- Only building industrial: pollution becomes #1 problem, residential demand collapses.
- Ignoring System Updates: the game tells you exactly what it needs. Act on [CRITICAL] and [DEMAND] messages.
- Not setting objectives: without clear goals, actions become scattered and inefficient.
- Building more road than needed: each zone only needs ONE tile of road touching it.

## Map Reading Tips

- Sectors with empty_pct > 80 and dominant type "empty" (not "water") are ideal building locations.
- unpowered_zones count in sectors indicates power connectivity problems.
- Water-dominated sectors should be avoided. Look for large contiguous land masses.
- Trees auto-bulldoze when placing buildings (if auto-bulldoze is on), but water never does.
- Use inspect_area with radius 3-5 to verify specific build sites before placing.

## Current Best Strategy

1. Place a nuclear power plant in a corner of the map ($5,000, 2000 power, no pollution)
2. Set initial objectives with set_objectives
3. Build industrial zones at the map edge adjacent to the plant (pollution spills off edge)
4. Run a wire from plant toward map center, then start residential zone strips
5. Build two-zone strips: two rows of 3x3 zones touching each other, one road row between strips
6. Zones touching each other propagate power — only wire across road gaps
7. Check demand valves: build what's needed. Residential first, then match job demand
8. Commercial zones as buffer between industrial edge and residential center
9. Place parks near residential for land value boost (improves growth, tax revenue, and score)
10. Police station once crime becomes a top problem (~every 15 tiles of developed area)
11. Build Stadium when resPop approaches 500, Seaport when indPop approaches 70, Airport when comPop approaches 100
12. React immediately to [CRITICAL] System Updates
