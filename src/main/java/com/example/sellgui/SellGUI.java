package com.example.sellgui;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public class SellGUI extends JavaPlugin implements Listener, CommandExecutor {

    private static final int SIZE = 54;          // double chest
    private static final int SELL_SLOT = 53;     // last slot

    private Economy economy;
    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    private final DecimalFormat money = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));

    /** Marker holder so we can recognise our GUI. */
    private static class SellHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override
    public void onEnable() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("No Vault economy provider found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = rsp.getProvider();

        loadPrices();
        getCommand("sell").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ------------------------------------------------------------ prices.yml

    private void loadPrices() {
        File file = new File(getDataFolder(), "prices.yml");
        if (!file.exists()) saveResource("prices.yml", false);

        prices.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("prices");
        if (section == null) section = yaml;   // also allow prices at top level

        for (String key : section.getKeys(false)) {
            Material mat = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (mat == null) {
                getLogger().warning("prices.yml: unknown material '" + key + "'");
                continue;
            }
            if (!section.isDouble(key) && !section.isInt(key)) continue;   // skip non-number entries
            prices.put(mat, section.getDouble(key));
        }
        getLogger().info("Loaded " + prices.size() + " sell prices.");
    }

    // --------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("sellgui.reload")) {
                sender.sendMessage(ChatColor.RED + smallCaps("no permission"));
                return true;
            }
            loadPrices();
            sender.sendMessage(ChatColor.GREEN + smallCaps("prices reloaded") + ChatColor.WHITE + " (" + prices.size() + ")");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        openGui(player);
        return true;
    }

    // ------------------------------------------------------------------- GUI

    private void openGui(Player player) {
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                ChatColor.DARK_GREEN + smallCaps("sell items"));
        holder.inventory = inv;

        ItemStack button = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = button.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + smallCaps("sell all"));
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + smallCaps("click to sell every item"),
                ChatColor.GRAY + smallCaps("inside this menu")));
        button.setItemMeta(meta);
        inv.setItem(SELL_SLOT, button);

        player.openInventory(inv);
    }

    private boolean isSellGui(Inventory inv) {
        return inv != null && inv.getHolder() instanceof SellHolder;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!isSellGui(e.getInventory())) return;

        // Protect the button from every kind of interaction
        if (e.getRawSlot() == SELL_SLOT) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) sell(p, e.getInventory());
            return;
        }
        // Stops double-click "collect to cursor" from grabbing the button
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (isSellGui(e.getInventory()) && e.getRawSlots().contains(SELL_SLOT)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!isSellGui(inv) || !(e.getPlayer() instanceof Player p)) return;

        // Give back anything that wasn't sold
        for (int i = 0; i < SELL_SLOT; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null) continue;
            inv.setItem(i, null);
            p.getInventory().addItem(item).values()
                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
    }

    // ------------------------------------------------------------------ sell

    private void sell(Player p, Inventory inv) {
        double total = 0;
        int items = 0;
        for (int i = 0; i < SELL_SLOT; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null) continue;
            items++;
            Double price = prices.get(item.getType());
            if (price == null) continue;           // unsellable -> stays in GUI
            total += price * item.getAmount();
            inv.setItem(i, null);
        }

        if (total <= 0) {
            p.sendMessage(ChatColor.RED + smallCaps(items == 0
                    ? "put items in the menu first"
                    : "these items have no price in prices.yml"));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        economy.depositPlayer(p, total);
        p.sendMessage(ChatColor.GREEN + "+$" + ChatColor.WHITE + money.format(total));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    // ------------------------------------------------------------ small caps

    private static final String NORMAL = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL  = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    static String smallCaps(String in) {
        StringBuilder sb = new StringBuilder(in.length());
        for (char c : in.toCharArray()) {
            int idx = NORMAL.indexOf(Character.toLowerCase(c));
            sb.append(idx >= 0 ? SMALL.charAt(idx) : c);
        }
        return sb.toString();
    }
}
