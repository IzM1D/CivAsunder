package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.CombatManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Map;

public class CustomArrowListener implements Listener {

    private static final double EXPLOSIVE_ARROW_ENTITY_POWER = 0.7;
    private static final double EXPLOSIVE_ARROW_BLOCK_POWER = 4.0;
    private static final int SHIELD_DISABLE_MS = 5000;
    private static final int ARMOR_DURABILITY_DAMAGE = 80;
    private static final int SHIELD_DURABILITY_DAMAGE = 40;
    private static final int MAX_BOUNCES = 5;
    private static final double[] BOUNCE_DAMAGE_MULTIPLIERS = {1.0, 0.8, 0.6, 0.4, 0.2, 0.05};

    private final CivAsunder plugin;
    private final CombatManager combatManager;
    private final NamespacedKey arrowTypeKey;
    private final NamespacedKey bounceCountKey;
    private final NamespacedKey explosiveArrowTntKey;

    public CustomArrowListener(CivAsunder plugin, CombatManager combatManager) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.arrowTypeKey = new NamespacedKey(plugin, "custom_arrow_type");
        this.bounceCountKey = new NamespacedKey(plugin, "custom_arrow_bounces");
        this.explosiveArrowTntKey = new NamespacedKey(plugin, "explosive_arrow_tnt");
    }

    public void registerRecipes() {
        registerShapelessRecipe(CustomArrowType.EXPLOSIVE, Material.ARROW, Material.TNT);
        registerShapedRecipe(CustomArrowType.PIERCING, 3, " A ", " AN", " A ",
                Map.of('A', Material.ARROW, 'N', Material.IRON_AXE));
        registerShapelessRecipe(CustomArrowType.MELTING, Material.ARROW, Material.FLINT_AND_STEEL);
        registerShapelessRecipe(CustomArrowType.TELEPORTING, Material.ARROW, Material.ENDER_PEARL);
        registerShapelessRecipe(CustomArrowType.BOUNCING, Material.ARROW, Material.SLIME_BLOCK);
    }

    private void registerShapedRecipe(CustomArrowType arrowType, int amount, String top, String middle, String bottom,
                                      Map<Character, Material> ingredients) {
        NamespacedKey recipeKey = new NamespacedKey(plugin, "custom_arrow_" + arrowType.id + "_recipe");
        Bukkit.removeRecipe(recipeKey);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createArrowItem(arrowType, amount));
        recipe.shape(top, middle, bottom);
        ingredients.forEach(recipe::setIngredient);
        Bukkit.addRecipe(recipe);
    }

    private void registerShapelessRecipe(CustomArrowType arrowType, Material... ingredients) {
        NamespacedKey recipeKey = new NamespacedKey(plugin, "custom_arrow_" + arrowType.id + "_recipe");
        Bukkit.removeRecipe(recipeKey);

        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, createArrowItem(arrowType));
        for (Material ingredient : ingredients) {
            recipe.addIngredient(ingredient);
        }
        Bukkit.addRecipe(recipe);
    }

    private ItemStack createArrowItem(CustomArrowType arrowType) {
        return createArrowItem(arrowType, 1);
    }

    private ItemStack createArrowItem(CustomArrowType arrowType, int amount) {
        ItemStack item = new ItemStack(Material.ARROW);
        item.setAmount(amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(arrowType.displayName);
            meta.setLore(arrowType.lore);
            meta.getPersistentDataContainer().set(arrowTypeKey, PersistentDataType.STRING, arrowType.id);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        CustomArrowType arrowType = getArrowType(event.getConsumable());
        if (arrowType == null || !(event.getProjectile() instanceof Projectile projectile)) return;

        tagProjectile(projectile, arrowType, 0);
        projectile.setCustomName(arrowType.displayName);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) return;
        CustomArrowType arrowType = getArrowType(projectile);
        if (arrowType == null) return;

        switch (arrowType) {
            case PIERCING -> handlePiercingDamage(event);
            case MELTING -> handleMeltingDamage(event);
            case BOUNCING -> event.setDamage(event.getDamage() * getBounceDamageMultiplier(projectile));
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplosiveArrowTntDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !(event.getDamager() instanceof TNTPrimed tnt)
                || !isExplosiveArrowTnt(tnt)) {
            return;
        }

        double reducedDamage = calculateReducedExplosionDamage(tnt, player, event.getDamage());
        if (reducedDamage <= 0.0) {
            event.setCancelled(true);
            return;
        }

        event.setDamage(reducedDamage);
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        CustomArrowType arrowType = getArrowType(projectile);
        if (arrowType == null) return;

        switch (arrowType) {
            case EXPLOSIVE -> spawnPrimedTnt(projectile);
            case TELEPORTING -> teleportShooter(event, projectile);
            case BOUNCING -> bounce(event, projectile);
            default -> {
            }
        }
    }

    private void handlePiercingDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        double damage = Math.max(0.0, event.getDamage());
        event.setCancelled(true);
        if (target instanceof Player player && hasRaisedShield(player)) {
            combatManager.lockShield(player, SHIELD_DISABLE_MS);
            player.clearActiveItem();
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 1.1f);
        }

        applyPureDamage(target, damage, event);
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0.0, 1.0, 0.0),
                12, 0.25, 0.35, 0.25, 0.08);
    }

    private void handleMeltingDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        event.setCancelled(true);
        if (target instanceof Player player && hasRaisedShield(player)) {
            ItemStack shield = getRaisedShield(player);
            boolean broken = damageDurableItem(shield, SHIELD_DURABILITY_DAMAGE);
            if (broken) removeRaisedShield(player);
            player.getWorld().playSound(player.getLocation(),
                    broken ? Sound.ENTITY_ITEM_BREAK : Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.8f);
            return;
        }

        EntityEquipment equipment = target.getEquipment();
        if (equipment == null) return;

        ItemStack[] armor = equipment.getArmorContents();
        boolean changed = false;
        boolean brokenAny = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (!isArmor(piece)) continue;

            if (damageDurableItem(piece, ARMOR_DURABILITY_DAMAGE)) {
                armor[i] = null;
                brokenAny = true;
            }
            changed = true;
        }

        if (!changed) return;
        equipment.setArmorContents(armor);
        target.getWorld().playSound(target.getLocation(),
                brokenAny ? Sound.ENTITY_ITEM_BREAK : Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 0.7f);
        target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0.0, 1.0, 0.0),
                10, 0.25, 0.35, 0.25, 0.02);
    }

    private void spawnPrimedTnt(Projectile projectile) {
        Location location = projectile.getLocation();
        projectile.remove();
        TNTPrimed tnt = location.getWorld().spawn(location, TNTPrimed.class);
        tnt.setFuseTicks(1);
        tnt.setYield((float) EXPLOSIVE_ARROW_BLOCK_POWER);
        tnt.getPersistentDataContainer().set(explosiveArrowTntKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void teleportShooter(ProjectileHitEvent event, Projectile projectile) {
        ProjectileSource shooter = projectile.getShooter();
        if (!(shooter instanceof Entity entity) || entity.isDead()) {
            projectile.remove();
            return;
        }

        Location destination = projectile.getLocation().clone();
        if (event.getHitBlock() != null && event.getHitBlockFace() != null) {
            destination.add(event.getHitBlockFace().getDirection().multiply(0.65));
        }
        Location current = entity.getLocation();
        destination.setYaw(current.getYaw());
        destination.setPitch(current.getPitch());

        projectile.remove();
        entity.teleport(destination);
        entity.setFallDistance(0.0f);
        entity.getWorld().playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        entity.getWorld().spawnParticle(Particle.PORTAL, destination.add(0.0, 1.0, 0.0),
                24, 0.35, 0.6, 0.35, 0.05);
    }

    private void bounce(ProjectileHitEvent event, Projectile projectile) {
        int bounceCount = getBounceCount(projectile);
        if (bounceCount >= MAX_BOUNCES) return;

        Vector reflectedVelocity = reflectVelocity(event, projectile);
        if (reflectedVelocity.lengthSquared() < 0.04) return;

        ProjectileSource shooter = projectile.getShooter();
        double baseDamage = projectile instanceof AbstractArrow arrow ? arrow.getDamage() : 0.0;
        boolean critical = projectile instanceof AbstractArrow arrow && arrow.isCritical();
        Location spawnLocation = projectile.getLocation().clone();
        Vector direction = reflectedVelocity.clone().normalize();
        spawnLocation.add(direction.clone().multiply(0.55));

        projectile.remove();
        Arrow bouncedArrow = spawnLocation.getWorld().spawnArrow(spawnLocation, direction,
                (float) Math.min(reflectedVelocity.length(), 4.0), 0.0f);
        bouncedArrow.setVelocity(reflectedVelocity);
        bouncedArrow.setShooter(shooter);
        bouncedArrow.setDamage(baseDamage);
        bouncedArrow.setCritical(critical);
        tagProjectile(bouncedArrow, CustomArrowType.BOUNCING, bounceCount + 1);
        bouncedArrow.getWorld().playSound(spawnLocation, Sound.BLOCK_SLIME_BLOCK_HIT, 0.8f, 1.25f);
    }

    private Vector reflectVelocity(ProjectileHitEvent event, Projectile projectile) {
        Vector velocity = projectile.getVelocity().clone();
        if (velocity.lengthSquared() < 0.01) {
            velocity = projectile.getLocation().getDirection();
        }

        Vector normal = null;
        BlockFace hitBlockFace = event.getHitBlockFace();
        if (hitBlockFace != null) {
            normal = hitBlockFace.getDirection();
        } else if (event.getHitEntity() != null) {
            normal = projectile.getLocation().toVector().subtract(event.getHitEntity().getLocation().toVector());
            if (normal.lengthSquared() < 0.01) normal = velocity.clone().multiply(-1.0);
            normal.normalize();
        }

        if (normal == null || normal.lengthSquared() < 0.01) {
            return velocity.multiply(0.75);
        }

        double dot = velocity.dot(normal);
        return velocity.subtract(normal.multiply(2.0 * dot)).multiply(0.78);
    }

    private void tagProjectile(Projectile projectile, CustomArrowType arrowType, int bounceCount) {
        PersistentDataContainer container = projectile.getPersistentDataContainer();
        container.set(arrowTypeKey, PersistentDataType.STRING, arrowType.id);
        container.set(bounceCountKey, PersistentDataType.INTEGER, bounceCount);

        if (projectile instanceof AbstractArrow arrow) {
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        }
    }

    private CustomArrowType getArrowType(ItemStack item) {
        if (item == null || item.getType() != Material.ARROW || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(arrowTypeKey, PersistentDataType.STRING);
        return CustomArrowType.fromId(id);
    }

    private CustomArrowType getArrowType(Projectile projectile) {
        String id = projectile.getPersistentDataContainer().get(arrowTypeKey, PersistentDataType.STRING);
        return CustomArrowType.fromId(id);
    }

    private int getBounceCount(Projectile projectile) {
        return projectile.getPersistentDataContainer().getOrDefault(bounceCountKey,
                PersistentDataType.INTEGER, 0);
    }

    private double getBounceDamageMultiplier(Projectile projectile) {
        int bounceCount = Math.min(getBounceCount(projectile), BOUNCE_DAMAGE_MULTIPLIERS.length - 1);
        return BOUNCE_DAMAGE_MULTIPLIERS[bounceCount];
    }

    private boolean isExplosiveArrowTnt(TNTPrimed tnt) {
        return tnt.getPersistentDataContainer().has(explosiveArrowTntKey, PersistentDataType.BYTE);
    }

    private double calculateReducedExplosionDamage(TNTPrimed tnt, Player player, double originalDamage) {
        double distance = tnt.getLocation().distance(player.getLocation());
        double originalRadius = EXPLOSIVE_ARROW_BLOCK_POWER * 2.0;
        double reducedRadius = EXPLOSIVE_ARROW_ENTITY_POWER * 2.0;
        if (distance > reducedRadius || originalDamage <= 0.0) {
            return 0.0;
        }

        double originalDistanceFactor = 1.0 - (distance / originalRadius);
        if (originalDistanceFactor <= 0.0) {
            return 0.0;
        }

        double exposure = getExplosionExposure(originalDamage, originalRadius) / originalDistanceFactor;
        double reducedImpact = Math.max(0.0, Math.min(1.0, exposure) * (1.0 - (distance / reducedRadius)));
        return ((reducedImpact * reducedImpact + reducedImpact) / 2.0) * 7.0 * reducedRadius + 1.0;
    }

    private double getExplosionExposure(double damage, double radius) {
        double impactEquation = Math.max(0.0, (damage - 1.0) * 2.0 / (7.0 * radius));
        return (Math.sqrt(1.0 + 4.0 * impactEquation) - 1.0) / 2.0;
    }

    private void applyPureDamage(LivingEntity target, double damage, EntityDamageByEntityEvent cause) {
        if (damage <= 0.0 || target.isDead()) return;

        target.setLastDamageCause(cause);
        double nextHealth = Math.max(0.0, target.getHealth() - damage);
        target.setHealth(nextHealth);
    }

    private boolean hasRaisedShield(Player player) {
        return player.isBlocking() && getRaisedShield(player) != null;
    }

    private ItemStack getRaisedShield(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType() == Material.SHIELD) return mainHand;

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand.getType() == Material.SHIELD) return offHand;
        return null;
    }

    private void removeRaisedShield(Player player) {
        if (player.getInventory().getItemInMainHand().getType() == Material.SHIELD) {
            player.getInventory().setItemInMainHand(null);
        } else if (player.getInventory().getItemInOffHand().getType() == Material.SHIELD) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    private boolean damageDurableItem(ItemStack item, int amount) {
        if (item == null || item.getType() == Material.AIR || item.getType().getMaxDurability() <= 0) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return false;

        int nextDamage = damageable.getDamage() + amount;
        if (nextDamage >= item.getType().getMaxDurability()) {
            return true;
        }

        damageable.setDamage(nextDamage);
        item.setItemMeta(meta);
        return false;
    }

    private boolean isArmor(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        String name = item.getType().name();
        return name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS");
    }

    private enum CustomArrowType {
        EXPLOSIVE("explosive", ChatColor.RED + "Взрывающаяся стрела",
                java.util.List.of(ChatColor.GRAY + "Создаёт почти взорвавшийся динамит при попадании.")),
        PIERCING("piercing", ChatColor.AQUA + "Пробивающая стрела",
                java.util.List.of(ChatColor.GRAY + "Наносит чистый урон и выводит поднятый щит из строя.")),
        MELTING("melting", ChatColor.GOLD + "Стрела оплавления",
                java.util.List.of(ChatColor.GRAY + "Повреждает броню или поднятый щит вместо здоровья.")),
        TELEPORTING("teleporting", ChatColor.BLUE + "Телепортирующая стрела",
                java.util.List.of(ChatColor.GRAY + "Телепортирует стрелка в точку попадания.")),
        BOUNCING("bouncing", ChatColor.GREEN + "Отскакивающая стрела",
                java.util.List.of(ChatColor.GRAY + "До пяти раз отскакивает, теряя урон после каждого рикошета."));

        private final String id;
        private final String displayName;
        private final java.util.List<String> lore;

        CustomArrowType(String id, String displayName, java.util.List<String> lore) {
            this.id = id;
            this.displayName = displayName;
            this.lore = lore;
        }

        private static CustomArrowType fromId(String id) {
            if (id == null) return null;
            for (CustomArrowType arrowType : values()) {
                if (arrowType.id.equals(id)) return arrowType;
            }
            return null;
        }
    }
}
