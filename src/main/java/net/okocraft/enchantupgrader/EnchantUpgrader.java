package net.okocraft.enchantupgrader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.HoverEvent.Action;
import net.milkbowl.vault.economy.Economy;

public class EnchantUpgrader extends JavaPlugin implements CommandExecutor, TabCompleter {

    private FileConfiguration config;
    private FileConfiguration defaultConfig;

    private Economy economy;

    @Override
    public void onEnable() {
        PluginCommand command = Objects.requireNonNull(getCommand("upgradeenchant"),
                "Command is not written in plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);

        saveDefaultConfig();

        config = getConfig();
        defaultConfig = getDefaultConfig();

        if (!setupEconomy()) {
            throw new ExceptionInInitializerError("Cannot load economy.");
        }
    }

    @Override
    public void onDisable() {
    }

    /**
     * /upgrade <enchant>
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (economy == null) {
            sendMessage(sender, "economy-is-not-enabled");
            return false;
        }

        if (!(sender instanceof Player)) {
            sendMessage(sender, "player-only");
            return false;
        }

        if (args.length == 0) {
            sendMessage(sender, "not-enough-arguments");
            return false;
        }

        @SuppressWarnings("deprecation")
        Enchantment specifiedEnchant = Enchantment.getByName(args[0].toUpperCase(Locale.ROOT));
        if (specifiedEnchant == null) {
            sendMessage(sender, "cannot-find-enchant");
            return false;
        }

        Player player = (Player) sender;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            sendMessage(sender, "cannot-upgrade-air");
            return false;
        }

        if (config.getBoolean("upgrade-only") && !item.getEnchantments().containsKey(specifiedEnchant)) {
            sendMessage(sender, "upgrade-only");
            return false;
        }

        @SuppressWarnings("deprecation")
        String enchantName = specifiedEnchant.getName();
        String key = "enchants." + (config.isConfigurationSection("enchants." + enchantName) ? enchantName : "DEFAULT")
                + ".cost.";
        int requiredVanillaLevel = Math.max(0, config.getInt(key + "vanilla-level"));
        int requiredVanillaExperience = Math.max(0, config.getInt(key + "vanilla-experience"));
        double requiredMoney = Math.max(0, config.getDouble(key + "money"));
        List<ItemStack> requiredItems = new ArrayList<ItemStack>() {
            private static final long serialVersionUID = 1L;
            {
                ConfigurationSection section = config.getConfigurationSection(key + "item");
                if (section != null) {

                    section.getKeys(false).forEach(itemSectionKey -> {
                        Material material;
                        try {
                            material = Material.valueOf(section.getString(itemSectionKey + ".material", ""));
                        } catch (IllegalArgumentException e) {
                            return;
                        }

                        String name = section.getString(itemSectionKey + ".name");
                        if (name == null) {
                            return;
                        }
                        List<String> lore = section.getStringList(itemSectionKey + ".lore");
                        ItemStack item = new ItemStack(material);
                        ItemMeta meta = item.getItemMeta();
                        meta.setDisplayName(name);
                        meta.setLore(lore);
                        item.setItemMeta(meta);
                        item.setAmount(section.getInt(itemSectionKey + ".amount", 1));
                        add(item);
                    });
                }
            }
        };

        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            sender.sendMessage(getMessage("notify-cost"));
            if (requiredVanillaLevel != 0) {
                player.sendMessage(getMessage("vanilla-level") + "§7: §b" + requiredVanillaLevel);
            }
            if (requiredVanillaExperience != 0) {
                player.sendMessage(getMessage("vanilla-experience") + "§7: §b" + requiredVanillaExperience);
            }
            if (requiredMoney != 0) {
                player.sendMessage(getMessage("money") + "§7: §b" + requiredMoney);
            }
            if (!requiredItems.isEmpty()) {
                player.sendMessage(getMessage("item") + "§7:");
                requiredItems.forEach(itemStack -> {
                    StringBuilder sb = new StringBuilder();
                    List<String> lore = (itemStack.getItemMeta().hasLore() ? itemStack.getItemMeta().getLore()
                            : new ArrayList<>());
                    for (String loreLine : lore) {
                        sb.append(loreLine).append("\n");
                    }
                    if (sb.length() > 0) {
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    String displayName = itemStack.getItemMeta().getDisplayName();
                    displayName = (displayName == null) ? itemStack.getType().name()
                            : displayName + "§r§7(" + itemStack.getType().name() + ")";
                    TextComponent requiredItemMessage = new TextComponent("  " + displayName);
                    requiredItemMessage.setHoverEvent(
                            new HoverEvent(Action.SHOW_TEXT, new ComponentBuilder(sb.toString()).create()));
                    player.spigot().sendMessage(requiredItemMessage);
                });
            }
            return true;
        }

        int requiredTotalVanillaExp = getExpAtLevel(requiredVanillaLevel) + requiredVanillaExperience;
        if (requiredTotalVanillaExp != 0 && getPlayerExp(player) - requiredTotalVanillaExp < 0) {
            if (player.getLevel() < requiredVanillaLevel) {
                sendMessage(sender, "not-enough-vanilla-level");
            } else {
                sendMessage(sender, "not-enough-vanilla-experience");
            }
            return false;
        }

        if (requiredMoney != 0 && economy.getBalance(player) - requiredMoney < 0) {
            sendMessage(sender, "not-enough-money");
            return false;
        }

        if (!requiredItems.isEmpty()) {
            for (ItemStack itemStack : requiredItems) {
                if (!player.getInventory().containsAtLeast(itemStack, itemStack.getAmount())) {
                    sendMessage(sender, "not-enough-item");
                    return false;
                }
            }
        }

        if (requiredTotalVanillaExp != 0) {
            setPlayerExp(player, getPlayerExp(player) - requiredTotalVanillaExp);
        }

        if (requiredMoney != 0) {
            economy.withdrawPlayer(player, requiredMoney);
        }

        if (!requiredItems.isEmpty()) {
            player.getInventory().removeItem(requiredItems.toArray(new ItemStack[requiredItems.size()]));
        }

        int nextLevel = item.getEnchantments().getOrDefault(specifiedEnchant, 0) + 1;
        item.addUnsafeEnchantment(specifiedEnchant, nextLevel);
        player.getInventory().setItemInMainHand(item);

        player.sendMessage(getMessage("success").replaceAll("%enchant%", enchantName).replaceAll("%level%",
                String.valueOf(nextLevel)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        @SuppressWarnings("deprecation")
        List<String> enchants = Arrays.stream(Enchantment.values()).map(Enchantment::getName)
                .collect(Collectors.toList());
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], enchants, new ArrayList<>());
        }

        if (!enchants.contains(args[0].toUpperCase(Locale.ROOT))) {
            return List.of();
        }

        if (args.length == 2) {
            return StringUtil.copyPartialMatches(args[1], List.of("confirm"), new ArrayList<>());
        }

        return List.of();
    }

    private FileConfiguration getDefaultConfig() {
        InputStream is = Objects.requireNonNull(getResource("config.yml"),
                "Jar do not have config.yml. what happened?");
        return YamlConfiguration.loadConfiguration(new InputStreamReader(is));
    }

    private String getMessage(String key) {
        String fullKey = "messages." + key;
        return ChatColor.translateAlternateColorCodes('&',
                config.getString(fullKey, defaultConfig.getString(fullKey, fullKey)));
    }

    private void sendMessage(CommandSender sender, String key) {
        sender.sendMessage(getMessage(key));
    }

    /**
     * economyをセットする。
     * 
     * @return 成功したらtrue 失敗したらfalse
     */
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("Vault was not found.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return true;
    }

    // Calculate amount of EXP needed to level up
    private static int getExpToLevelUp(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        } else if (level <= 30) {
            return 5 * level - 38;
        } else {
            return 9 * level - 158;
        }
    }

    // Calculate total experience up to a level
    private static int getExpAtLevel(int level) {
        if (level <= 16) {
            return (int) (Math.pow(level, 2) + 6 * level);
        } else if (level <= 31) {
            return (int) (2.5 * Math.pow(level, 2) - 40.5 * level + 360.0);
        } else {
            return (int) (4.5 * Math.pow(level, 2) - 162.5 * level + 2220.0);
        }
    }

    // Calculate player's current EXP amount
    private static int getPlayerExp(Player player) {
        int exp = 0;
        int level = player.getLevel();

        // Get the amount of XP in past levels
        exp += getExpAtLevel(level);

        // Get amount of XP towards next level
        exp += Math.round(getExpToLevelUp(level) * player.getExp());

        return exp;
    }

    // Give or take EXP
    private static void setPlayerExp(Player player, int exp) {
        player.setExp(0);
        player.setLevel(0);
        player.giveExp(exp);
    }
}
