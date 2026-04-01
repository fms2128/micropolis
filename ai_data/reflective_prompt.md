ALWAYS stabilize score before expanding when delta_score is negative for 2+ turns or recent 3-turn reward average is below 0.

ALWAYS fix every unpowered zone/building before placing new zones or services. If unpowered_zones > 0, your first diagnostics must be get_city_entities, then render_map around the affected area only if the connection path is unclear.

NEVER place a police station, fire station, or new zone unless you can also afford the roads/wires needed to make it powered and road-connected in the same turn.

WHEN funds are below 3000, prefer the cheapest high-certainty actions only: bulldoze rubble, add short power links, add missing road tiles, and rebalance budget/tax. Avoid new major services or speculative expansion.

WHEN road_percent is below 80 and roads are not a top problem, do NOT rush to fully fund roads. Keep road funding around 70-80% to preserve cash unless repeated road warnings appear.

WHEN crime is above 70 and you already have at least one police station funded at 100%, first improve police coverage by placing the next station closer to the main residential/commercial cluster; do not add more than one new police station within 10 turns unless crime remains above 80.

WHEN HOUSING is the top problem and residential demand is strongly positive (>1000), add low-cost residential capacity first, then 1-2 commercial zones, while maintaining approximate R:C:I expansion near 2:1:1. Do not overbuild industrial if pollution is already above 50.

ALWAYS treat disaster recovery in this order: clear rubble -> restore power links -> restore road access -> only then resume expansion.

IF score falls while population rises, prioritize fixing citizen problems (housing, crime, taxes, pollution) instead of chasing raw growth.

WHEN funds are tight and taxes are listed as a top-3 problem, avoid raising tax further; solve infrastructure and service issues first.

WHEN funds are below 1000, DO NOT spend on speculative zoning unless the zone can use existing road access and likely existing power immediately; preserve at least $300 cash buffer after any action.

WHEN recent 3-turn reward average is near zero or negative but population is still growing, STOP micro-expanding every turn. Prefer diagnosis and one targeted fix for the highest-severity score problem instead of adding more zones.

WHEN CRIME is the top problem and average crime is above 70, make police coverage your next medium-cost investment once funds reach at least 600-800 above the cost of placement plus any required roads/wires. Place the station near the densest residential/commercial area, not near industry or empty land.

IF approval is at least 70 and TAXES appears as a top-3 problem, lower tax by 1 point when funds are positive and annual net remains positive. Prefer 3% tax over 4% in this situation.

NEVER let objective lists drift into repeated tornado/rubble rechecks after multiple confirmations unless new disaster evidence appears in current diagnostics or System Updates.

WHEN road funding is already 70% and traffic is very low (<10), do NOT spend turns or cash on road expansion/funding changes unless roads become a top problem or zones lack road access.

IF there is no hospital and need_hospital = 1, treat it as low priority while funds are below 2000; solve crime, tax pressure, and core zoning/connectivity first.

WHEN funds are below 500, NEVER choose an objective whose main action is "preserve cash" or "wait". Set one concrete, actionable objective that addresses the highest score problem with the best cost/impact ratio.

IF CRIME remains the #1 problem for 5+ turns and average crime is still above 70, make the next major purchase a second police station as soon as cash is sufficient. Do not keep spending on small zones first once this trigger is met.

WHEN placing a new police station for crime control, put it adjacent to the densest residential/commercial cluster and connected with the minimum required road/power. Avoid remote coverage.

IF approval is 70+ and taxes are a top-3 problem, lower tax from 4% to 3% at the next safe opportunity even if cash is tight, provided annual net income remains positive. Do this before adding optional new zones.

WHEN score is flat for 3+ turns while population grows, STOP prioritizing growth objectives. Replace them with score-repair objectives focused on the top 1-2 citizen problems.

DO NOT keep adding low-cost residential/commercial zones when HOUSING is not the top problem and CRIME or TRAFFIC are more severe. Fix the higher-severity score drag first.

WHEN traffic is a top-2 problem and road_count is already substantial, prefer diagnosing road access/layout before building more roads. Only add roads that directly serve unconnected or newly zoned areas.

IF road_effect is below 30 but traffic average is only moderate (<=50), do not panic-build roads. Maintain funding discipline and fix the more severe problem first.

WHEN approval is below 65 and TAXES is a top-3 problem, lower tax from 4% to 3% if annual net income remains positive. Do this before optional zoning or road work.

IF recent reward history contains 3 consecutive large negatives (worse than -20) after a period of small positive rewards, STOP expansion immediately for at least 3 turns. Spend only on repairs, budget/tax adjustment, and one high-impact service fix.

DO NOT keep a second-police-station objective active when funds are below the likely full placement threshold. First save cash to at least 2500-3000, then buy once, instead of spending on small distractions.

WHEN CRIME is the top problem, population is falling, and you have exactly 1 police station, treat a second police station as the next major purchase unless a power/disaster issue exists.

IF HOUSING is listed as a top problem but residential demand is already maxed and population is shrinking, interpret this as city quality/coverage weakness, not a signal to spam new residential zones. Fix crime/tax/service access first.

WHEN score is flat or falling and approval is under 60, freeze all nonessential zoning until one of these improves: crime falls, taxes are reduced, or score rises.

NEVER spend scarce funds on repeated verification objectives once a problem has been confirmed solved. Replace stale objectives with the current highest-impact unresolved score problem immediately.