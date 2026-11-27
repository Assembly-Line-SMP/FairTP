# FairTP
Simple /spawn and /rtp.

Includes integration with Open Parties and Claims to teleport players to random, unclaimed chunks.

`/rtp` (also `/randomtp`) searches in the player's current dimension without blocking the
server while chunks generate. It tries up to 32 chunks, checking multiple positions in
each, and reports failure if it cannot find safe ground. Only one search per player can
run at a time. The search stops if the player is no longer alive, connected, and in the
original dimension when the destination is checked.

The common config's `teleport_radius` bounds the square search area around the dimension's
shared spawn, and `deadzone_radius` excludes locations closer than that distance to spawn.
The teleport radius must be larger than the deadzone radius. Neither value is changed by
the search.