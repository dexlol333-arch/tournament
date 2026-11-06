# Tourney

Automated 1v1 bracket tournaments for Paper 1.21+.

## Build
`mvn package` -> `target/Tourney.jar` (or push to GitHub, the workflow builds it).

## Set up an arena (do this for each arena)
1. `/tourney create arena1`
2. Stand at one corner of the fighting area: `/tourney setpos1 arena1`
3. Stand at the opposite corner (set it high enough to include jumping room): `/tourney setpos2 arena1`
4. Stand where player 1 starts: `/tourney setspawn1 arena1`
5. Stand where player 2 starts: `/tourney setspawn2 arena1`
6. `/tourney list` should show `ready`. Both spawns must be inside the pos1/pos2 box.
7. `/tourney setlobby` at your spawn - losers and waiting winners go here.

More ready arenas = more matches at the same time. Extra matches wait for a free arena.

## Run a tournament
`/tourney start [seconds]` broadcasts a clickable [CLICK HERE TO JOIN]. When time is up the bracket is drawn randomly.
`/tourney begin` skips the wait, `/tourney cancel` aborts.
Players: `/tourney join`, `/tourney leave` (leaving = disqualified).

Permissions: `tourney.admin` (op), `tourney.join` (everyone).
