package me.noramibu.tweaks.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Hotkey {
    public enum Action {
        SwitchSlot,
        HoldItem
    }

    public Action action;
    public Keybind keybind;
    public int presses;
    public int pressDelay;

    // SwitchSlot
    public int slot;

    // HoldItem
    public Identifier itemIdentifier;
    public String customName;
    public String enchantments; // JSON string of ItemEnchantmentsComponent

    // transient state
    public transient int pressCount = 0;
    public transient int delayLeft = 0;

    public Hotkey() {
        this.action = Action.SwitchSlot;
        this.keybind = Keybind.none();
        this.presses = 1;
        this.pressDelay = 10;
        this.slot = 1;
        this.itemIdentifier = Registries.ITEM.getId(Items.AIR);
        this.customName = null;
        this.enchantments = null;
    }

    public void fromItemStack(ItemStack stack) {
        this.itemIdentifier = Registries.ITEM.getId(stack.getItem());

        Text nameText = stack.getName();
        this.customName = nameText != null ? nameText.getString() : null;

        ItemEnchantmentsComponent enchantmentsComponent = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantmentsComponent != null && !enchantmentsComponent.isEmpty()) {
            List<String> enchantList = new ArrayList<>();
            for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : enchantmentsComponent.getEnchantmentEntries()) {
                entry.getKey().getKey().ifPresent(key -> {
                    enchantList.add(key.getValue().toString() + "=" + entry.getIntValue());
                });
            }
            Collections.sort(enchantList);
            this.enchantments = String.join(";", enchantList);
        } else {
            this.enchantments = null;
        }
    }

    public ItemStack toItemStack() {
        if (itemIdentifier == null) return new ItemStack(Items.AIR);

        Item item = Registries.ITEM.get(itemIdentifier);
        ItemStack stack = new ItemStack(item);

        if (customName != null) {
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(customName));
        }

        return stack;
    }

    public boolean matches(ItemStack otherStack) {
        if (this.itemIdentifier == null) {
            return otherStack == null || otherStack.isEmpty();
        }
        if (otherStack == null || otherStack.isEmpty()) {
            return this.itemIdentifier.equals(Registries.ITEM.getId(Items.AIR));
        }

        if (!Registries.ITEM.getId(otherStack.getItem()).equals(this.itemIdentifier)) {
            return false;
        }

        Text otherNameText = otherStack.getName();
        String otherNameString = otherNameText != null ? otherNameText.getString() : null;
        if (!Objects.equals(this.customName, otherNameString)) {
            return false;
        }

        ItemEnchantmentsComponent otherEnchants = otherStack.get(DataComponentTypes.ENCHANTMENTS);

        if (this.enchantments == null) {
            return otherEnchants == null || otherEnchants.isEmpty();
        }

        if (otherEnchants == null || otherEnchants.isEmpty()) return false;

        List<String> otherEnchantList = new ArrayList<>();
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : otherEnchants.getEnchantmentEntries()) {
            entry.getKey().getKey().ifPresent(key -> {
                otherEnchantList.add(key.getValue().toString() + "=" + entry.getIntValue());
            });
        }
        Collections.sort(otherEnchantList);
        String otherEnchantsString = String.join(";", otherEnchantList);

        return this.enchantments.equals(otherEnchantsString);
    }

    public void resetState() {
        pressCount = 0;
        delayLeft = 0;
    }

    public JsonObject toJson() {
        Gson gson = new Gson();
        JsonObject object = new JsonObject();
        object.addProperty("action", action.name());
        object.add("keybind", gson.toJsonTree(keybind));
        object.addProperty("presses", presses);
        object.addProperty("pressDelay", pressDelay);
        object.addProperty("slot", slot);

        if (itemIdentifier != null) {
            object.addProperty("itemIdentifier", itemIdentifier.toString());
        }
        if (customName != null) {
            object.addProperty("customName", customName);
        }
        if (enchantments != null) {
            object.addProperty("enchantments", enchantments);
        }

        return object;
    }

    public static Hotkey fromJson(JsonObject object) {
        Hotkey hotkey = new Hotkey();

        if (object.has("action")) {
            try {
                hotkey.action = Action.valueOf(object.get("action").getAsString());
            } catch (IllegalArgumentException ignored) {}
        }
        if (object.has("keybind")) {
            hotkey.keybind = new Gson().fromJson(object.get("keybind"), Keybind.class);
        }
        if (object.has("presses")) {
            hotkey.presses = object.get("presses").getAsInt();
        }
        if (object.has("pressDelay")) {
            hotkey.pressDelay = object.get("pressDelay").getAsInt();
        }
        if (object.has("slot")) {
            hotkey.slot = object.get("slot").getAsInt();
        }

        if (object.has("itemIdentifier")) {
            hotkey.itemIdentifier = Identifier.tryParse(object.get("itemIdentifier").getAsString());
        }
        if (object.has("customName")) {
            hotkey.customName = object.get("customName").getAsString();
        }
        if (object.has("enchantments")) {
            hotkey.enchantments = object.get("enchantments").getAsString();
        }

        return hotkey;
    }
}
