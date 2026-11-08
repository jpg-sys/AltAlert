package dev.altalert;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AltAlert extends JavaPlugin implements Listener, TabExecutor {

    private record Owner(UUID uuid, String name) {}

    private final Map<String, Owner> ipOwners = new ConcurrentHashMap<>();
    private final Set<String> allowed = ConcurrentHashMap.newKeySet();
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadAll();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("aa");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
    }

    private void loadAll() {
        allowed.clear();
        for (String s : getConfig().getStringList("allowed")) allowed.add(s.toLowerCase(Locale.ROOT));
        ipOwners.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        var sec = y.getConfigurationSection("ips");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(Objects.requireNonNull(sec.getString(key + ".uuid")));
                ipOwners.put(key, new Owner(id, sec.getString(key + ".name", "unknown")));
            } catch (Exception ignored) {}
        }
    }

    private static String ipKey(String ip) { return ip.replace('.', '_').replace(':', '-'); }

    private synchronized void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        ipOwners.forEach((k, o) -> { y.set("ips." + k + ".uuid", o.uuid().toString()); y.set("ips." + k + ".name", o.name()); });
        try { y.save(dataFile); } catch (IOException e) { getLogger().warning("Could not save data.yml: " + e.getMessage()); }
    }

    private synchronized void saveAllowed() {
        getConfig().set("allowed", new ArrayList<>(new TreeSet<>(allowed)));
        saveConfig();
    }

    // Runs async, so only thread-safe collections are touched here (Folia-safe)
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String name = e.getName();
        if (allowed.contains(name.toLowerCase(Locale.ROOT))) return;

        String key = ipKey(e.getAddress().getHostAddress());
        Owner existing = ipOwners.putIfAbsent(key, new Owner(e.getUniqueId(), name));
        if (existing == null) {
            getServer().getAsyncScheduler().runNow(this, t -> saveData());
            return;
        }
        if (existing.uuid().equals(e.getUniqueId())) {
            if (!existing.name().equals(name)) {
                ipOwners.put(key, new Owner(e.getUniqueId(), name));
                getServer().getAsyncScheduler().runNow(this, t -> saveData());
            }
            return;
        }
        Component msg = MiniMessage.miniMessage().deserialize(getConfig().getString("kick-message", "Alt accounts are not allowed."));
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, msg);
        getLogger().info(name + " was denied: IP already used by " + existing.name());
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1 && a[0].equalsIgnoreCase("list")) {
            s.sendMessage(Component.text("Exempt players: " + (allowed.isEmpty() ? "none" : String.join(", ", new TreeSet<>(allowed)))));
            return true;
        }
        if (a.length != 2) { s.sendMessage(Component.text("Usage: /aa <add|remove> <username> | /aa list")); return true; }
        String user = a[1].toLowerCase(Locale.ROOT);
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (allowed.add(user)) { saveAllowed(); s.sendMessage(Component.text("Added " + a[1] + " to the exempt list.")); }
                else s.sendMessage(Component.text(a[1] + " is already exempt."));
            }
            case "remove" -> {
                if (allowed.remove(user)) { saveAllowed(); s.sendMessage(Component.text("Removed " + a[1] + " from the exempt list.")); }
                else s.sendMessage(Component.text(a[1] + " is not on the exempt list."));
            }
            default -> s.sendMessage(Component.text("Usage: /aa <add|remove> <username> | /aa list"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("add", "remove", "list"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("remove")) out.addAll(new TreeSet<>(allowed));
        else if (a.length == 2 && a[0].equalsIgnoreCase("add")) getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
        String pre = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(pre));
        return out;
    }
}
