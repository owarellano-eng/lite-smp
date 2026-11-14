package com.dozer.strengthsmp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public final class StrengthSMP extends JavaPlugin implements Listener {
    private static final int START_LEVEL = 3;
    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 5;
    private static final String[] LOOT_POOL = {"Trident", "Sword", "Mace", "Armor", "Axe", "Bow", "Crossbow"};
    private NamespacedKey strengthItemKey;
    private NamespacedKey upgradedArmorKey;

    @Override
    public void onEnable() {
        strengthItemKey = new NamespacedKey(this, "strength_crystal");
        upgradedArmorKey = new NamespacedKey(this, "upgraded_armor");
        getServer().getScheduler().runTaskTimer(this, this::scanArmor, 10L, 5L);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("StrengthSMP enabled. Player strength ranges from 1 to 5.");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) revertAllArmor(p);
        saveConfig();
    }

    private String playerKey(UUID uuid) {
        return "players." + uuid + ".strength";
    }

    private int getStrength(Player player) {
        String key = playerKey(player.getUniqueId());
        if (!getConfig().contains(key)) {
            getConfig().set(key, START_LEVEL);
            saveConfig();
        }
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, getConfig().getInt(key, START_LEVEL)));
    }

    private void setStrength(Player player, int level) {
        int bounded = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
        getConfig().set(playerKey(player.getUniqueId()), bounded);
        saveConfig();
    }

    private double multiplier(int level) {
        return switch (level) {
            case 2 -> 1.4;
            case 3 -> 2.0;
            case 4 -> 3.0;
            case 5 -> 4.0;
            default -> 1.0;
        };
    }

    private Player getAttackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player attacker = getAttackingPlayer(event.getDamager());
        if (attacker == null) return;
        event.setDamage(event.getDamage() * multiplier(getStrength(attacker)));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;

        int victimBefore = getStrength(victim);
        int killerBefore = getStrength(killer);
        setStrength(victim, victimBefore - 1);
        setStrength(killer, killerBefore + 1);

        killer.sendMessage(ChatColor.GREEN + "You gained 1 Strength! " + ChatColor.GRAY + "(" + getStrength(killer) + "/5)");
        if (victimBefore > MIN_LEVEL) {
            victim.sendMessage(ChatColor.RED + "You lost 1 Strength. " + ChatColor.GRAY + "(" + getStrength(victim) + "/5)");
        } else {
            victim.sendMessage(ChatColor.RED + "You are already at the minimum Strength (1).");
        }
    }

    private String assignedKey(UUID uuid) {
        return "players." + uuid + ".assignedItem";
    }

    // Returns the player's assigned item, picking one at random the first time.
    private String getAssignedItem(Player player) {
        String key = assignedKey(player.getUniqueId());
        String assigned = getConfig().getString(key);
        if (assigned == null) {
            assigned = LOOT_POOL[ThreadLocalRandom.current().nextInt(LOOT_POOL.length)];
            getConfig().set(key, assigned);
            saveConfig();
        }
        return assigned;
    }

    // On first join the player is only told which item they were assigned; nothing is given.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean first = !getConfig().contains(assignedKey(player.getUniqueId()));
        String assigned = getAssignedItem(player);
        if (first) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + "You have been assigned: " + ChatColor.GOLD + assigned + ChatColor.LIGHT_PURPLE + "!");
        }
    }

    // ---- Armor assignment: diamond armor becomes netherite while worn, diamond again when removed ----

    private Material armorPiece(Material m, boolean netherite) {
        String prefix = netherite ? "DIAMOND_" : "NETHERITE_";
        String name = m.name();
        if (!name.startsWith(prefix)) return null;
        String piece = name.substring(prefix.length());
        if (!(piece.equals("HELMET") || piece.equals("CHESTPLATE") || piece.equals("LEGGINGS") || piece.equals("BOOTS"))) return null;
        return Material.valueOf((netherite ? "NETHERITE_" : "DIAMOND_") + piece);
    }

    private boolean isUpgradedArmor(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        if (armorPiece(item.getType(), false) == null) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(upgradedArmorKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private ItemStack upgradeArmor(ItemStack item) {
        Material target = armorPiece(item.getType(), true);
        if (target == null) return item;
        ItemStack result = item.withType(target);
        ItemMeta meta = result.getItemMeta();
        meta.getPersistentDataContainer().set(upgradedArmorKey, PersistentDataType.BYTE, (byte) 1);
        result.setItemMeta(meta);
        return result;
    }

    private ItemStack downgradeArmor(ItemStack item) {
        Material target = armorPiece(item.getType(), false);
        if (target == null) return item;
        ItemStack result = item.withType(target);
        ItemMeta meta = result.getItemMeta();
        meta.getPersistentDataContainer().remove(upgradedArmorKey);
        result.setItemMeta(meta);
        return result;
    }

    private void scanArmor() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean hasArmorAssignment = "Armor".equals(getAssignedItem(player));
            PlayerInventory inv = player.getInventory();

            // Worn armor: upgrade diamond for Armor players, revert upgraded pieces for everyone else.
            ItemStack[] worn = inv.getArmorContents();
            boolean changed = false;
            for (int i = 0; i < worn.length; i++) {
                ItemStack piece = worn[i];
                if (piece == null || piece.getType().isAir()) continue;
                if (hasArmorAssignment && armorPiece(piece.getType(), true) != null) {
                    worn[i] = upgradeArmor(piece);
                    changed = true;
                } else if (!hasArmorAssignment && isUpgradedArmor(piece)) {
                    worn[i] = downgradeArmor(piece);
                    changed = true;
                }
            }
            if (changed) inv.setArmorContents(worn);

            // Anything upgraded that is not being worn goes back to diamond.
            for (int slot = 0; slot < 36; slot++) {
                ItemStack item = inv.getItem(slot);
                if (isUpgradedArmor(item)) inv.setItem(slot, downgradeArmor(item));
            }
            if (isUpgradedArmor(inv.getItemInOffHand())) inv.setItemInOffHand(downgradeArmor(inv.getItemInOffHand()));
            if (isUpgradedArmor(player.getItemOnCursor())) player.setItemOnCursor(downgradeArmor(player.getItemOnCursor()));
        }
    }

    private void revertAllArmor(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] worn = inv.getArmorContents();
        for (int i = 0; i < worn.length; i++) {
            if (isUpgradedArmor(worn[i])) worn[i] = downgradeArmor(worn[i]);
        }
        inv.setArmorContents(worn);
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inv.getItem(slot);
            if (isUpgradedArmor(item)) inv.setItem(slot, downgradeArmor(item));
        }
    }

    // Dropped / dying players' upgraded armor turns back into diamond on the ground.
    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (isUpgradedArmor(stack)) event.getEntity().setItemStack(downgradeArmor(stack));
    }

    // Logging out counts as taking the armor off; it is re-upgraded on the next scan after joining.
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        revertAllArmor(event.getPlayer());
    }

    private ItemStack createStrengthItem(int amount) {
        ItemStack item = new ItemStack(Material.NETHER_STAR, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Strength Crystal");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Right-click to gain 1 Strength");
        lore.add(ChatColor.DARK_GRAY + "Droppable and tradeable");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(strengthItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isStrengthItem(ItemStack item) {
        if (item == null || item.getType() != Material.NETHER_STAR || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(strengthItemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    @EventHandler
    public void onUseStrengthItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isStrengthItem(item)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        int current = getStrength(player);
        if (current >= MAX_LEVEL) {
            player.sendMessage(ChatColor.RED + "You are already at maximum Strength (5).");
            return;
        }
        item.setAmount(item.getAmount() - 1);
        setStrength(player, current + 1);
        player.sendMessage(ChatColor.LIGHT_PURPLE + "Strength Crystal used! " + ChatColor.GRAY + "Your Strength is now " + getStrength(player) + "/5.");
    }

    private void giveStrengthItems(Player target, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int stack = Math.min(64, remaining);
            ItemStack item = createStrengthItem(stack);
            var leftovers = target.getInventory().addItem(item);
            leftovers.values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            remaining -= stack;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("withdraw")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can withdraw Strength.");
                return true;
            }
            int amount = 1;
            if (args.length >= 1) {
                try { amount = Integer.parseInt(args[0]); }
                catch (NumberFormatException ex) { player.sendMessage(ChatColor.RED + "Usage: /withdraw [amount]"); return true; }
            }
            if (amount < 1 || amount > 64) {
                player.sendMessage(ChatColor.RED + "You can withdraw between 1 and 64 Strength at a time.");
                return true;
            }
            int current = getStrength(player);
            if (current - amount < MIN_LEVEL) {
                player.sendMessage(ChatColor.RED + "You must keep at least 1 Strength. You have " + current + ".");
                return true;
            }
            setStrength(player, current - amount);
            giveStrengthItems(player, amount);
            player.sendMessage(ChatColor.LIGHT_PURPLE + "Withdrew " + amount + " Strength Crystal(s). " + ChatColor.GRAY + "Strength: " + getStrength(player) + "/5.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("strengthitem")) {
            if (!sender.hasPermission("strengthsmp.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
            Player target;
            int amount = 1;
            if (args.length == 0) {
                if (!(sender instanceof Player p)) { sender.sendMessage("Usage: /strengthitem <player> [amount]"); return true; }
                target = p;
            } else {
                target = Bukkit.getPlayerExact(args[0]);
                if (target == null) { sender.sendMessage(ChatColor.RED + "That player is not online."); return true; }
                if (args.length >= 2) {
                    try { amount = Integer.parseInt(args[1]); }
                    catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "Usage: /strengthitem [player] [amount]"); return true; }
                }
            }
            if (amount < 1 || amount > 2304) { sender.sendMessage(ChatColor.RED + "Amount must be between 1 and 2304."); return true; }
            giveStrengthItems(target, amount);
            sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " Strength Crystal(s) to " + target.getName() + ".");
            return true;
        }

        if (command.getName().equalsIgnoreCase("strength")) {
            if (args.length == 0) {
                if (!(sender instanceof Player player)) { sender.sendMessage("Usage: /strength give <player> [amount] or /strength set <player> <1-5>"); return true; }
                int level = getStrength(player);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Your Strength: " + level + "/5 " + ChatColor.GRAY + "(Damage: " + multiplier(level) + "x)");
                player.sendMessage(ChatColor.LIGHT_PURPLE + "Assigned item: " + ChatColor.GOLD + getAssignedItem(player));
                return true;
            }
            if (!sender.hasPermission("strengthsmp.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
            if (args[0].equalsIgnoreCase("give") && args.length >= 2) {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(ChatColor.RED + "That player is not online."); return true; }
                int amount = 1;
                if (args.length >= 3) {
                    try { amount = Integer.parseInt(args[2]); }
                    catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "Usage: /strength give <player> [amount]"); return true; }
                }
                if (amount < 1 || amount > 2304) { sender.sendMessage(ChatColor.RED + "Amount must be between 1 and 2304."); return true; }
                giveStrengthItems(target, amount);
                sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " Strength Crystal(s) to " + target.getName() + ".");
                return true;
            }
            if (args[0].equalsIgnoreCase("set") && args.length >= 3) {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(ChatColor.RED + "That player is not online."); return true; }
                try {
                    int level = Integer.parseInt(args[2]);
                    if (level < MIN_LEVEL || level > MAX_LEVEL) throw new NumberFormatException();
                    setStrength(target, level);
                    sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + " to Strength " + level + ".");
                } catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "Strength must be between 1 and 5."); }
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "Usage: /strength | /strength give <player> [amount] | /strength set <player> <1-5>");
            return true;
        }
        return false;
    }
}
