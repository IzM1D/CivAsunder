package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.MatterManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;

public class MatterConverterListener implements Listener {

    private static final String TITLE = "§5Конвертатор материи";
    private static final int INVENTORY_SIZE = 9;
    private static final int INPUT_SLOT = 2;
    private static final int ARROW_SLOT = 4;
    private static final int OUTPUT_SLOT = 6;

    private final CivAsunder plugin;
    private final MatterManager matterManager;
    private final NamespacedKey converterKey;
    private final NamespacedKey matterCurrencyKey;
    private final NamespacedKey recipeKey;

    public MatterConverterListener(CivAsunder plugin, MatterManager matterManager) {
        this.plugin = plugin;
        this.matterManager = matterManager;
        this.converterKey = new NamespacedKey(plugin, "is_matter_converter");
        this.matterCurrencyKey = new NamespacedKey(plugin, "is_matter_currency");
        this.recipeKey = new NamespacedKey(plugin, "matter_converter_recipe");
    }

    public void registerRecipe() {
        if (Bukkit.getRecipe(recipeKey) != null) return;

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createConverterItem());
        recipe.shape(" S ", "SCS", " S ");
        recipe.setIngredient('S', Material.SMOOTH_STONE);
        recipe.setIngredient('C', Material.CRAFTING_TABLE);
        Bukkit.addRecipe(recipe);
    }

    public ItemStack createConverterItem() {
        ItemStack item = new ItemStack(Material.CRAFTING_TABLE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TITLE);
            meta.getPersistentDataContainer().set(converterKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConverterPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!isConverterItem(item)) return;

        matterManager.getConverters().add(event.getBlockPlaced().getLocation());
        matterManager.saveData();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConverterBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location location = block.getLocation();
        if (block.getType() != Material.CRAFTING_TABLE || !matterManager.getConverters().remove(location)) {
            return;
        }

        if (event.isDropItems()) {
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(location, createConverterItem());
        }
        matterManager.saveData();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConverterInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CRAFTING_TABLE) return;
        if (!matterManager.getConverters().contains(block.getLocation())) return;

        event.setCancelled(true);
        openConverter(event.getPlayer());
    }

    @EventHandler
    public void onConverterClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConverterHolder)) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < INVENTORY_SIZE) {
            if (rawSlot == OUTPUT_SLOT) {
                event.setCancelled(true);
                handleOutputClick(event);
                return;
            }

            if (rawSlot != INPUT_SLOT) {
                event.setCancelled(true);
                return;
            }
        } else if (event.isShiftClick()) {
            event.setCancelled(true);
            moveShiftClickedItemToInput(event);
            updateOutput(event.getInventory());
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> updateOutput(event.getInventory()));
    }

    @EventHandler
    public void onConverterDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConverterHolder)) return;

        boolean touchesInput = false;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= INVENTORY_SIZE) continue;
            if (rawSlot != INPUT_SLOT) {
                event.setCancelled(true);
                return;
            }
            touchesInput = true;
        }

        if (touchesInput) {
            Bukkit.getScheduler().runTask(plugin, () -> updateOutput(event.getInventory()));
        }
    }

    @EventHandler
    public void onConverterClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConverterHolder)) return;

        Inventory inventory = event.getInventory();
        inventory.setItem(OUTPUT_SLOT, null);
        ItemStack input = inventory.getItem(INPUT_SLOT);
        if (input != null && input.getType() != Material.AIR) {
            event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), input);
            inventory.setItem(INPUT_SLOT, null);
        }
    }

    private void openConverter(Player player) {
        Inventory inventory = Bukkit.createInventory(new ConverterHolder(), INVENTORY_SIZE, TITLE);
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        if (paneMeta != null) {
            paneMeta.setDisplayName(" ");
            pane.setItemMeta(paneMeta);
        }
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (slot != INPUT_SLOT && slot != OUTPUT_SLOT && slot != ARROW_SLOT) {
                inventory.setItem(slot, pane);
            }
        }

        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta arrowMeta = arrow.getItemMeta();
        if (arrowMeta != null) {
            arrowMeta.setDisplayName(" ");
            arrow.setItemMeta(arrowMeta);
        }
        inventory.setItem(ARROW_SLOT, arrow);
        player.openInventory(inventory);
    }

    private void updateOutput(Inventory inventory) {
        ConversionPlan plan = createPlan(inventory.getItem(INPUT_SLOT));
        inventory.setItem(OUTPUT_SLOT, plan == null ? null : matterManager.createMatterItem(plan.matter()));
    }

    private void handleOutputClick(InventoryClickEvent event) {
        Inventory inventory = event.getInventory();
        ConversionPlan plan = createPlan(inventory.getItem(INPUT_SLOT));
        if (plan == null) {
            inventory.setItem(OUTPUT_SLOT, null);
            return;
        }

        Player player = (Player) event.getWhoClicked();
        ItemStack result = matterManager.createMatterItem(plan.matter());
        if (event.isShiftClick()) {
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(result);
            if (!leftovers.isEmpty()) return;
        } else {
            ItemStack cursor = player.getItemOnCursor();
            if (cursor == null || cursor.getType() == Material.AIR) {
                player.setItemOnCursor(result);
            } else if (cursor.isSimilar(result)
                    && cursor.getAmount() + result.getAmount() <= cursor.getMaxStackSize()) {
                cursor.setAmount(cursor.getAmount() + result.getAmount());
                player.setItemOnCursor(cursor);
            } else {
                return;
            }
        }

        consumeInput(inventory, plan.itemsToConsume());
        updateOutput(inventory);
    }

    private void moveShiftClickedItemToInput(InventoryClickEvent event) {
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || isMatterEssence(clicked)) return;
        Inventory inventory = event.getInventory();
        ItemStack input = inventory.getItem(INPUT_SLOT);
        if (input != null && input.getType() != Material.AIR && !input.isSimilar(clicked)) return;

        int maxMove = clicked.getMaxStackSize();
        int freeSpace = input == null || input.getType() == Material.AIR ? maxMove : maxMove - input.getAmount();
        if (freeSpace <= 0) return;

        int moved = Math.min(clicked.getAmount(), freeSpace);
        ItemStack movedStack = clicked.clone();
        movedStack.setAmount(moved);
        if (input == null || input.getType() == Material.AIR) {
            inventory.setItem(INPUT_SLOT, movedStack);
        } else {
            input.setAmount(input.getAmount() + moved);
        }
        if (clicked.getAmount() <= moved) {
            event.getClickedInventory().setItem(event.getSlot(), null);
        } else {
            clicked.setAmount(clicked.getAmount() - moved);
        }
    }

    private void consumeInput(Inventory inventory, int amount) {
        ItemStack input = inventory.getItem(INPUT_SLOT);
        if (input == null || input.getType() == Material.AIR) return;
        if (input.getAmount() <= amount) {
            inventory.setItem(INPUT_SLOT, null);
        } else {
            input.setAmount(input.getAmount() - amount);
        }
    }

    private ConversionPlan createPlan(ItemStack input) {
        if (input == null || input.getType() == Material.AIR || isMatterEssence(input)) return null;

        double singleValue = matterManager.getValueTable().getOrDefault(input.getType(), 0.0);
        if (singleValue <= 0.0) return null;

        int matterToGive = (int) Math.floor(singleValue * input.getAmount());
        if (matterToGive < 1) return null;

        matterToGive = Math.min(matterToGive, Material.AMETHYST_SHARD.getMaxStackSize());
        int itemsToConsume = (int) Math.ceil(matterToGive / singleValue);
        return new ConversionPlan(matterToGive, Math.min(itemsToConsume, input.getAmount()));
    }

    private boolean isConverterItem(ItemStack item) {
        if (item == null || item.getType() != Material.CRAFTING_TABLE || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(converterKey, PersistentDataType.BYTE);
    }

    private boolean isMatterEssence(ItemStack item) {
        if (item == null || item.getType() != Material.AMETHYST_SHARD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(matterCurrencyKey, PersistentDataType.BYTE);
    }

    private static final class ConverterHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record ConversionPlan(int matter, int itemsToConsume) {}
}
