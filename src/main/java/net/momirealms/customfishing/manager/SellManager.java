/*
 *  Copyright (C) <2022> <XiaoMoMi>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.momirealms.customfishing.manager;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import de.tr7zw.changeme.nbtapi.NBTCompound;
import de.tr7zw.changeme.nbtapi.NBTItem;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.customfishing.CustomFishing;
import net.momirealms.customfishing.api.event.SellFishEvent;
import net.momirealms.customfishing.data.PlayerSellData;
import net.momirealms.customfishing.fishing.loot.Item;
import net.momirealms.customfishing.listener.InventoryListener;
import net.momirealms.customfishing.listener.JoinQuitListener;
import net.momirealms.customfishing.listener.WindowPacketListener;
import net.momirealms.customfishing.object.InventoryFunction;
import net.momirealms.customfishing.util.AdventureUtils;
import net.momirealms.customfishing.util.ConfigUtils;
import net.momirealms.customfishing.util.ItemStackUtils;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SellManager extends InventoryFunction {

    private final WindowPacketListener windowPacketListener;
    private final InventoryListener inventoryListener;
    private final JoinQuitListener joinQuitListener;
    private final CustomFishing plugin;
    public static String formula;
    public static String title;
    public static int guiSize;
    public static String msgNotification;
    public static String actionbarNotification;
    public static String titleNotification;
    public static String subtitleNotification;
    public static int titleIn;
    public static int titleStay;
    public static int titleOut;
    public static String[] commands;
    public static Item sellIcon;
    public static Item denyIcon;
    public static Key closeKey;
    public static Key openKey;
    public static Key successKey;
    public static Key denyKey;
    public static Sound.Source soundSource;
    public static HashMap<Integer, ItemStack> guiItems;
    public static HashSet<Integer> functionIconSlots;
    public static HashMap<Material, Float> vanillaPrices = new HashMap<>();
    public static boolean sellLimitation;
    public static int upperLimit;
    private final HashMap<Player, Inventory> inventoryMap;
    private final ConcurrentHashMap<UUID, PlayerSellData> sellDataMap;

    public SellManager(CustomFishing plugin) {
        super();
        this.plugin = plugin;
        this.windowPacketListener = new WindowPacketListener(this);
        this.inventoryListener = new InventoryListener(this);
        this.joinQuitListener = new JoinQuitListener(this);
        this.sellDataMap = new ConcurrentHashMap<>();
        this.inventoryMap = new HashMap<>();
    }

    @Override
    public void load() {
        functionIconSlots = new HashSet<>();
        guiItems = new HashMap<>();
        vanillaPrices = new HashMap<>();
        loadConfig();
        CustomFishing.getProtocolManager().addPacketListener(windowPacketListener);
        Bukkit.getPluginManager().registerEvents(inventoryListener, plugin);
        Bukkit.getPluginManager().registerEvents(joinQuitListener, plugin);
    }

    @Override
    public void unload() {
        for (Player player : this.inventoryMap.keySet()) {
            player.closeInventory();
        }
        this.inventoryMap.clear();
        CustomFishing.getProtocolManager().removePacketListener(windowPacketListener);
        HandlerList.unregisterAll(inventoryListener);
        HandlerList.unregisterAll(joinQuitListener);
    }

    @Override
    public void disable() {
        unload();
        plugin.getDataManager().getDataStorageInterface().saveSellData(sellDataMap.entrySet(), true);
        sellDataMap.clear();
    }

    @Override
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSellData sellData = sellDataMap.remove(uuid);
        if (sellData == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getDataManager().getDataStorageInterface().saveSellData(uuid, sellData, true);
        });
    }

    @Override
    public void onJoin(Player player) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            joinReadData(player, false);
        }, 20);
    }

    public void joinReadData(Player player, boolean force) {
        if (player == null || !player.isOnline()) return;
        PlayerSellData sellData = plugin.getDataManager().getDataStorageInterface().loadSellData(player.getUniqueId(), force);
        if (sellData != null) {
            sellDataMap.put(player.getUniqueId(), sellData);
        }
        // If sql exception or data is locked
        else if (!force) {
            // can still try to load
            if (checkTriedTimes(player.getUniqueId())) {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    joinReadData(player, false);
                }, 20);
            }
            // tried 3 times
            else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    joinReadData(player, true);
                }, 20);
            }
        }
    }

    private void loadConfig() {
        YamlConfiguration config = ConfigUtils.getConfig("sell-fish.yml");
        formula = config.getString("price-formula", "{base} + {bonus} * {size}");
        sellLimitation = config.getBoolean("sell-limitation.enable", false);
        upperLimit = config.getInt("sell-limitation.upper-limit", 10000);
        title = config.getString("container-title");
        guiSize = config.getInt("rows") * 9;
        openKey = config.contains("sounds.open") ? Key.key(config.getString("sounds.open")) : null;
        closeKey = config.contains("sounds.close") ? Key.key(config.getString("sounds.close")) : null;
        successKey = config.contains("sounds.success") ? Key.key(config.getString("sounds.success")) : null;
        denyKey = config.contains("sounds.deny") ? Key.key(config.getString("sounds.deny")) : null;
        soundSource = Sound.Source.valueOf(config.getString("sounds.type","player").toUpperCase());
        if (config.contains("decorative-icons")){
            ConfigurationSection dec_section = config.getConfigurationSection("decorative-icons");
            if (dec_section != null) {
                for (String key : dec_section.getKeys(false)) {
                    ConfigurationSection item_section = dec_section.getConfigurationSection(key);
                    if (item_section == null) continue;
                    Item item = new Item(item_section, key);
                    ItemStack itemStack = ItemStackUtils.getFromItem(item);
                    if (item_section.contains("slots")) {
                        for (int slot : item_section.getIntegerList("slots")) {
                            guiItems.put(slot - 1, itemStack);
                        }
                    }
                }
            }
        }

        ConfigurationSection sellIconSection = config.getConfigurationSection("functional-icons.sell");
        if (sellIconSection != null) {
            sellIcon = new Item(sellIconSection, "sellIcon");
        }
        else {
            AdventureUtils.consoleMessage("<red>[CustomFishing] Sell icon is missing");
        }
        ConfigurationSection denyIconSection = config.getConfigurationSection("functional-icons.deny");
        if (denyIconSection != null) {
            denyIcon = new Item(denyIconSection, "denyIcon");
        }
        else {
            AdventureUtils.consoleMessage("<red>[CustomFishing] Deny icon is missing");
        }

        for (int slot : config.getIntegerList("functional-icons.slots")) {
            guiItems.put(slot - 1, ItemStackUtils.getFromItem(sellIcon));
            functionIconSlots.add(slot - 1);
        }

        if (config.getBoolean("actions.message.enable", false)) {
            msgNotification = config.getString("actions.message.text");
        } else msgNotification = null;
        if (config.getBoolean("actions.actionbar.enable", false)) {
            actionbarNotification = config.getString("actions.actionbar.text");
        } else actionbarNotification = null;
        if (config.getBoolean("actions.title.enable", false)) {
            titleNotification = config.getString("actions.title.title");
            subtitleNotification = config.getString("actions.title.subtitle");
            titleIn = config.getInt("actions.title.in");
            titleStay = config.getInt("actions.title.stay");
            titleOut = config.getInt("actions.title.out");
        } else titleNotification = null;
        if (config.getBoolean("actions.commands.enable")) {
            commands = config.getStringList("actions.commands.value").toArray(new String[0]);
        } else commands = null;

        ConfigurationSection configurationSection = config.getConfigurationSection("vanilla-item-price");
        if (configurationSection != null) {
            for (String vanilla : configurationSection.getKeys(false)) {
                vanillaPrices.put(Material.valueOf(vanilla.toUpperCase()), (float) configurationSection.getDouble(vanilla));
            }
        }
    }

    public void openGuiForPlayer(Player player) {
        player.closeInventory();
        if (!sellDataMap.containsKey(player.getUniqueId())) {
            AdventureUtils.consoleMessage("<red>Sell cache is not loaded for player " + player.getName());
            return;
        }
        Inventory inventory = plugin.getVersionHelper().isSpigot() ? Bukkit.createInventory(player, guiSize, AdventureUtils.replaceMiniMessage(SellManager.title.replace("{player}", player.getName()))) : Bukkit.createInventory(player, guiSize, "{CustomFishing_Sell}");
        for (Map.Entry<Integer, ItemStack> entry : guiItems.entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue());
        }
        for (int slot : functionIconSlots) {
            inventory.setItem(slot, ItemStackUtils.getFromItem(sellIcon.cloneWithPrice(getTotalPrice(getPlayerItems(inventory)))));
        }
        inventoryMap.put(player, inventory);
        player.openInventory(inventory);
        if (openKey != null) AdventureUtils.playerSound(player, soundSource, openKey, 1, 1);
    }

    @Override
    public void onClickInventory(InventoryClickEvent event) {
        final Player player = (Player) event.getView().getPlayer();
        Inventory inventory = inventoryMap.get(player);
        if (inventory == null) return;
        boolean update = true;
        if (inventory == event.getClickedInventory()) {
            int clickedSlot = event.getSlot();
            if (guiItems.containsKey(clickedSlot)) {
                event.setCancelled(true);
            }
            if (functionIconSlots.contains(clickedSlot)) {
                List<ItemStack> playerItems = getPlayerItems(inventory);
                float totalPrice = getTotalPrice(playerItems);

                if (totalPrice > 0) {

                    PlayerSellData sellData = sellDataMap.get(player.getUniqueId());

                    if (sellData == null) {
                        inventory.close();
                        AdventureUtils.playerMessage(player, MessageManager.prefix + "Your data is not loaded! Try to rejoin the server");
                        AdventureUtils.consoleMessage("<red>[CustomFishing] Unexpected issue, " + player.getName() + "'s sell-cache is not loaded!");
                        if (denyKey != null) AdventureUtils.playerSound(player, soundSource, denyKey, 1, 1);
                        return;
                    }

                    Calendar calendar = Calendar.getInstance();
                    int currentDate = (calendar.get(Calendar.MONTH) + 1) * 100 + calendar.get(Calendar.DATE);
                    if (currentDate != sellData.getDate()) {
                        sellData.setDate(currentDate);
                        sellData.setMoney(0);
                    }

                    double sell = sellData.getMoney();

                    if (sellLimitation && sell + totalPrice > upperLimit) {
                        inventory.close();
                        AdventureUtils.playerMessage(player, MessageManager.prefix + MessageManager.reachSellLimit);
                        if (denyKey != null) AdventureUtils.playerSound(player, soundSource, denyKey, 1, 1);
                        return;
                    }

                    SellFishEvent sellFishEvent = new SellFishEvent(player, totalPrice);
                    Bukkit.getPluginManager().callEvent(sellFishEvent);
                    if (sellFishEvent.isCancelled()) {
                        return;
                    }

                    for (ItemStack playerItem : playerItems) {
                        if (playerItem == null || playerItem.getType() == Material.AIR) continue;
                        if (getSingleItemPrice(playerItem) == 0) continue;
                        playerItem.setAmount(0);
                    }

                    sellData.setMoney(sellFishEvent.getMoney() + sell);
                    doActions(player, sellFishEvent.getMoney(), upperLimit - sell - sellFishEvent.getMoney());
                    inventory.close();
                }
                else {
                    for (int slot : functionIconSlots) {
                        inventory.setItem(slot, ItemStackUtils.getFromItem(denyIcon));
                    }
                    update = false;
                    if (denyKey != null) AdventureUtils.playerSound(player, soundSource, denyKey, 1, 1);
                }
            }
        }
        if (update) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                ItemStack icon = ItemStackUtils.getFromItem(sellIcon.cloneWithPrice(getTotalPrice(getPlayerItems(inventory))));
                for (int slot : functionIconSlots) {
                    inventory.setItem(slot, icon);
                }
            });
        }
    }

    @Override
    public void onDragInventory(InventoryDragEvent event) {
        final Player player = (Player) event.getView().getPlayer();
        Inventory inventory = inventoryMap.get(player);
        if (inventory == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ItemStack icon = ItemStackUtils.getFromItem(sellIcon.cloneWithPrice(getTotalPrice(getPlayerItems(inventory))));
            for (int slot : functionIconSlots) {
                inventory.setItem(slot, icon);
            }
        });
    }

    @Override
    public void onCloseInventory(InventoryCloseEvent event) {
        final Player player = (Player) event.getPlayer();
        Inventory inventory = inventoryMap.remove(player);
        if (inventory == null) return;
        if (event.getInventory() == inventory) {
            returnItems(getPlayerItems(event.getInventory()), player);
            if (closeKey != null) AdventureUtils.playerSound(player, soundSource, closeKey, 1, 1);
        }
    }

    private List<ItemStack> getPlayerItems(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < guiSize; i++) {
            if (guiItems.containsKey(i)) continue;
            items.add(inventory.getItem(i));
        }
        return items;
    }

    private void returnItems(List<ItemStack> itemStacks, Player player){
        PlayerInventory inventory = player.getInventory();
        for (ItemStack stack : itemStacks) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            if (hasEmptySlot(inventory)) inventory.addItem(stack);
            else player.getLocation().getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }

    private boolean hasEmptySlot(PlayerInventory inventory) {
        for (ItemStack itemStack : inventory.getStorageContents()) {
            if (itemStack == null || itemStack.getType() == Material.AIR) return true;
        }
        return false;
    }

    private float getTotalPrice(List<ItemStack> itemStacks){
        float totalPrice = 0;
        for (ItemStack stack : itemStacks) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            float price = getSingleItemPrice(stack);
            price *= stack.getAmount();
            totalPrice += price;
        }
        return totalPrice;
    }

    public float getSingleItemPrice(ItemStack itemStack) {
        NBTItem nbtItem = new NBTItem(itemStack);
        NBTCompound fishMeta = nbtItem.getCompound("FishMeta");
        float price = 0;
        if (fishMeta != null) {
            float base = fishMeta.getFloat("base");
            float bonus = fishMeta.getFloat("bonus");
            float size = fishMeta.getFloat("size");
            Expression expression = new ExpressionBuilder(formula)
                    .variables("base", "bonus","size")
                    .build()
                    .setVariable("base", base)
                    .setVariable("bonus", bonus)
                    .setVariable("size", size);
            price = (float) expression.evaluate();
        }
        Double money = Optional.ofNullable(nbtItem.getDouble("Price")).orElse(0d);
        price += money;
        if (price == 0) {
            price = Optional.ofNullable(vanillaPrices.get(itemStack.getType())).orElse(0f);
        }
        return price;
    }

    private void doActions(Player player, float earnings, double remains) {
        if (titleNotification != null) AdventureUtils.playerTitle(player, titleNotification.replace("{money}", String.format("%.2f", earnings)).replace("{remains}", sellLimitation ? String.format("%.2f", remains) : "unlimited"), subtitleNotification.replace("{money}", String.format("%.2f", earnings)).replace("{remains}", sellLimitation ? String.format("%.2f", remains) : "unlimited"), titleIn * 50, titleStay * 50, titleOut * 50);
        if (msgNotification != null) AdventureUtils.playerMessage(player, msgNotification.replace("{money}", String.format("%.2f", earnings)).replace("{remains}", sellLimitation ? String.format("%.2f", remains) : "unlimited"));
        if (actionbarNotification != null) AdventureUtils.playerActionbar(player, actionbarNotification.replace("{money}", String.format("%.2f", earnings)).replace("{remains}", sellLimitation ? String.format("%.2f", remains) : "unlimited"));
        if (ConfigManager.logEarning) AdventureUtils.consoleMessage("[CustomFishing] Log: " + player.getName() + " earns " + String.format("%.2f", earnings) + " from selling fish");
        if (successKey != null) AdventureUtils.playerSound(player, soundSource, successKey, 1, 1);
        if (plugin.getIntegrationManager().getVaultHook() != null) plugin.getIntegrationManager().getVaultHook().getEconomy().depositPlayer(player, earnings);
        if (commands != null)
            for (String cmd : commands)
                Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", player.getName()).replace("{money}", String.format("%.2f", earnings)).replace("{remains}", sellLimitation ? String.format("%.2f", remains) : "unlimited"));
    }

    public double getTodayEarning(Player player) {
        PlayerSellData playerSellData = sellDataMap.get(player.getUniqueId());
        if (playerSellData == null) return 0d;
        Calendar calendar = Calendar.getInstance();
        int currentDate = (calendar.get(Calendar.MONTH) + 1) * 100 + calendar.get(Calendar.DATE);
        if (currentDate != playerSellData.getDate()) {
            playerSellData.setDate(currentDate);
            playerSellData.setMoney(0);
        }
        return playerSellData.getMoney();
    }

    @Override
    public void onWindowTitlePacketSend(PacketContainer packet, Player player) {
        StructureModifier<WrappedChatComponent> wrappedChatComponentStructureModifier = packet.getChatComponents();
        WrappedChatComponent component = wrappedChatComponentStructureModifier.getValues().get(0);
        if (component.getJson().equals("{\"text\":\"{CustomFishing_Sell}\"}")) {
            wrappedChatComponentStructureModifier.write(0,
                    WrappedChatComponent.fromJson(
                            GsonComponentSerializer.gson().serialize(
                                    MiniMessage.miniMessage().deserialize(
                                            AdventureUtils.replaceLegacy(plugin.getIntegrationManager().getPlaceholderManager().parse(player, SellManager.title))
                                    )
                            )
                    )
            );
        }
    }
}
