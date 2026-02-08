package com.BreiwalkerSMP.Vaults;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class Vaults extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, Integer> currentPage = new HashMap<>();
    private final Map<UUID, UUID> adminViewing = new HashMap<>();
    private final Set<UUID> isSwitching = new HashSet<>();
    private File userDataFolder;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        userDataFolder = new File(getDataFolder(), "userdata");
        if (!userDataFolder.exists()) userDataFolder.mkdirs();

        getCommand("vault").setExecutor(this);
        getCommand("vault").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        try {
            new Metrics(this, 24652);
            getLogger().info("bStats enabled.");
        } catch (Exception ignored) {}

        getLogger().info("========================");
        getLogger().info("      Vaults 1.2        ");
        getLogger().info("     By Breiwalker      ");
        getLogger().info("========================");

        checkUpdates();
    }

    private File getPlayerFile(UUID uuid) {
        return new File(userDataFolder, uuid.toString() + ".yml");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        // /vault shared
        if (args.length == 1 && args[0].equalsIgnoreCase("shared")) {
            showSharedList(player);
            return true;
        }

        // /vault share <Player>
        if (args.length == 2 && args[0].equalsIgnoreCase("share")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) { player.sendMessage("§cPlayer not found."); return true; }
            addSharedPlayer(player.getUniqueId(), target.getUniqueId());
            player.sendMessage("§aVault shared with §e" + target.getName());
            return true;
        }

        // /vault <Name> (Die View Funktion)
        if (args.length == 1) {
            @SuppressWarnings("deprecation")
            UUID targetUUID = Bukkit.getOfflinePlayer(args[0]).getUniqueId();

            if (isSharedWith(targetUUID, player.getUniqueId()) || player.hasPermission("vault.admin")) {
                adminViewing.put(player.getUniqueId(), targetUUID); // Hier wird gespeichert, wen wir anschauen
                openVault(player, 1, targetUUID);
                player.sendMessage("§7Opening vault of §e" + args[0]);
                return true;
            } else {
                player.sendMessage("§cYou don't have permission to view this vault.");
                return true;
            }
        }

        // Standard: Eigener Vault
        adminViewing.remove(player.getUniqueId());
        openVault(player, 1, player.getUniqueId());
        return true;
    }

    public void openVault(Player player, int page, UUID targetUUID) {
        currentPage.put(player.getUniqueId(), page);

        int rows = getConfig().getInt("vault-settings.rows", 6);
        int slotsPerPage = rows * 9;
        int totalPages = getConfig().getInt("vault-settings.total-pages", 2);

        String title = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("vault-settings.title", "Vault - Page %page%")
                        .replace("%page%", String.valueOf(page)));

        Inventory inv = Bukkit.createInventory(player, slotsPerPage, title);

        File userFile = getPlayerFile(targetUUID);
        if (userFile.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(userFile);

            // Migration alt zu neu
            if (config.contains("data") && !config.contains("pages.page-1")) {
                config.set("pages.page-1", config.getString("data"));
                config.set("data", null);
                try { config.save(userFile); } catch (IOException ignored) {}
            }

            String data = config.getString("pages.page-" + page);
            if (data != null) {
                try {
                    ItemStack[] pageItems = itemStackArrayFromBase64(data);
                    for (int i = 0; i < Math.min(pageItems.length, slotsPerPage); i++) {
                        inv.setItem(i, pageItems[i]);
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        }

        // Navigations-Items
        if (page < totalPages) inv.setItem(slotsPerPage - 1, createNavItem(Material.ARROW, "§eNext Page →", "next"));
        if (page > 1) inv.setItem(slotsPerPage - 9, createNavItem(Material.ARROW, "§e← Previous Page", "prev"));

        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
    }

    private void saveVaultContent(Player player, Inventory inv) {
        // Hier wird geprüft: Speichern wir bei uns selbst oder bei dem, den wir gerade anschauen?
        UUID saveUUID = adminViewing.getOrDefault(player.getUniqueId(), player.getUniqueId());
        int page = currentPage.getOrDefault(player.getUniqueId(), 1);
        int slotsPerPage = inv.getSize();

        File userFile = getPlayerFile(saveUUID);
        FileConfiguration config = YamlConfiguration.loadConfiguration(userFile);

        ItemStack[] currentItems = inv.getContents().clone();
        for (int i = 0; i < currentItems.length; i++) {
            if (isNavItem(currentItems[i])) currentItems[i] = null;
        }

        config.set("pages.page-" + page, itemStackArrayToBase64(currentItems));
        try { config.save(userFile); } catch (IOException e) { e.printStackTrace(); }
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().contains("Vault")) return;
        if (e.getCurrentItem() == null) return;

        ItemMeta meta = e.getCurrentItem().getItemMeta();
        if (meta != null) {
            String nav = meta.getPersistentDataContainer().get(new NamespacedKey(this, "nav"), PersistentDataType.STRING);
            if (nav != null) {
                e.setCancelled(true);
                Player p = (Player) e.getWhoClicked();
                int current = currentPage.getOrDefault(p.getUniqueId(), 1);
                int totalPages = getConfig().getInt("vault-settings.total-pages", 2);
                int next = current;

                if (nav.equals("next") && current < totalPages) next = current + 1;
                else if (nav.equals("prev") && current > 1) next = current - 1;

                if (next != current) {
                    saveVaultContent(p, e.getInventory());
                    isSwitching.add(p.getUniqueId());

                    openVault(p, next, adminViewing.getOrDefault(p.getUniqueId(), p.getUniqueId()));

                    Bukkit.getScheduler().runTaskLater(this, () -> isSwitching.remove(p.getUniqueId()), 2L);
                }
            }
        }
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        if (e.getView().getTitle().contains("Vault")) {
            Player p = (Player) e.getPlayer();
            if (isSwitching.contains(p.getUniqueId())) return;

            saveVaultContent(p, e.getInventory());
            currentPage.remove(p.getUniqueId());
            adminViewing.remove(p.getUniqueId()); // Wichtig: Admin-Status erst beim Schließen löschen
            p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 1.0f, 1.0f);
        }
    }

    private boolean isNavItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(this, "nav"), PersistentDataType.STRING);
    }

    private ItemStack createNavItem(Material m, String name, String tag) {
        ItemStack i = new ItemStack(m);
        ItemMeta mt = i.getItemMeta();
        mt.setDisplayName(name);
        mt.getPersistentDataContainer().set(new NamespacedKey(this, "nav"), PersistentDataType.STRING, tag);
        i.setItemMeta(mt);
        return i;
    }

    public String itemStackArrayToBase64(ItemStack[] items) {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            BukkitObjectOutputStream out = new BukkitObjectOutputStream(os);
            out.writeInt(items.length);
            for (ItemStack item : items) out.writeObject(item);
            out.close();
            return Base64Coder.encodeLines(os.toByteArray());
        } catch (Exception e) { return ""; }
    }

    public ItemStack[] itemStackArrayFromBase64(String data) throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(Base64Coder.decodeLines(data));
        BukkitObjectInputStream in = new BukkitObjectInputStream(is);
        ItemStack[] items = new ItemStack[in.readInt()];
        for (int i = 0; i < items.length; i++) items[i] = (ItemStack) in.readObject();
        in.close();
        return items;
    }

    private void checkUpdates() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL("https://api.modrinth.com/v2/project/o1RrpCAv/version");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Vaults-UpdateChecker");
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line; StringBuilder sb = new StringBuilder();
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    if (!sb.toString().contains(getDescription().getVersion())) getLogger().warning("New version available on Modrinth!");
                }
            } catch (Exception ignored) {}
        });
    }

    private void addSharedPlayer(UUID owner, UUID guest) {
        File f = getPlayerFile(owner);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        List<String> list = cfg.getStringList("shared-with");
        if (!list.contains(guest.toString())) {
            list.add(guest.toString()); cfg.set("shared-with", list);
            try { cfg.save(f); } catch (IOException ignored) {}
        }
    }

    private boolean isSharedWith(UUID owner, UUID guest) {
        File f = getPlayerFile(owner);
        return f.exists() && YamlConfiguration.loadConfiguration(f).getStringList("shared-with").contains(guest.toString());
    }

    private void showSharedList(Player p) {
        p.sendMessage("§eAccessible Vaults:");
        File[] files = userDataFolder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (YamlConfiguration.loadConfiguration(f).getStringList("shared-with").contains(p.getUniqueId().toString())) {
                String name = Bukkit.getOfflinePlayer(UUID.fromString(f.getName().replace(".yml", ""))).getName();
                p.sendMessage("§7- §f" + (name != null ? name : "Unknown"));
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return List.of("share", "shared");
        return null;
    }
}