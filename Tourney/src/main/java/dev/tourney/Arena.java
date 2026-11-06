package dev.tourney;

import org.bukkit.Location;

public class Arena {

    private final String name;
    private Location spawn1, spawn2, pos1, pos2;
    private boolean busy;

    public Arena(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public Location getSpawn1() { return spawn1; }
    public Location getSpawn2() { return spawn2; }
    public Location getPos1() { return pos1; }
    public Location getPos2() { return pos2; }
    public void setSpawn1(Location l) { this.spawn1 = l; }
    public void setSpawn2(Location l) { this.spawn2 = l; }
    public void setPos1(Location l) { this.pos1 = l; }
    public void setPos2(Location l) { this.pos2 = l; }
    public boolean isBusy() { return busy; }
    public void setBusy(boolean busy) { this.busy = busy; }

    /** An arena needs both spawns and both border corners (same world). */
    public boolean isReady() {
        return spawn1 != null && spawn2 != null && pos1 != null && pos2 != null
                && pos1.getWorld() != null && pos1.getWorld().equals(pos2.getWorld());
    }

    public String missing() {
        StringBuilder sb = new StringBuilder();
        if (spawn1 == null) sb.append("spawn1 ");
        if (spawn2 == null) sb.append("spawn2 ");
        if (pos1 == null) sb.append("pos1 ");
        if (pos2 == null) sb.append("pos2 ");
        if (pos1 != null && pos2 != null && !pos1.getWorld().equals(pos2.getWorld())) sb.append("(pos1/pos2 in different worlds)");
        return sb.toString().trim();
    }

    /** True if the location is inside the arena border box. */
    public boolean contains(Location l) {
        if (!isReady() || l.getWorld() == null || !l.getWorld().equals(pos1.getWorld())) return false;
        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX()) + 1;
        int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY()) + 1;
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ()) + 1;
        return l.getX() >= minX && l.getX() < maxX
                && l.getY() >= minY && l.getY() < maxY
                && l.getZ() >= minZ && l.getZ() < maxZ;
    }
}
