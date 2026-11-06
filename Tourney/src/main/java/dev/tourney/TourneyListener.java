package dev.tourney;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

import java.util.UUID;

public class TourneyListener implements Listener {

    private final TourneyPlugin plugin;

    public TourneyListener(TourneyPlugin plugin) {
        this.plugin = plugin;
    }

    private Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    // ------------------------------------------------------------------ combat

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Tournament t = plugin.getTournament();
        if (t == null) return;
        if (!(e.getEntity() instanceof Player p)) return;

        // Tournament players can't hurt outsiders
        if (!t.isParticipant(p.getUniqueId())) {
            if (e instanceof EntityDamageByEntityEvent ed) {
                Player atk = attackerOf(ed.getDamager());
                if (atk != null && t.isParticipant(atk.getUniqueId())) e.setCancelled(true);
            }
            return;
        }

        UUID id = p.getUniqueId();
        Tournament.Match m = t.getMatch(id);

        // Waiting in lobby / countdown: invulnerable (but don't fall forever into the void)
        if (m == null || !m.isFighting()) {
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID && m == null) p.teleport(plugin.getLobby());
            e.setCancelled(true);
            return;
        }

        // Only your opponent can hurt you
        if (e instanceof EntityDamageByEntityEvent ed) {
            Player atk = attackerOf(ed.getDamager());
            if (atk == null || !atk.getUniqueId().equals(m.other(id))) {
                e.setCancelled(true);
                return;
            }
        }

        // Lethal hit -> they lose, no death screen, inventory untouched (keep inventory)
        if (p.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            t.endMatch(m, m.other(id), id, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Tournament t = plugin.getTournament();
        Player p = e.getEntity();
        if (t == null || !t.isParticipant(p.getUniqueId())) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.deathMessage(null);
        plugin.getSendToSpawn().add(p.getUniqueId());
        Tournament.Match m = t.getMatch(p.getUniqueId());
        if (m != null) t.endMatch(m, m.other(p.getUniqueId()), p.getUniqueId(), false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (plugin.getSendToSpawn().remove(e.getPlayer().getUniqueId())) {
            e.setRespawnLocation(plugin.getLobby());
        }
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Tournament t = plugin.getTournament();
        if (t != null) t.forfeit(e.getPlayer().getUniqueId(), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (plugin.getSendToSpawn().remove(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> p.teleport(plugin.getLobby()), 2L);
        }
    }

    // ------------------------------------------------------------------ arena border / freeze

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Tournament t = plugin.getTournament();
        if (t == null) return;
        Player p = e.getPlayer();
        Tournament.Match m = t.getMatch(p.getUniqueId());
        if (m == null || e.getTo() == null) return;

        Location from = e.getFrom(), to = e.getTo();

        // frozen during countdown (can still look around)
        if (!m.isFighting()) {
            if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                e.setTo(new Location(to.getWorld(), from.getX(), to.getY(), from.getZ(), to.getYaw(), to.getPitch()));
            }
            return;
        }

        // arena border
        Arena arena = m.getArena();
        if (!arena.contains(to)) {
            if (arena.contains(from)) {
                e.setCancelled(true);
            } else {
                p.teleport(m.spawnOf(p.getUniqueId()));
            }
            p.sendActionBar(Component.text("You can't leave the arena!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Tournament t = plugin.getTournament();
        if (t == null) return;
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c != PlayerTeleportEvent.TeleportCause.ENDER_PEARL && c != PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) return;
        Tournament.Match m = t.getMatch(e.getPlayer().getUniqueId());
        if (m != null && e.getTo() != null && !m.getArena().contains(e.getTo())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ protection

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Tournament t = plugin.getTournament();
        if (t != null && t.isParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Tournament t = plugin.getTournament();
        if (t != null && t.isParticipant(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Tournament t = plugin.getTournament();
        Player p = e.getPlayer();
        if (t == null || t.getMatch(p.getUniqueId()) == null) return;
        if (p.hasPermission("tourney.admin")) return;
        String msg = e.getMessage().toLowerCase();
        if (msg.startsWith("/tourney") || msg.startsWith("/tournament") || msg.startsWith("/tourny")) return;
        e.setCancelled(true);
        p.sendMessage(Component.text("You can't use commands during a match. Use /tourney leave to forfeit.", NamedTextColor.RED));
    }
}
