# Agent Memory - Micropolis AI Cheatsheet

> This is your long-term memory. It persists across sessions and games.
> Use it to record what works, what doesn't, and refine your strategy over time.
> The reward signal tells you HOW you're doing. This file tells you WHY and WHAT TO DO ABOUT IT.

## Strategies That Work

- Always check demand valves FIRST each turn. Build what the city actually needs (positive valve = build more).
- Residential first after power plant: citizens need homes before anything else grows. Residential zones generate population which drives tax income.
- Cheap zones ($100) before expensive infrastructure ($3000+) when funds are limited. One zone at a time, verify it works, then expand.
- Parks next to residential zones boost land value and citizen happiness, leading to denser growth.
- Adding a residential zone when residential demand is maxed produces large positive reward through both score and population growth.
- When industrial demand is maxed, a single cheap industrial zone can still yield positive reward even before score improves.
- After a negative reward from expensive purchases, switching to cheap single-zone placements recovers the reward signal quickly.
- When housing demand is maxed but new zones keep failing or staying unpowered, adding a few cheap parks beside an already occupied residential block can be a low-risk score lever and land-value boost.
- Turn 44: when repeated new residential zones were stalling due to false power pockets, placing a few parks on verified empty tiles directly beside an existing residential block was a very cheap alternative action ($30 total) and avoided another $100 failed-growth zone gamble.
- Turn 49: the east cluster accepted a residential zone at (94,19) immediately west of the previously unpowered industrial test, with road on y=22 and wires tied into the existing trunk on y=24. This shifted footprint may be a viable housing infill spot even though the adjacent industrial tile at (97,19) stayed unpowered.

## Strategies That Don't Work

- Buying additional coal plants ($3000) as a "quick fix" for power problems when budget is tight. The cash hit dominates the reward signal. Diagnose wiring issues first.
- Industrial spam without prior residential zones: pollution skyrockets, residential demand goes deeply negative, score stalls.
- Placing zones in areas not verified to have actual power. Aggregate "powered_zones" count can be misleading - always verify with query_zone.
- Placing residential zones directly next to industrial zones or power plants: pollution tanks land value, growth stalls, citizens complain.
- Expanding a cluster where query_zone repeatedly reports zones as unpowered. Stop, fix power, then expand.
- Lowering taxes to 3-4% too early when there's no tax income yet. Keep at 5-7% until there's meaningful population.
- When multiple consecutive turns show small negative reward and zero score/population change, blindly extending the same residential strip can stall even with max residential demand. After 2-3 flat turns, pivot to a new clean area or another score-improving lever instead of repeating the pattern.
- Turn 35-37: extending the existing residential band near x=31-39,y=21-26 produced repeated zero score/pop growth while query_zone kept reporting unpowered/slum conditions despite aggregate powered counts showing zero unpowered. After 2+ flat turns, abandon that band and pivot elsewhere.
- Turn 37-38: a second residential pocket near x=12-21,y=24-30 also stayed unpowered/slum and produced no growth despite visible nearby wires and roads. Don't keep extending coastal slum bands when query_zone remains unpowered.
- Turn 40: the industrial test at (15,28) also remained unpowered and gave negative reward. Stop investing in the x=12-21,y=24-30 cluster entirely.
- Turn 43: placing residential at (18,28) in the x=18-24,y=28-34 pocket succeeded next to existing road/power stubs, but query_zone still reported it unpowered. Avoid further spending in this inland-looking strip unless a later turn proves the local grid actually energizes zones.
- Turn 45: even parks placed directly beside a dense residential block did not move score or population on the next tick; parks are fine as cheap filler but should not be the primary action when max housing demand is unmet.
- Turn 46: after another negative reward with zero score/pop change, continuing to spend in the known suspect x=21-34,y=25-30 residential pockets is confirmed bad. Pivot away from stalled unpowered/slum clusters instead of adding more housing or parks there.
- Turn 48: a new industrial test at (97,19) with adjacent road and wire still queried unpowered next turn and produced negative reward. Do not keep investing in the east tree pocket around x=97-100,y=19-24 unless a later turn proves actual powered growth.
- Turn 50: adding another residential zone in the east pocket at (94,19) immediately produced negative reward and still queried unpowered next turn. Do not continue investing around x=94-99,y=19-24 unless powered growth is later confirmed.

## Placement Rules Learned

- Always use query_zone to verify a zone is actually powered. Do NOT trust aggregate powered/unpowered counts alone.
- Build roads and power lines ADJACENT to the zone footprint, not through the 3x3 area. Building through it blocks zone placement.
- find_empty_area can suggest spots surrounded by water. Always inspect_area the result before committing to verify the land is actually accessible.
- Water permanently blocks placement and breaks power line connections. Power lines over water cost more and may fail to connect.
- x,y coordinates for placement are the TOP-LEFT corner of the building footprint.
- Power propagates through adjacent powered zones - you don't always need explicit power lines if zones are touching.
- Turn 40: tried to place residential at (22,24) after extending access, but failed because the newly/previously routed power line occupied the 3x3 footprint. In the x=22-24,y=24-28 pocket, keep wires on y=26 or roads on y=27 only if the zone footprint is elsewhere; always re-check exact footprint before zoning.

## Financial Management

- Start with taxes at 7%. Lower to 5% once population is growing steadily. Only go below 5% when tax income comfortably covers expenses.
- Road funding at 50-80% is fine in the early game when the road network is small. Save full funding for larger networks.
- One coal plant ($3000) supports roughly 20-25 zones. Don't buy a second plant until the first is near capacity.
- Monthly expenses = road + fire + police funding. Monthly income = tax revenue. Keep net cashflow positive.
- If runway_months < 12, immediately cut spending or raise taxes. Don't build anything expensive.

## Common Mistakes

- Placing zones without road access: zones need adjacent road to grow. No road = no growth, wasted money.
- Placing zones without power: unpowered zones don't develop. Always connect to power grid and verify.
- Spending too much at once: buying multiple power plants, big buildings, or many zones in one turn crashes the reward signal.
- Only building industrial: pollution becomes the #1 problem, residential demand collapses, score stalls.
- Ignoring System Updates: the game tells you exactly what it needs. Read and act on [CRITICAL] and [DEMAND] messages.
- Not setting objectives: without clear goals, actions become scattered and inefficient.

## Map Reading Tips

- Sectors with empty_pct > 80 and dominant type "empty" (not "water") are ideal building locations.
- unpowered_zones count in sectors indicates power connectivity problems in that area.
- Water-dominated sectors should be avoided for building. Look for large contiguous land masses.
- Trees auto-bulldoze when placing buildings (if auto-bulldoze is on), but water never does.
- Use inspect_area with a small radius (3-5) to verify specific build sites before placing.

## Current Best Strategy

1. Place a coal power plant (find_empty_area 4x4, prefer inland location away from water)
2. Set initial objectives with set_objectives
3. Place 2-3 residential zones on clean land NEAR (but not on) the power plant
4. Build roads adjacent to all zones
5. Connect power lines from plant to zones
6. Verify power with query_zone on each new zone
7. Check demand: build what's needed (residential first, then industrial FAR from residential)
8. Keep industrial 10+ tiles away from residential to avoid pollution damage
9. Add police/fire stations once population justifies the maintenance cost
10. Review and update objectives every few turns
11. React immediately to [CRITICAL] System Updates
12. Update this memory when you confirm new strategies over multiple turns
