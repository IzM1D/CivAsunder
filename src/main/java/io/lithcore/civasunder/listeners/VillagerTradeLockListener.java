package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Убирает возможность «роллить» трейды, снимая и возвращая жителю блок профессии.
 *
 * Ванильный сервер генерирует новый набор рецептов при каждой выдаче профессии, поэтому
 * первый полученный набор мы сохраняем в PersistentDataContainer жителя и при всех
 * последующих сменах карьеры восстанавливаем его же. Ключ включает профессию и уровень,
 * так что честный апгрейд уровня по-прежнему добавляет новые трейды — но тоже ровно один раз.
 */
public class VillagerTradeLockListener implements Listener {

    private final CivAsunder plugin;

    public VillagerTradeLockListener(CivAsunder plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        Villager villager = event.getEntity();
        Villager.Profession profession = event.getProfession();
        scheduleSync(villager, profession);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        scheduleSync(villager, villager.getProfession());
    }

    /**
     * Ванилла заполняет рецепты уже после события, поэтому читаем и правим их следующим тиком.
     */
    private void scheduleSync(Villager villager, Villager.Profession profession) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!villager.isValid() || villager.getProfession() != profession) {
                return;
            }
            NamespacedKey key = tradeKey(profession, villager.getVillagerLevel());
            PersistentDataContainer container = villager.getPersistentDataContainer();
            String stored = container.get(key, PersistentDataType.STRING);

            if (stored == null) {
                String encoded = encode(villager.getRecipes());
                if (encoded != null) {
                    container.set(key, PersistentDataType.STRING, encoded);
                }
                return;
            }

            List<MerchantRecipe> locked = decode(stored);
            if (locked != null && !locked.isEmpty()) {
                villager.setRecipes(locked);
            }
        });
    }

    private NamespacedKey tradeKey(Villager.Profession profession, int level) {
        String professionName = profession.getKey().getKey();
        return new NamespacedKey(plugin, "locked_trades_" + professionName + "_" + level);
    }

    private String encode(List<MerchantRecipe> recipes) {
        if (recipes == null || recipes.isEmpty()) {
            return null;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(recipes.size());
            for (MerchantRecipe recipe : recipes) {
                out.writeObject(recipe.getResult());
                List<org.bukkit.inventory.ItemStack> ingredients = recipe.getIngredients();
                out.writeInt(ingredients.size());
                for (org.bukkit.inventory.ItemStack ingredient : ingredients) {
                    out.writeObject(ingredient);
                }
                out.writeInt(recipe.getUses());
                out.writeInt(recipe.getMaxUses());
                out.writeBoolean(recipe.hasExperienceReward());
                out.writeInt(recipe.getVillagerExperience());
                out.writeFloat(recipe.getPriceMultiplier());
                out.writeInt(recipe.getDemand());
                out.writeInt(recipe.getSpecialPrice());
            }
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception serializationFailure) {
            plugin.getLogger().warning("[CivAsunder] Не удалось сохранить трейды жителя: "
                    + serializationFailure.getMessage());
            return null;
        }
    }

    private List<MerchantRecipe> decode(String encoded) {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(encoded));
             BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
            int recipeCount = in.readInt();
            List<MerchantRecipe> recipes = new ArrayList<>(recipeCount);
            for (int i = 0; i < recipeCount; i++) {
                org.bukkit.inventory.ItemStack result = (org.bukkit.inventory.ItemStack) in.readObject();
                int ingredientCount = in.readInt();
                List<org.bukkit.inventory.ItemStack> ingredients = new ArrayList<>(ingredientCount);
                for (int j = 0; j < ingredientCount; j++) {
                    ingredients.add((org.bukkit.inventory.ItemStack) in.readObject());
                }
                int uses = in.readInt();
                int maxUses = in.readInt();
                boolean experienceReward = in.readBoolean();
                int villagerExperience = in.readInt();
                float priceMultiplier = in.readFloat();
                int demand = in.readInt();
                int specialPrice = in.readInt();

                MerchantRecipe recipe = new MerchantRecipe(result, uses, maxUses, experienceReward,
                        villagerExperience, priceMultiplier, demand, specialPrice);
                recipe.setIngredients(ingredients);
                recipes.add(recipe);
            }
            return recipes;
        } catch (Exception deserializationFailure) {
            plugin.getLogger().warning("[CivAsunder] Не удалось прочитать сохранённые трейды жителя: "
                    + deserializationFailure.getMessage());
            return null;
        }
    }
}
