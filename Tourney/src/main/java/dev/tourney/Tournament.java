package dev.tourney;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

public class Tournament {

    public enum State { REGISTRATION, RUNNING, ENDED }

    public static class Match {
        private final UUID a, b;
        private Arena arena;
        private boolean fighting;
        private BukkitTask countdownTask;

        Match(UUID a, UUID b) { this.a = a; this.b = b; }

        public boolean has(UUID u) { return a.equals(u) || b.equals(u); }
        public UUID other(UUID u) { return a.equals(u) ? b : a; }
        public boolean isFighting() { return fighting; }
        public Arena getArena() { return arena; }
        public Location spawnOf(UUID u) { return a.equals(u) ? arena.getSpawn1() : arena.getSpawn2(); }
    }

    private final TourneyPlugin plugin;
    private State state = State.REGISTRATION;
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Deque<Match> queue = new ArrayDeque<>();
    private final List<Match> active = new ArrayList<>();
    private int round = 0;
    private int matchesRemaining = 0;
    private int secondsLeft;
    private BukkitTask regTask;

    public Tournament(TourneyPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ queries

    public State getState() { return state; }
    public boolean isParticipant(UUID u) { return participants.contains(u); }
    public int getParticipantCount() { return participants.size(); }

    public Match getMatch(UUID u) {
        for (Match m : active) if (m.has(u)) return m;
        return null;
    }

    // ------------------------------------------------------------------ registration

    public void openRegistration(int seconds) {
        state = State.REGISTRATION;
        secondsLeft = seconds;
        broadcastInvite();
        regTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            secondsLeft--;
            if (secondsLeft <= 0) {
                cancelRegTask();
                begin();
                return;
            }
            if (secondsLeft == 30 || secondsLeft == 10 || secondsLeft == 5) broadcastInvite();
        }, 20L, 20L);
    }

    private void broadcastInvite() {
        Component join = Component.text("[CLICK HERE TO JOIN]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/tourney join"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to enter the tournament!", NamedTextColor.YELLOW)));
        Bukkit.broadcast(Component.empty());
        Bukkit.broadcast(Component.text("A TOURNAMENT IS STARTING! ", NamedTextColor.GOLD, TextDecoration.BOLD).append(join));
        Bukkit.broadcast(Component.text("Starts in " + secondsLeft + "s  |  " + participants.size() + " player(s) joined", NamedTextColor.GRAY));
        Bukkit.broadcast(Component.empty());
    }

    public void register(Player p) {
        participants.add(p.getUniqueId());
        names.put(p.getUniqueId(), p.getName());
        prepare(p);
        p.teleport(plugin.getLobby());
        p.sendMessage(Component.text("You joined the tournament! Wait for it to begin.", NamedTextColor.GREEN));
        Bukkit.broadcast(Component.text(p.getName(), NamedTextColor.GREEN)
                .append(Component.text(" joined the tournament (" + participants.size() + ")", NamedTextColor.GRAY)));
    }

    /** Skip the remaining registration time. */
    public void forceBegin() {
        if (state != State.REGISTRATION) return;
        cancelRegTask();
        begin();
    }

    private void begin() {
        if (participants.size() < plugin.getConfig().getInt("min-players", 2)) {
            Bukkit.broadcast(Component.text("Tournament cancelled: not enough players joined.", NamedTextColor.RED));
            cancelAll(null);
            return;
        }
        state = State.RUNNING;
        Bukkit.broadcast(Component.text("Registration closed! " + participants.size() + " players are competing. Good luck!", NamedTextColor.GOLD));
        Bukkit.getScheduler().runTaskLater(plugin, this::nextRound, 40L);
    }

    // ------------------------------------------------------------------ rounds

    private void nextRound() {
        if (state != State.RUNNING) return;
        if (participants.size() <= 1) { finish(); return; }

        round++;
        List<UUID> pool = new ArrayList<>(participants);
        Collections.shuffle(pool);
        boolean finals = pool.size() == 2;
        String label = finals ? "FINALS" : "Round " + round;

        UUID bye = null;
        if (pool.size() % 2 == 1) bye = pool.remove(pool.size() - 1);

        queue.clear();
        for (int i = 0; i < pool.size(); i += 2) queue.add(new Match(pool.get(i), pool.get(i + 1)));
        matchesRemaining = queue.size();

        Bukkit.broadcast(Component.empty());
        for (Match m : queue) {
            Bukkit.broadcast(Component.text(label + " ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text("» ", NamedTextColor.GRAY))
                    .append(nameOf(m.a))
                    .append(Component.text(" vs ", NamedTextColor.GRAY))
                    .append(nameOf(m.b)));
            showTitle(m.a, Component.text(label, NamedTextColor.GOLD), Component.text("vs " + nm(m.b), NamedTextColor.GREEN));
            showTitle(m.b, Component.text(label, NamedTextColor.GOLD), Component.text("vs " + nm(m.a), NamedTextColor.GREEN));
        }
        if (bye != null) {
            Bukkit.broadcast(nameOf(bye).append(Component.text(" has a bye and advances automatically.", NamedTextColor.GRAY)));
            Player bp = Bukkit.getPlayer(bye);
            if (bp != null) bp.sendMessage(Component.text("You got a bye! Wait for the next round...", NamedTextColor.GREEN));
        }
        Bukkit.broadcast(Component.empty());

        long delay = plugin.getConfig().getInt("round-delay-seconds", 5) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, this::tryStartMatches, delay);
    }

    private void tryStartMatches() {
        if (state != State.RUNNING) return;
        while (!queue.isEmpty()) {
            Arena arena = plugin.pickFreeArena();
            if (arena == null) break; // wait for an arena to free up
            startMatch(queue.poll(), arena);
        }
    }

    private void startMatch(Match m, Arena arena) {
        Player pa = Bukkit.getPlayer(m.a);
        Player pb = Bukkit.getPlayer(m.b);
        if (pa == null || pb == null) {
            resolveQueuedForfeit(m, pa == null ? m.a : m.b);
            return;
        }
        m.arena = arena;
        arena.setBusy(true);
        active.add(m);

        prepare(pa);
        prepare(pb);
        pa.teleport(arena.getSpawn1());
        pb.teleport(arena.getSpawn2());

        final int[] t = {Math.max(1, plugin.getConfig().getInt("countdown-seconds", 5))};
        m.countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (t[0] <= 0) {
                m.fighting = true;
                m.countdownTask.cancel();
                Title go = Title.title(Component.text("FIGHT!", NamedTextColor.RED, TextDecoration.BOLD), Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(200)));
                Player a = Bukkit.getPlayer(m.a), b = Bukkit.getPlayer(m.b);
                if (a != null) a.showTitle(go);
                if (b != null) b.showTitle(go);
                return;
            }
            Title tt = Title.title(Component.text(String.valueOf(t[0]), NamedTextColor.YELLOW, TextDecoration.BOLD),
                    Component.text(nm(m.a) + " vs " + nm(m.b), NamedTextColor.GRAY),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO));
            Player a = Bukkit.getPlayer(m.a), b = Bukkit.getPlayer(m.b);
            if (a != null) a.showTitle(tt);
            if (b != null) b.showTitle(tt);
            t[0]--;
        }, 0L, 20L);
    }

    /** Called when a fighter dies, quits, or leaves. */
    public void endMatch(Match m, UUID winner, UUID loser, boolean loserQuit) {
        if (state != State.RUNNING || !active.remove(m)) return;
        if (m.countdownTask != null) m.countdownTask.cancel();
        m.fighting = false;
        if (m.arena != null) m.arena.setBusy(false);

        disqualify(loser, loserQuit);

        Bukkit.broadcast(nameOf(winner).append(Component.text(" defeated ", NamedTextColor.GRAY))
                .append(Component.text(nm(loser), NamedTextColor.RED)));

        Player w = Bukkit.getPlayer(winner);
        if (w != null) {
            if (w.isDead()) plugin.getSendToSpawn().add(winner);
            else {
                prepare(w);
                w.teleport(plugin.getLobby());
                w.sendMessage(Component.text("You won! Waiting for the next player...", NamedTextColor.GREEN));
            }
        }
        matchesRemaining--;
        afterMatch();
    }

    private void afterMatch() {
        if (state != State.RUNNING) return;
        if (participants.size() <= 1) { finish(); return; }
        if (matchesRemaining <= 0) {
            long delay = plugin.getConfig().getInt("round-delay-seconds", 5) * 20L;
            Bukkit.getScheduler().runTaskLater(plugin, this::nextRound, delay);
        } else {
            tryStartMatches();
        }
    }

    private void resolveQueuedForfeit(Match m, UUID gone) {
        disqualify(gone, true);
        matchesRemaining--;
        UUID other = m.other(gone);
        Player op = Bukkit.getPlayer(other);
        if (op != null) op.sendMessage(Component.text("Your opponent left. You advance! Waiting for the next round...", NamedTextColor.GREEN));
        afterMatch();
    }

    // ------------------------------------------------------------------ leaving / disqualification

    /** Player used /tourney leave or disconnected. */
    public void forfeit(UUID u, boolean quitting) {
        if (!participants.contains(u)) return;

        if (state == State.REGISTRATION) {
            participants.remove(u);
            Bukkit.broadcast(Component.text(nm(u) + " left the tournament (" + participants.size() + ")", NamedTextColor.GRAY));
            return;
        }
        if (state != State.RUNNING) return;

        Match m = getMatch(u);
        if (m != null) { endMatch(m, m.other(u), u, quitting); return; }

        for (Iterator<Match> it = queue.iterator(); it.hasNext(); ) {
            Match q = it.next();
            if (q.has(u)) {
                it.remove();
                resolveQueuedForfeit(q, u);
                return;
            }
        }
        // waiting winner / bye player
        disqualify(u, quitting);
        if (participants.size() <= 1) finish();
    }

    private void disqualify(UUID u, boolean quitting) {
        participants.remove(u);
        Bukkit.broadcast(Component.text("[" + nm(u) + "] Disqualified", NamedTextColor.RED));
        Player p = Bukkit.getPlayer(u);
        if (p == null || quitting || p.isDead()) {
            plugin.getSendToSpawn().add(u);
        } else {
            prepare(p);
            p.teleport(plugin.getLobby());
            p.showTitle(Title.title(Component.text("DISQUALIFIED", NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text("You have been sent to spawn", NamedTextColor.GRAY)));
            p.sendMessage(Component.text("You have been disqualified from the tournament.", NamedTextColor.RED));
        }
    }

    // ------------------------------------------------------------------ ending

    private void finish() {
        state = State.ENDED;
        UUID champ = participants.stream().findFirst().orElse(null);
        Bukkit.broadcast(Component.empty());
        if (champ != null) {
            Bukkit.broadcast(Component.text("TOURNAMENT WINNER: ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(nameOf(champ).decorate(TextDecoration.BOLD))
                    .append(Component.text("  Congratulations!", NamedTextColor.YELLOW)));
            showTitle(champ, Component.text("YOU WON!", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Tournament champion", NamedTextColor.YELLOW));
            Player cp = Bukkit.getPlayer(champ);
            if (cp != null) {
                prepare(cp);
                cp.teleport(plugin.getLobby());
            }
        } else {
            Bukkit.broadcast(Component.text("The tournament ended with no winner.", NamedTextColor.GRAY));
        }
        Bukkit.broadcast(Component.empty());
        cleanup();
    }

    /** Staff cancel (reason shown if not null). */
    public void cancelAll(String reason) {
        if (reason != null) Bukkit.broadcast(Component.text(reason, NamedTextColor.RED));
        for (UUID u : new ArrayList<>(participants)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                prepare(p);
                p.teleport(plugin.getLobby());
            }
        }
        state = State.ENDED;
        cleanup();
    }

    public void shutdown() {
        cleanup();
    }

    private void cleanup() {
        cancelRegTask();
        for (Match m : active) {
            if (m.countdownTask != null) m.countdownTask.cancel();
            if (m.arena != null) m.arena.setBusy(false);
        }
        active.clear();
        queue.clear();
        plugin.clearTournament();
    }

    private void cancelRegTask() {
        if (regTask != null) {
            regTask.cancel();
            regTask = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("deprecation")
    private void prepare(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    private String nm(UUID u) { return names.getOrDefault(u, "Unknown"); }

    private Component nameOf(UUID u) { return Component.text(nm(u), NamedTextColor.GREEN); }

    private void showTitle(UUID u, Component main, Component sub) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) p.showTitle(Title.title(main, sub));
    }
}
