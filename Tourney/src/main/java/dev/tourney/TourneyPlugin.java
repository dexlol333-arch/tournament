package dev.tourney;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class TourneyPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Set<UUID> sendToSpawn = new HashSet<>();
    private File arenaFile;
    private Tournament tournament;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        arenaFile = new File(getDataFolder(), "arenas.yml");
        loadArenas();
        Bukkit.getPluginManager().registerEvents(new TourneyListener(this), this);
        var cmd = getCommand("tourney");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (tournament != null) tournament.shutdown();
    }

    // ------------------------------------------------------------------ accessors

    public Tournament getTournament() { return tournament; }
    public void clearTournament() { tournament = null; }
    public Set<UUID> getSendToSpawn() { return sendToSpawn; }

    public Location getLobby() {
        Location l = getConfig().getLocation("lobby");
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public Arena pickFreeArena() {
        List<Arena> free = new ArrayList<>();
        for (Arena a : arenas.values()) if (a.isReady() && !a.isBusy()) free.add(a);
        if (free.isEmpty()) return null;
        return free.get(new Random().nextInt(free.size()));
    }

    private int readyArenas() {
        int n = 0;
        for (Arena a : arenas.values()) if (a.isReady()) n++;
        return n;
    }

    // ------------------------------------------------------------------ storage

    private void loadArenas() {
        arenas.clear();
        if (!arenaFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(arenaFile);
        ConfigurationSection sec = cfg.getConfigurationSection("arenas");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            Arena a = new Arena(name);
            a.setSpawn1(sec.getLocation(name + ".spawn1"));
            a.setSpawn2(sec.getLocation(name + ".spawn2"));
            a.setPos1(sec.getLocation(name + ".pos1"));
            a.setPos2(sec.getLocation(name + ".pos2"));
            arenas.put(name.toLowerCase(Locale.ROOT), a);
        }
    }

    private void saveArenas() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            String p = "arenas." + a.getName();
            cfg.set(p + ".spawn1", a.getSpawn1());
            cfg.set(p + ".spawn2", a.getSpawn2());
            cfg.set(p + ".pos1", a.getPos1());
            cfg.set(p + ".pos2", a.getPos2());
        }
        try {
            cfg.save(arenaFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save arenas.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ messaging

    private void ok(CommandSender s, String text) { s.sendMessage(Component.text(text, NamedTextColor.GREEN)); }
    private void err(CommandSender s, String text) { s.sendMessage(Component.text(text, NamedTextColor.RED)); }
    private void info(CommandSender s, String text) { s.sendMessage(Component.text(text, NamedTextColor.YELLOW)); }

    private void help(CommandSender s) {
        info(s, "Tourney commands:");
        s.sendMessage(Component.text("/tourney join | leave", NamedTextColor.GRAY));
        if (s.hasPermission("tourney.admin")) {
            s.sendMessage(Component.text("/tourney create|delete <arena>", NamedTextColor.GRAY));
            s.sendMessage(Component.text("/tourney setspawn1|setspawn2|setpos1|setpos2 <arena>", NamedTextColor.GRAY));
            s.sendMessage(Component.text("/tourney setlobby  (where losers/waiting players go)", NamedTextColor.GRAY));
            s.sendMessage(Component.text("/tourney list | start [seconds] | begin | cancel", NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) { help(s); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> join(s);
            case "leave" -> leave(s);
            default -> {
                if (!s.hasPermission("tourney.admin")) { err(s, "You don't have permission."); return true; }
                admin(s, sub, a);
            }
        }
        return true;
    }

    private void join(CommandSender s) {
        if (!(s instanceof Player p)) { err(s, "Players only."); return; }
        if (!p.hasPermission("tourney.join")) { err(s, "You don't have permission."); return; }
        if (tournament == null || tournament.getState() != Tournament.State.REGISTRATION) {
            err(s, "There is no tournament open for joining right now.");
            return;
        }
        if (tournament.isParticipant(p.getUniqueId())) { err(s, "You already joined!"); return; }
        tournament.register(p);
    }

    private void leave(CommandSender s) {
        if (!(s instanceof Player p)) { err(s, "Players only."); return; }
        if (tournament == null || !tournament.isParticipant(p.getUniqueId())) { err(s, "You're not in a tournament."); return; }
        tournament.forfeit(p.getUniqueId(), false);
    }

    private void admin(CommandSender s, String sub, String[] a) {
        switch (sub) {
            case "list" -> {
                if (arenas.isEmpty()) { info(s, "No arenas yet. Use /tourney create <name>"); return; }
                for (Arena ar : arenas.values()) {
                    String status = ar.isReady() ? "ready" : "missing: " + ar.missing();
                    s.sendMessage(Component.text(ar.getName() + " - " + status,
                            ar.isReady() ? NamedTextColor.GREEN : NamedTextColor.RED));
                }
            }
            case "create" -> {
                if (a.length < 2) { err(s, "Usage: /tourney create <arena>"); return; }
                String key = a[1].toLowerCase(Locale.ROOT);
                if (arenas.containsKey(key)) { err(s, "That arena already exists."); return; }
                arenas.put(key, new Arena(a[1]));
                saveArenas();
                ok(s, "Arena '" + a[1] + "' created. Now set spawn1, spawn2, pos1 and pos2.");
            }
            case "delete" -> {
                if (a.length < 2) { err(s, "Usage: /tourney delete <arena>"); return; }
                Arena ar = arenas.get(a[1].toLowerCase(Locale.ROOT));
                if (ar == null) { err(s, "No such arena."); return; }
                if (ar.isBusy()) { err(s, "That arena is in use right now."); return; }
                arenas.remove(a[1].toLowerCase(Locale.ROOT));
                saveArenas();
                ok(s, "Arena deleted.");
            }
            case "setspawn1", "setspawn2", "setpos1", "setpos2" -> {
                if (!(s instanceof Player p)) { err(s, "Players only."); return; }
                if (a.length < 2) { err(s, "Usage: /tourney " + sub + " <arena>"); return; }
                Arena ar = arenas.get(a[1].toLowerCase(Locale.ROOT));
                if (ar == null) { err(s, "No such arena. Create it first with /tourney create " + a[1]); return; }
                Location l = p.getLocation();
                switch (sub) {
                    case "setspawn1" -> ar.setSpawn1(l);
                    case "setspawn2" -> ar.setSpawn2(l);
                    case "setpos1" -> ar.setPos1(l);
                    default -> ar.setPos2(l);
                }
                saveArenas();
                ok(s, sub.substring(3) + " set for '" + ar.getName() + "'." + (ar.isReady() ? " Arena is READY!" : " Still missing: " + ar.missing()));
            }
            case "setlobby" -> {
                if (!(s instanceof Player p)) { err(s, "Players only."); return; }
                getConfig().set("lobby", p.getLocation());
                saveConfig();
                ok(s, "Tournament spawn set. Losers and waiting players are sent here.");
            }
            case "start" -> {
                if (tournament != null) { err(s, "A tournament is already running."); return; }
                if (readyArenas() == 0) { err(s, "You need at least one READY arena. Use /tourney list."); return; }
                int secs = getConfig().getInt("registration-seconds", 60);
                if (a.length > 1) {
                    try { secs = Math.max(5, Integer.parseInt(a[1])); }
                    catch (NumberFormatException ex) { err(s, "Seconds must be a number."); return; }
                }
                tournament = new Tournament(this);
                tournament.openRegistration(secs);
            }
            case "begin" -> {
                if (tournament == null || tournament.getState() != Tournament.State.REGISTRATION) { err(s, "No tournament in registration."); return; }
                tournament.forceBegin();
            }
            case "cancel" -> {
                if (tournament == null) { err(s, "No tournament is running."); return; }
                tournament.cancelAll("The tournament was cancelled by staff.");
            }
            default -> help(s);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("join", "leave"));
            if (s.hasPermission("tourney.admin"))
                out.addAll(List.of("create", "delete", "list", "setspawn1", "setspawn2", "setpos1", "setpos2", "setlobby", "start", "begin", "cancel"));
        } else if (a.length == 2 && s.hasPermission("tourney.admin")) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (List.of("delete", "setspawn1", "setspawn2", "setpos1", "setpos2").contains(sub))
                for (Arena ar : arenas.values()) out.add(ar.getName());
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
