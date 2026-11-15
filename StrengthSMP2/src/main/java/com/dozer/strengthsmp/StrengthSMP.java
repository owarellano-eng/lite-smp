package com.dozer.strengthsmp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
    private NamespacedKey rerollKey;
    private static final long OVERDRIVE_COOLDOWN_MS = 60_000L; // 60s cooldown
    private static final int OVERDRIVE_TICKS = 20 * 15;        // 15s of effects
    private static final double SHOCKWAVE_RADIUS = 7.0;
    private static final double SHOCKWAVE_DAMAGE = 6.0;        // 3 hearts, ignores the Strength multiplier
    private final Map<UUID, Long> overdriveCooldowns = new HashMap<>();
    private boolean abilityDamageInProgress = false;

    @Override
    public void onEnable() {
        strengthItemKey = new NamespacedKey(this, "strength_crystal");
        upgradedArmorKey = new NamespacedKey(this, "upgraded_armor");
        getServer().getScheduler().runTaskTimer(this, this::scanArmor, 10L, 5L);
        rerollKey = new NamespacedKey(this, "reroll");
        ShapedRecipe rerollRecipe = new ShapedRecipe(rerollKey, createReroll());
        rerollRecipe.shape("ISI", "SDS", "ISI");
        rerollRecipe.setIngredient('I', Material.IRON_BLOCK);
        rerollRecipe.setIngredient('S', Material.GOLD_BLOCK);
        rerollRecipe.setIngredient('D', Material.DIAMOND_BLOCK);
        Bukkit.removeRecipe(rerollKey);
        Bukkit.addRecipe(rerollRecipe);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("StrengthSMP enabled. Player strength ranges from 1 to 5.");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) revertAllArmor(p);
        Bukkit.removeRecipe(rerollKey);
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
            case 2 -> 1.2;
            case 3 -> 1.5;
            case 4 -> 2.0;
            case 5 -> 2.5;
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
        if (abilityDamageInProgress) return;
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
        player.discoverRecipe(rerollKey);
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
        overdriveCooldowns.remove(event.getPlayer().getUniqueId());
        revertAllArmor(event.getPlayer());
    }

    // ---- Reroll book: craft with iron blocks (corners), gold blocks (sides), diamond block (middle) ----

    private ItemStack createReroll() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Reroll");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Right-click to reroll your assigned item.");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(rerollKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isReroll(ItemStack item) {
        if (item == null || item.getType() != Material.BOOK || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(rerollKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    // Picks a different random assigned item for the player and saves it. Returns the new item.
    private String rerollAssigned(Player player) {
        String current = getAssignedItem(player);
        String next;
        do {
            next = LOOT_POOL[ThreadLocalRandom.current().nextInt(LOOT_POOL.length)];
        } while (next.equals(current));
        getConfig().set(assignedKey(player.getUniqueId()), next);
        saveConfig();
        return next;
    }

    @EventHandler
    public void onUseReroll(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (!isReroll(item)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        String next = rerollAssigned(player);
        item.setAmount(item.getAmount() - 1);
        player.sendMessage(ChatColor.AQUA + "Rerolled! " + ChatColor.GRAY + "You are now assigned: " + ChatColor.GOLD + next);
    }
    
    // ---- /activate: Strength 5 only. "Overdrive" with a shockwave, aura and a bonus based on your assigned item ----

    private void activateOverdrive(Player player) {
        if (getStrength(player) < MAX_LEVEL) {
            player.sendMessage(ChatColor.RED + "You need Strength 5 to use /activate. " + ChatColor.GRAY + "(You have " + getStrength(player) + "/5)");
            return;
        }
        long now = System.currentTimeMillis();
        long readyAt = overdriveCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long secs = (readyAt - now + 999) / 1000;
            player.sendMessage(ChatColor.RED + "Overdrive is on cooldown for " + secs + "s.");
            return;
        }
        overdriveCooldowns.put(player.getUniqueId(), now + OVERDRIVE_COOLDOWN_MS);

        final Player user = player;
        final Location origin = user.getLocation();

        // Lightning flash + explosion + thunder (all visual/sound only).
        user.getWorld().strikeLightningEffect(origin);
        user.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, origin, 1);
        user.getWorld().playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.7f);
        user.getWorld().playSound(origin, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);

        // Shockwave: damages and launches everything nearby except you.
        abilityDamageInProgress = true;
        try {
            for (Entity nearby : user.getNearbyEntities(SHOCKWAVE_RADIUS, SHOCKWAVE_RADIUS, SHOCKWAVE_RADIUS)) {
                if (!(nearby instanceof LivingEntity victim)) continue;
                victim.damage(SHOCKWAVE_DAMAGE, user);
                Vector push = victim.getLocation().toVector().subtract(origin.toVector());
                if (push.lengthSquared() < 0.0001) push = new Vector(0, 0, 1);
                victim.setVelocity(push.normalize().multiply(1.6).setY(0.6));
            }
        } finally {
            abilityDamageInProgress = false;
        }

        // Base buffs for everyone.
        user.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, OVERDRIVE_TICKS, 1));
        user.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, OVERDRIVE_TICKS, 0));

        // Bonus depends on the item you were assigned.
        String assigned = getAssignedItem(user);
        String bonus;
        switch (assigned) {
            case "Trident" -> {
                user.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, OVERDRIVE_TICKS, 1));
                user.addPotionEffect(new PotionEffect(PotionEffectType.CONDUIT_POWER, OVERDRIVE_TICKS, 0));
                bonus = "Tidal Rush (swim fast, see and breathe underwater)";
            }
            case "Sword" -> {
                user.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, OVERDRIVE_TICKS, 2));
                bonus = "Blade Guard (extra golden hearts)";
            }
            case "Mace" -> {
                user.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, OVERDRIVE_TICKS, 2));
                bonus = "Skyfall (jump much higher)";
            }
            case "Armor" -> {
                user.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, OVERDRIVE_TICKS, 1));
                bonus = "Iron Will (extra damage resistance)";
            }
            case "Axe" -> {
                user.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, OVERDRIVE_TICKS, 1));
                bonus = "Berserker (mine and swing faster)";
            }
            case "Bow" -> {
                user.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, OVERDRIVE_TICKS, 2));
                bonus = "Windrunner (even faster)";
            }
            default -> {
                user.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, OVERDRIVE_TICKS, 1));
                bonus = "Second Wind (fast healing)";
            }
        }

        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + user.getName() + ChatColor.RED + " activated OVERDRIVE!");
        user.sendMessage(ChatColor.LIGHT_PURPLE + "Overdrive active for 15 seconds. " + ChatColor.GOLD + bonus);

        // Fiery aura around you while it lasts.
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!user.isOnline() || user.isDead() || ticks >= OVERDRIVE_TICKS) {
                    if (user.isOnline()) user.sendMessage(ChatColor.GRAY + "Overdrive ended.");
                    cancel();
                    return;
                }
                Location loc = user.getLocation().add(0, 1, 0);
                user.getWorld().spawnParticle(Particle.FLAME, loc, 10, 0.4, 0.7, 0.4, 0.02);
                user.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 4, 0.5, 0.8, 0.5, 0.01);
                ticks += 4;
            }
        }.runTaskTimer(this, 0L, 4L);
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
                if (!(sender instanceof Player player)) { sender.sendMessage("Usage: /strength give <player> [amount] or /strength set <player> <1-5> | /strength reroll [player] | /strength givereroll [player]"); return true; }
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
            if (args[0].equalsIgnoreCase("reroll")) {
                Player target = null;
                if (args.length >= 2) target = Bukkit.getPlayerExact(args[1]);
                else if (sender instanceof Player p) target = p;
                if (target == null) { sender.sendMessage(ChatColor.RED + "Usage: /strength reroll [player] (player must be online)"); return true; }
                String before = getAssignedItem(target);
                String after = rerollAssigned(target);
                sender.sendMessage(ChatColor.GREEN + "Rerolled " + target.getName() + ": " + before + " -> " + after);
                boolean self = (sender instanceof Player) && ((Player) sender).getUniqueId().equals(target.getUniqueId());
                if (!self) target.sendMessage(ChatColor.AQUA + "An admin rerolled your item! " + ChatColor.GRAY + "You are now assigned: " + ChatColor.GOLD + after);
                else target.sendMessage(ChatColor.AQUA + "Rerolled! " + ChatColor.GRAY + "You are now assigned: " + ChatColor.GOLD + after);
                return true;
            }
            if (args[0].equalsIgnoreCase("givereroll")) {
                Player target = null;
                if (args.length >= 2) target = Bukkit.getPlayerExact(args[1]);
                else if (sender instanceof Player p) target = p;
                if (target == null) { sender.sendMessage(ChatColor.RED + "Usage: /strength givereroll [player] (player must be online)"); return true; }
                final Player receiver = target;
                var leftovers = receiver.getInventory().addItem(createReroll());
                leftovers.values().forEach(l -> receiver.getWorld().dropItemNaturally(receiver.getLocation(), l));
                sender.sendMessage(ChatColor.GREEN + "Gave a Reroll book to " + receiver.getName() + ".");
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

        if (command.getName().equalsIgnoreCase("activate")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use /activate.");
                return true;
            }
            activateOverdrive(player);
            return true;
        }
        return false;
    }
}
