package kr.server.immortalenchant;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class ImmortalEnchantPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey typeKey, rolledKey, stageKey, optionsKey, itemIdKey, projectileMultiplierKey, statsVersionKey;
    private final Random random = new Random();
    private final Set<UUID> glowOwned = new HashSet<>();
    private final Map<UUID, GlowBackup> glowBackups = new HashMap<>();
    private final Map<UUID, MenuSession> menuSessions = new HashMap<>();
    private boolean armorDebugEnabled = false;

    private enum Tier { WEAK, NORMAL, STRONG }
    private record Option(String id, Tier tier, double value) {}
    private record MenuSession(String title, String itemId) {}
    private record GlowBackup(PotionEffect effect, long expiresAtMillis) {}

    private static final String ROLL_TITLE = "불멸의 잠재력 개방";
    private static final String UPGRADE_TITLE = "불멸 장비 강화";
    private static final Map<String, String> NAMES = Map.of(
            "sword", "불멸의 단죄", "axe", "불멸의 분쇄자", "pickaxe", "불멸의 개척자",
            "crossbow", "불멸의 심판자", "helmet", "불멸의 관", "chestplate", "불멸의 심장갑",
            "leggings", "불멸의 전투각반", "boots", "불멸의 발걸음");

    @Override public void onEnable() {
        saveDefaultConfig();
        // 기존 서버 config.yml에도 새 버전의 신규 설정 키만 보충하고 기존 사용자 설정은 덮어쓰지 않습니다.
        getConfig().options().copyDefaults(true);
        saveConfig();
        typeKey = new NamespacedKey(this, "immortal_type");
        rolledKey = new NamespacedKey(this, "options_rolled");
        stageKey = new NamespacedKey(this, "upgrade_stage");
        optionsKey = new NamespacedKey(this, "rolled_options");
        itemIdKey = new NamespacedKey(this, "item_id");
        projectileMultiplierKey = new NamespacedKey(this, "projectile_multiplier");
        statsVersionKey = new NamespacedKey(this, "stats_version");
        Objects.requireNonNull(getCommand("immortal"), "plugin.yml command 'immortal' is missing").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::refreshGlow, 1L, 20L);
        getServer().getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().forEach(this::ensureEquippedStats));
        getLogger().info("불멸 인챈트 플러그인이 활성화되었습니다.");
    }

    @Override public void onDisable() {
        for (UUID id : new HashSet<>(glowOwned)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) removeOwnedGlow(player);
        }
        glowOwned.clear();
        glowBackups.clear();
        menuSessions.clear();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color("&b/immortal give <sword|axe|pickaxe|crossbow|helmet|chestplate|leggings|boots>"));
            sender.sendMessage(color("&b/immortal enchant &7- 최초 옵션 생성 GUI"));
            sender.sendMessage(color("&b/immortal anvil &7- 강화 GUI"));
            sender.sendMessage(color("&b/immortal stats &7- 현재 적용된 능력치 확인"));
            sender.sendMessage(color("&b/immortal debug <on|off|status> &7- 초과 방어력 디버그"));
            return true;
        }
        if (args[0].equalsIgnoreCase("debug")) {
            if (!sender.hasPermission("immortalenchant.admin")) { msg(sender, "no-permission"); return true; }
            if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
                sender.sendMessage(color("&b초과 방어력 디버그: " + (armorDebugEnabled ? "&a켜짐" : "&c꺼짐")));
                return true;
            }
            if (args[1].equalsIgnoreCase("on")) armorDebugEnabled = true;
            else if (args[1].equalsIgnoreCase("off")) armorDebugEnabled = false;
            else {
                sender.sendMessage(color("&c사용법: /immortal debug <on|off|status>"));
                return true;
            }
            sender.sendMessage(color("&a초과 방어력 디버그를 " + (armorDebugEnabled ? "켜짐" : "꺼짐") + " 상태로 변경했습니다."));
            getLogger().info("Armor debug " + (armorDebugEnabled ? "enabled" : "disabled") + " by " + sender.getName());
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("immortalenchant.admin")) { msg(sender, "no-permission"); return true; }
            reloadConfig();
            sender.sendMessage(color("&a불멸 인챈트 설정을 다시 불러왔습니다."));
            return true;
        }
        if (!(sender instanceof Player player)) { msg(sender, "player-only"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (!player.hasPermission("immortalenchant.admin")) { msg(player, "no-permission"); return true; }
                if (args.length < 2 || !NAMES.containsKey(args[1].toLowerCase(Locale.ROOT))) {
                    player.sendMessage(color("&c사용법: /immortal give <sword|axe|pickaxe|crossbow|helmet|chestplate|leggings|boots>"));
                    return true;
                }
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(createItem(args[1].toLowerCase(Locale.ROOT)));
                leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                player.sendMessage(color("&a" + NAMES.get(args[1].toLowerCase(Locale.ROOT)) + "&a 지급 완료. 테스트용 장비입니다."));
            }
            case "enchant" -> openRollMenu(player);
            case "anvil", "upgrade" -> openUpgradeMenu(player);
            case "stats" -> showStats(player);
            default -> player.sendMessage(color("&c알 수 없는 명령어입니다."));
        }
        return true;
    }

    private void showStats(Player player) {
        player.sendMessage(color("&8&m---------------- &b불멸 능력치 확인 &8&m----------------"));
        showAttribute(player, Attribute.ATTACK_DAMAGE, "공격력");
        showAttribute(player, Attribute.ATTACK_SPEED, "공격 속도");
        showAttribute(player, Attribute.MINING_EFFICIENCY, "채굴 효율 보정");
        double[] armorTotals = equippedImmortalArmorTotals(player);
        if (armorTotals != null) {
            double excessArmor = Math.max(0.0, armorTotals[0] - 30.0);
            double bonusReduction = excessArmor * 0.01;
            player.sendMessage(color("&b불멸 방어구 합산 방어력: &f" + String.format(Locale.US, "%.2f", armorTotals[0])));
            player.sendMessage(color("&b불멸 방어구 합산 방어 강도: &f" + String.format(Locale.US, "%.2f", armorTotals[1])));
            player.sendMessage(color("&7바닐라 방어력 상한: &f30.00"));
            player.sendMessage(color("&7초과 방어력: &f" + String.format(Locale.US, "%.2f", excessArmor)));
            player.sendMessage(color("&a초과 방어력 추가 피해 감소: &f" + String.format(Locale.US, "%.2f%%", bonusReduction * 100.0)));
            player.sendMessage(color("&8초과 방어력 1당 피해 1% 추가 감소"));
        } else {
            player.sendMessage(color("&7불멸 방어구 4부위 착용 시 방어력 보정 정보가 표시됩니다."));
        }
        showAttribute(player, Attribute.KNOCKBACK_RESISTANCE, "넉백 저항");
        showAttribute(player, Attribute.MAX_HEALTH, "최대 체력");
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (isImmortal(hand) && "pickaxe".equals(itemType(hand))) {
            var miningAttribute = player.getAttribute(Attribute.MINING_EFFICIENCY);
            double modifier = miningAttribute == null ? 0 : miningAttribute.getValue();
            player.sendMessage(color("&b곡괭이 예상 채굴 효율: &f" + String.format(Locale.US, "%.2f", 9.0 + modifier)));
        }
        if (isImmortal(hand) && "crossbow".equals(itemType(hand)))
            player.sendMessage(color("&b석궁 빠른 장전: &f" + hand.getEnchantmentLevel(Enchantment.QUICK_CHARGE) + "레벨"));
        player.sendMessage(color("&8&m--------------------------------------------------"));
    }
    private void showAttribute(Player player, Attribute attribute, String label) {
        var instance = player.getAttribute(attribute);
        if (instance != null) player.sendMessage(color("&7" + label + ": &f" + String.format(Locale.US, "%.2f", instance.getValue())));
    }

    private ItemStack createItem(String type) {
        Material material = switch (type) {
            case "sword" -> Material.STONE_SWORD;
            case "axe" -> Material.NETHERITE_AXE;
            case "pickaxe" -> Material.NETHERITE_PICKAXE;
            case "crossbow" -> Material.CROSSBOW;
            case "helmet" -> Material.CHAINMAIL_HELMET;
            case "chestplate" -> Material.CHAINMAIL_CHESTPLATE;
            case "leggings" -> Material.CHAINMAIL_LEGGINGS;
            default -> Material.CHAINMAIL_BOOTS;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&b&l" + NAMES.get(type)));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(typeKey, PersistentDataType.STRING, type);
        pdc.set(itemIdKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        pdc.set(rolledKey, PersistentDataType.BYTE, (byte) 0);
        pdc.set(stageKey, PersistentDataType.INTEGER, 0);
        meta.setUnbreakable(true);
        List<String> initialLore = new ArrayList<>();
        initialLore.add(color("&7불멸 장비"));
        initialLore.addAll(baseStatLore(type));
        initialLore.add(color("&f강화 단계: &e+0"));
        initialLore.add(color("&8아직 잠재력이 개방되지 않았습니다."));
        meta.setLore(initialLore);
        if (type.equals("crossbow")) meta.addEnchant(Enchantment.QUICK_CHARGE, 2, true);
        item.setItemMeta(meta);
        applyBaseStats(item, type);
        return item;
    }

    private void applyBaseStats(ItemStack item, String type) {
        ItemMeta meta = item.getItemMeta();
        hideVanillaAttributeTooltip(meta);
        removeOwnedModifiers(meta);
        double damage = switch (type) { case "sword" -> 12; case "axe" -> 15; case "pickaxe" -> 9; default -> 0; };
        double speed = switch (type) { case "sword" -> 1.6; case "axe" -> 1.0; case "pickaxe" -> 1.2; default -> 0; };
        if (damage > 0) addModifier(meta, Attribute.ATTACK_DAMAGE, "base_damage_" + type, damage - 1.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
        if (speed > 0) addModifier(meta, Attribute.ATTACK_SPEED, "base_speed_" + type, speed - 4.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
        if (type.equals("pickaxe")) addModifier(meta, Attribute.MINING_EFFICIENCY, "base_mining_" + type, 4.5, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
        if (isArmorType(type)) {
            double armor = switch (type) { case "helmet" -> 6; case "chestplate" -> 12; case "leggings" -> 9; default -> 6; };
            double toughness = switch (type) { case "helmet" -> 4; case "chestplate" -> 7; case "leggings" -> 6; default -> 3; };
            double knockback = switch (type) { case "helmet" -> .15; case "chestplate" -> .25; case "leggings", "boots" -> .20; default -> 0; };
            EquipmentSlotGroup slot = slotFor(type);
            addModifier(meta, Attribute.ARMOR, "base_armor_" + type, armor, AttributeModifier.Operation.ADD_NUMBER, slot);
            addModifier(meta, Attribute.ARMOR_TOUGHNESS, "base_toughness_" + type, toughness, AttributeModifier.Operation.ADD_NUMBER, slot);
            addModifier(meta, Attribute.KNOCKBACK_RESISTANCE, "base_knockback_" + type, knockback, AttributeModifier.Operation.ADD_NUMBER, slot);
        }
        meta.getPersistentDataContainer().set(statsVersionKey, PersistentDataType.INTEGER, 3);
        item.setItemMeta(meta);
    }

    // 실제 속성 수치는 그대로 유지하고, 바닐라가 자동 생성하는 중복 속성 설명만 숨깁니다.
    // 기본 공격력 9는 플레이어 기본 공격력 1 + 아이템 수정치 8로 계산되므로 수정치를 변경하면 안 됩니다.
    private void hideVanillaAttributeTooltip(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
    }

    private void addModifier(ItemMeta meta, Attribute attribute, String key, double amount, AttributeModifier.Operation operation, EquipmentSlotGroup slot) {
        meta.addAttributeModifier(attribute, new AttributeModifier(new NamespacedKey(this, key), amount, operation, slot));
    }
    private boolean isArmorType(String type) { return Set.of("helmet", "chestplate", "leggings", "boots").contains(type); }
    private EquipmentSlotGroup slotFor(String type) { return switch (type) { case "helmet" -> EquipmentSlotGroup.HEAD; case "chestplate" -> EquipmentSlotGroup.CHEST; case "leggings" -> EquipmentSlotGroup.LEGS; default -> EquipmentSlotGroup.FEET; }; }

    private boolean isImmortal(ItemStack item) {
        return item != null && !item.getType().isAir() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(typeKey, PersistentDataType.STRING);
    }
    private String itemType(ItemStack item) { return item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING); }
    private int stage(ItemStack item) { return item.getItemMeta().getPersistentDataContainer().getOrDefault(stageKey, PersistentDataType.INTEGER, 0); }
    private boolean rolled(ItemStack item) { return item.getItemMeta().getPersistentDataContainer().getOrDefault(rolledKey, PersistentDataType.BYTE, (byte) 0) == 1; }
    private String ensureItemId(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String id = meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (id == null) { id = UUID.randomUUID().toString(); meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id); item.setItemMeta(meta); }
        return id;
    }

    private void openRollMenu(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isImmortal(held)) { msg(player, "no-item"); return; }
        ensureItemStats(held);
        if (rolled(held)) { msg(player, "already-rolled"); return; }
        String id = ensureItemId(held);
        Inventory inv = Bukkit.createInventory(null, 45, color("&8" + ROLL_TITLE));
        fill(inv);
        inv.setItem(13, held.clone());
        inv.setItem(29, named(Material.EXPERIENCE_BOTTLE, "&e필요 경험치: &f" + getConfig().getInt("initial-roll.experience-levels", 1) + "레벨"));
        inv.setItem(31, named(Material.NETHER_STAR, "&b광물 블록 9종 필요", "&7석탄, 철, 금, 다이아몬드, 에메랄드", "&7청금석, 레드스톤, 네더라이트, 구리"));
        inv.setItem(33, named(Material.ENCHANTING_TABLE, "&a잠재력 개방", "&7클릭하면 재료를 확인하고 옵션을 생성합니다."));
        menuSessions.put(player.getUniqueId(), new MenuSession(ROLL_TITLE, id));
        player.openInventory(inv);
    }

    private void openUpgradeMenu(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isImmortal(held)) { msg(player, "no-item"); return; }
        ensureItemStats(held);
        if (!rolled(held)) { player.sendMessage(color("&c먼저 /immortal enchant로 잠재력을 개방해주세요.")); return; }
        String id = ensureItemId(held);
        Inventory inv = Bukkit.createInventory(null, 45, color("&8" + UPGRADE_TITLE));
        fill(inv);
        inv.setItem(13, held.clone());
        int attempt = stage(held);
        double chance = attempt >= getConfig().getInt("upgrade.max-attempts", 5) ? 0 : getConfig().getDouble("upgrade.success-chances." + attempt, .8 - .1 * attempt);
        inv.setItem(29, named(Material.EXPERIENCE_BOTTLE, "&e필요 경험치: &f" + getConfig().getInt("upgrade.experience-levels", 10) + "레벨"));
        inv.setItem(31, named(Material.AMETHYST_SHARD, "&d자수정 조각: &f" + getConfig().getInt("upgrade.amethyst-shard", 32) + "개"));
        inv.setItem(33, named(Material.ANVIL, attempt >= getConfig().getInt("upgrade.max-attempts", 5) ? "&c강화 최대 단계 도달" : "&a강화하기",
                "&7현재 단계: &e+" + attempt, "&7다음 성공 확률: &e" + (int) (chance * 100) + "%", "&7성공과 실패 모두 강화 단계가 1 상승합니다."));
        menuSessions.put(player.getUniqueId(), new MenuSession(UPGRADE_TITLE, id));
        player.openInventory(inv);
    }

    private void fill(Inventory inventory) {
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) if (i < 9 || i >= 36 || i % 9 == 0 || i % 9 == 8) inventory.setItem(i, glass);
    }
    private ItemStack named(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(Arrays.stream(lore).map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getHand() != EquipmentSlot.HAND) return;
        Material block = event.getClickedBlock().getType();
        boolean enchanting = block == Material.ENCHANTING_TABLE;
        boolean anvil = block == Material.ANVIL || block == Material.CHIPPED_ANVIL || block == Material.DAMAGED_ANVIL;
        if (!enchanting && !anvil) return;

        // 바닐라 인챈트 테이블/모루 UI는 불멸 전용 GUI로 대체합니다.
        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isImmortal(held)) {
            player.sendMessage(color("&c불멸 장비를 손에 들어야 이 작업대를 사용할 수 있습니다."));
            return;
        }
        ensureItemStats(held);
        if (enchanting) {
            if (rolled(held)) { msg(player, "already-rolled"); return; }
            openRollMenu(player);
        } else {
            if (!rolled(held)) {
                player.sendMessage(color("&c먼저 인챈트 테이블에서 잠재력을 개방해주세요."));
                return;
            }
            openUpgradeMenu(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareVanillaEnchant(PrepareItemEnchantEvent event) {
        if (isImmortal(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaEnchant(EnchantItemEvent event) {
        if (isImmortal(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareVanillaAnvil(PrepareAnvilEvent event) {
        Inventory top = event.getInventory();
        if (isImmortal(top.getItem(0)) || isImmortal(top.getItem(1))) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVanillaAnvilClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType() != InventoryType.ANVIL) return;
        Inventory top = event.getView().getTopInventory();
        if (isImmortal(top.getItem(0)) || isImmortal(top.getItem(1)) || isImmortal(event.getCurrentItem()))
            event.setCancelled(true);
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (isOurMenu(title)) event.setCancelled(true);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = stripColor(event.getView().getTitle());
        if (!isOurMenu(title)) return;
        event.setCancelled(true);
        if (event.getRawSlot() != 33 || event.getClickedInventory() == null || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        MenuSession session = menuSessions.get(player.getUniqueId());
        if (session == null || !session.title().equals(title)) { player.closeInventory(); return; }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isImmortal(held) || !session.itemId().equals(ensureItemId(held))) {
            player.sendMessage(color("&c메뉴를 열 때 선택한 장비가 손에 없습니다. 장비를 바꾸지 말고 다시 시도해주세요."));
            player.closeInventory();
            return;
        }
        if (title.equals(ROLL_TITLE)) performRoll(player, held);
        else performUpgrade(player, held);
    }

    private void performRoll(Player player, ItemStack held) {
        if (rolled(held)) { msg(player, "already-rolled"); return; }
        if (!hasRollCost(player)) { msg(player, "not-enough-cost"); return; }
        List<Option> options;
        try { options = rollOptions(itemType(held)); }
        catch (IllegalStateException ex) { player.sendMessage(color("&c설정에서 활성화된 옵션이 부족합니다. 관리자에게 문의해주세요.")); getLogger().warning(ex.getMessage()); return; }
        if (options.size() != 4) { player.sendMessage(color("&c옵션 4개를 구성할 수 없어 재료를 차감하지 않았습니다.")); return; }
        takeRollCost(player);
        storeOptions(held, options);
        ItemMeta meta = held.getItemMeta();
        meta.getPersistentDataContainer().set(rolledKey, PersistentDataType.BYTE, (byte) 1);
        updateLore(held, meta, options);
        applyOptionAttributes(held, options);
        player.sendMessage(color("&8[&b불멸&8] &a장비의 잠재력이 개방되었습니다!"));
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1, 1);
        player.closeInventory();
    }

    private void performUpgrade(Player player, ItemStack held) {
        int attempt = stage(held);
        int maxAttempts = getConfig().getInt("upgrade.max-attempts", 5);
        if (attempt >= maxAttempts) { msg(player, "max-attempts"); return; }
        int xpCost = getConfig().getInt("upgrade.experience-levels", 10);
        int shardCost = getConfig().getInt("upgrade.amethyst-shard", 32);
        if (player.getLevel() < xpCost || count(player, Material.AMETHYST_SHARD) < shardCost) { msg(player, "not-enough-cost"); return; }
        List<Option> options = readOptions(held);
        if (options.size() != 4) { player.sendMessage(color("&c옵션 데이터가 손상되어 강화를 중단했습니다. 재료는 차감되지 않았습니다.")); return; }

        // 모든 검증을 마친 뒤 비용을 차감합니다.
        player.setLevel(player.getLevel() - xpCost);
        remove(player, Material.AMETHYST_SHARD, shardCost);
        double chance = getConfig().getDouble("upgrade.success-chances." + attempt, .8 - .1 * attempt);
        boolean success = random.nextDouble() < chance;
        Option upgraded = null;
        if (success) {
            int selected = random.nextInt(options.size());
            Option old = options.get(selected);
            String incrementPath = itemType(held).equals("pickaxe") && old.id().equals("mining-speed")
                    ? "options.pickaxe-mining-speed.upgrade-increment"
                    : "options." + old.id() + ".upgrade-increment";
            double increment = getConfig().getDouble(incrementPath,
                    getConfig().getDouble("options." + old.id() + ".upgrade-increment", 0));
            if (old.id().equals("attack-damage") && attempt == 4)
                increment += getConfig().getDouble("options.attack-damage.fifth-success-bonus", 0.5);
            upgraded = new Option(old.id(), old.tier(), old.value() + increment);
            options.set(selected, upgraded);
            storeOptions(held, options);
        }
        ItemMeta meta = held.getItemMeta();
        meta.getPersistentDataContainer().set(stageKey, PersistentDataType.INTEGER, attempt + 1);
        updateLore(held, meta, options);
        applyOptionAttributes(held, options);
        player.sendMessage(color("&8[&b불멸&8] " + (success ? "&a강화에 성공했습니다!" : "&c강화에 실패했습니다. 옵션 수치는 유지됩니다.")));
        player.sendMessage(color("&7강화 단계: &e+" + attempt + " &7→ &e+" + (attempt + 1)));
        if (upgraded != null) player.sendMessage(color("&7강화된 옵션: &f" + optionName(upgraded.id()) + " &7(" + format(upgraded.value(), upgraded.id()) + ")"));
        player.playSound(player.getLocation(), success ? Sound.BLOCK_ANVIL_USE : Sound.BLOCK_ANVIL_LAND, 1, 1);
        player.closeInventory();
    }

    private boolean isOurMenu(String title) { String clean = stripColor(title); return clean.equals(ROLL_TITLE) || clean.equals(UPGRADE_TITLE); }
    private String stripColor(String text) { return ChatColor.stripColor(text); }
    @EventHandler public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && isOurMenu(event.getView().getTitle())) menuSessions.remove(player.getUniqueId());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        menuSessions.remove(event.getPlayer().getUniqueId());
        removeOwnedGlow(event.getPlayer());
    }

    private boolean hasRollCost(Player player) {
        if (player.getLevel() < getConfig().getInt("initial-roll.experience-levels", 1)) return false;
        var section = getConfig().getConfigurationSection("initial-roll.required-blocks");
        if (section == null) return false;
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || count(player, material) < section.getInt(key, 1)) return false;
        }
        return true;
    }
    private void takeRollCost(Player player) {
        player.setLevel(player.getLevel() - getConfig().getInt("initial-roll.experience-levels", 1));
        var section = getConfig().getConfigurationSection("initial-roll.required-blocks");
        if (section != null) for (String key : section.getKeys(false)) remove(player, Material.matchMaterial(key), section.getInt(key, 1));
    }
    private int count(Player player, Material material) {
        if (material == null) return 0;
        int amount = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) if (item != null && item.getType() == material) amount += item.getAmount();
        return amount;
    }
    private void remove(Player player, Material material, int amount) {
        if (material == null) return;
        for (int slot = 0; slot < player.getInventory().getStorageContents().length && amount > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType() != material) continue;
            int take = Math.min(amount, item.getAmount());
            item.setAmount(item.getAmount() - take);
            amount -= take;
        }
    }

    private List<Option> rollOptions(String type) {
        List<String> fixed = switch (type) {
            case "sword" -> List.of("attack-damage", "attack-speed");
            case "axe" -> List.of("attack-damage", "mining-speed");
            case "crossbow" -> List.of("projectile-damage", "experience-gain");
            case "pickaxe" -> List.of("mining-speed", "attack-speed");
            default -> List.of("max-health", "armor");
        };
        List<Option> result = new ArrayList<>();
        List<String> enabledFixed = fixed.stream().filter(id -> getConfig().getBoolean("options." + id + ".enabled", true)).toList();
        if (enabledFixed.size() != 2) throw new IllegalStateException("장비 " + type + "의 고정 긍정 옵션 2개 중 비활성화된 옵션이 있습니다.");
        for (String id : enabledFixed) result.add(makeOption(id, type));
        List<String> negatives = negativePool(type);
        if (negatives.isEmpty()) throw new IllegalStateException("장비 " + type + "에 활성화된 부정 옵션이 없습니다.");
        result.add(makeOption(negatives.get(random.nextInt(negatives.size())), type));
        boolean positive = random.nextDouble() < getConfig().getDouble("roll.positive-chance", .5);
        List<String> pool = positive ? positivePool(type) : negatives;
        if (pool.isEmpty()) pool = positive ? negatives : positivePool(type);
        if (pool.isEmpty()) throw new IllegalStateException("장비 " + type + "에 활성화된 네 번째 옵션 후보가 없습니다.");
        result.add(makeOption(pool.get(random.nextInt(pool.size())), type));
        return result;
    }
    private List<String> positivePool(String type) {
        List<String> candidates = switch (type) {
            case "sword" -> List.of("attack-damage", "attack-speed");
            case "axe" -> List.of("attack-damage", "mining-speed");
            case "crossbow" -> List.of("projectile-damage", "experience-gain");
            case "pickaxe" -> List.of("mining-speed", "attack-speed");
            default -> List.of("max-health", "armor", "armor-toughness");
        };
        return candidates.stream().filter(id -> getConfig().getBoolean("options." + id + ".enabled", true)).toList();
    }
    private List<String> negativePool(String type) {
        List<String> candidates = new ArrayList<>(List.of("negative-max-health", "glow"));
        if (Set.of("sword", "axe", "pickaxe").contains(type)) {
            candidates.add("negative-attack-damage");
            candidates.add("negative-attack-speed");
        }
        if (type.equals("crossbow")) candidates.add("negative-projectile-damage");
        return candidates.stream().filter(id -> getConfig().getBoolean("options." + id + ".enabled", true)).toList();
    }
    private Option makeOption(String id, String type) {
        List<String> configuredTiers = getConfig().getStringList("roll.tiers");
        List<String> tiers = configuredTiers.isEmpty() ? List.of("WEAK", "NORMAL", "STRONG") : configuredTiers;
        List<Double> weights = getConfig().getDoubleList("roll.tier-chances");
        double total = 0;
        for (int i = 0; i < tiers.size(); i++) total += i < weights.size() ? Math.max(0, weights.get(i)) : 1.0;
        double roll = random.nextDouble() * (total <= 0 ? tiers.size() : total);
        int chosen = 0;
        for (int i = 0; i < tiers.size(); i++) {
            roll -= total <= 0 ? 1.0 : (i < weights.size() ? Math.max(0, weights.get(i)) : 1.0);
            if (roll < 0) { chosen = i; break; }
        }
        Tier tier;
        try { tier = Tier.valueOf(tiers.get(chosen).toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { tier = Tier.values()[random.nextInt(Tier.values().length)]; }
        String tierKey = tier.name().toLowerCase(Locale.ROOT);
        String path = type.equals("pickaxe") && id.equals("mining-speed")
                ? "options.pickaxe-mining-speed." + tierKey
                : "options." + id + "." + tierKey;
        double fallback = id.equals("glow") ? 2 : 0;
        double value = getConfig().getDouble(path, getConfig().getDouble("options." + id + "." + tierKey, fallback));
        return new Option(id, tier, value);
    }

    private void storeOptions(ItemStack item, List<Option> options) {
        ItemMeta meta = item.getItemMeta();
        String serialized = options.stream().map(option -> option.id() + "~" + option.tier().name() + "~" + option.value())
                .reduce((a, b) -> a + ";" + b).orElse("");
        meta.getPersistentDataContainer().set(optionsKey, PersistentDataType.STRING, serialized);
        item.setItemMeta(meta);
    }
    private List<Option> readOptions(ItemStack item) {
        if (!isImmortal(item)) return new ArrayList<>();
        String raw = item.getItemMeta().getPersistentDataContainer().get(optionsKey, PersistentDataType.STRING);
        List<Option> options = new ArrayList<>();
        if (raw == null || raw.isBlank()) return options;
        for (String part : raw.split(";")) {
            String[] fields = part.split("~");
            if (fields.length != 3) continue;
            try { options.add(new Option(fields[0], Tier.valueOf(fields[1]), Double.parseDouble(fields[2]))); }
            catch (RuntimeException ignored) { getLogger().warning("잘못된 옵션 데이터 항목을 무시했습니다: " + part); }
        }
        return options;
    }

    private void applyOptionAttributes(ItemStack item, List<Option> options) {
        if (!isImmortal(item)) return;
        String type = itemType(item);
        ItemMeta meta = item.getItemMeta();
        hideVanillaAttributeTooltip(meta);
        removeOptionModifiers(meta);
        double damage = 0, attackSpeed = 0, mining = 0, armor = 0, toughness = 0, maxHealth = 0;
        for (Option option : options) {
            switch (option.id()) {
                case "attack-damage", "negative-attack-damage" -> damage += option.value();
                case "attack-speed", "negative-attack-speed" -> attackSpeed += option.value();
                case "mining-speed" -> mining += option.value();
                case "armor" -> armor += option.value();
                case "armor-toughness" -> toughness += option.value();
                case "max-health", "negative-max-health" -> maxHealth += option.value();
            }
        }
        EquipmentSlotGroup slot = isArmorType(type) ? slotFor(type) : EquipmentSlotGroup.MAINHAND;
        if (damage != 0 && Set.of("sword", "axe", "pickaxe").contains(type)) addModifier(meta, Attribute.ATTACK_DAMAGE, "option_attack_damage_" + type, damage, AttributeModifier.Operation.ADD_NUMBER, slot);
        if (attackSpeed != 0 && Set.of("sword", "axe", "pickaxe").contains(type)) addModifier(meta, Attribute.ATTACK_SPEED, "option_attack_speed_" + type, attackSpeed, AttributeModifier.Operation.MULTIPLY_SCALAR_1, slot);
        if (mining != 0 && Set.of("axe", "pickaxe").contains(type)) addModifier(meta, Attribute.MINING_EFFICIENCY, "option_mining_speed_" + type, mining, AttributeModifier.Operation.ADD_NUMBER, slot);
        if (armor != 0 && isArmorType(type)) addModifier(meta, Attribute.ARMOR, "option_armor_" + type, armor, AttributeModifier.Operation.ADD_NUMBER, slot);
        if (toughness != 0 && isArmorType(type)) addModifier(meta, Attribute.ARMOR_TOUGHNESS, "option_toughness_" + type, toughness, AttributeModifier.Operation.ADD_NUMBER, slot);
        if (maxHealth != 0) addModifier(meta, Attribute.MAX_HEALTH, "option_max_health_" + type, maxHealth, AttributeModifier.Operation.ADD_NUMBER, slot);
        item.setItemMeta(meta);
    }
    private void removeOptionModifiers(ItemMeta meta) {
        removeModifiersByPrefix(meta, "option_");
    }
    private void removeOwnedModifiers(ItemMeta meta) {
        removeModifiersByPrefix(meta, "base_");
        removeModifiersByPrefix(meta, "option_");
    }
    private void removeModifiersByPrefix(ItemMeta meta, String prefix) {
        for (Attribute attribute : List.of(Attribute.ATTACK_DAMAGE, Attribute.ATTACK_SPEED, Attribute.MINING_EFFICIENCY,
                Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS, Attribute.KNOCKBACK_RESISTANCE, Attribute.MAX_HEALTH)) {
            Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(attribute);
            if (modifiers == null) continue;
            for (AttributeModifier modifier : new ArrayList<>(modifiers)) {
                NamespacedKey key = modifier.getKey();
                if (key != null && key.getNamespace().equals(getName().toLowerCase(Locale.ROOT)) && key.getKey().startsWith(prefix))
                    meta.removeAttributeModifier(attribute, modifier);
            }
        }
    }
    private void ensureItemStats(ItemStack item) {
        if (!isImmortal(item)) return;
        int version = item.getItemMeta().getPersistentDataContainer().getOrDefault(statsVersionKey, PersistentDataType.INTEGER, 0);
        if (version >= 3) return;
        String type = itemType(item);
        if (version < 3 && type.equals("pickaxe") && rolled(item)) {
            // 기존 곡괭이의 채굴 옵션은 기존 tier 수치와의 차이만 더해 강화 누적분을 보존합니다.
            List<Option> legacyOptions = readOptions(item);
            boolean changed = false;
            for (int i = 0; i < legacyOptions.size(); i++) {
                Option option = legacyOptions.get(i);
                if (!option.id().equals("mining-speed")) continue;
                String tierKey = option.tier().name().toLowerCase(Locale.ROOT);
                double oldBase = getConfig().getDouble("options.mining-speed." + tierKey,
                        switch (option.tier()) { case WEAK -> 1.0; case NORMAL -> 1.5; case STRONG -> 2.0; });
                double newBase = getConfig().getDouble("options.pickaxe-mining-speed." + tierKey,
                        switch (option.tier()) { case WEAK -> 10.0; case NORMAL -> 20.0; case STRONG -> 31.5; });
                legacyOptions.set(i, new Option(option.id(), option.tier(), option.value() + newBase - oldBase));
                changed = true;
            }
            if (changed) storeOptions(item, legacyOptions);
        }
        applyBaseStats(item, type);
        if (rolled(item)) {
            List<Option> options = readOptions(item);
            applyOptionAttributes(item, options);
            updateLore(item, item.getItemMeta(), options);
        } else {
            ItemMeta meta = item.getItemMeta();
            int currentStage = stage(item);
            List<String> lore = new ArrayList<>();
            lore.add(color("&7불멸 장비"));
            lore.addAll(baseStatLore(type));
            lore.add(color("&f강화 단계: &e+" + currentStage));
            lore.add(color("&8아직 잠재력이 개방되지 않았습니다."));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
    }
    private void ensureEquippedStats(Player player) {
        ensureItemStats(player.getInventory().getItemInMainHand());
        ensureItemStats(player.getInventory().getItemInOffHand());
        for (ItemStack armor : player.getInventory().getArmorContents()) ensureItemStats(armor);
    }

    private List<String> baseStatLore(String type) {
        List<String> lines = new ArrayList<>();
        switch (type) {
            case "sword" -> { lines.add(color("&7기본 공격력: &f12")); lines.add(color("&7기본 공격 속도: &f1.6")); }
            case "axe" -> { lines.add(color("&7기본 공격력: &f15")); lines.add(color("&7기본 공격 속도: &f1.0")); }
            case "pickaxe" -> { lines.add(color("&7기본 공격력: &f9")); lines.add(color("&7기본 공격 속도: &f1.2")); lines.add(color("&7기본 채굴 효율: &f13.5")); lines.add(color("&7채굴 옵션 강함: &f일반 돌 즉시 채굴")); }
            case "crossbow" -> { lines.add(color("&7기본 투사체 피해: &f일반 석궁과 동일")); lines.add(color("&7재장전: &f빠른 장전 II")); }
            case "helmet" -> { lines.add(color("&7방어력: &f6")); lines.add(color("&7방어 강도: &f4")); lines.add(color("&7넉백 저항: &f15%")); }
            case "chestplate" -> { lines.add(color("&7방어력: &f12")); lines.add(color("&7방어 강도: &f7")); lines.add(color("&7넉백 저항: &f25%")); }
            case "leggings" -> { lines.add(color("&7방어력: &f9")); lines.add(color("&7방어 강도: &f6")); lines.add(color("&7넉백 저항: &f20%")); }
            case "boots" -> { lines.add(color("&7방어력: &f6")); lines.add(color("&7방어 강도: &f3")); lines.add(color("&7넉백 저항: &f20%")); }
        }
        return lines;
    }

    private void updateLore(ItemStack item, ItemMeta meta, List<Option> options) {
        hideVanillaAttributeTooltip(meta);
        int currentStage = meta.getPersistentDataContainer().getOrDefault(stageKey, PersistentDataType.INTEGER, 0);
        List<String> lore = new ArrayList<>();
        lore.add(color("&7불멸 장비"));
        lore.addAll(baseStatLore(itemType(item)));
        lore.add(color("&f강화 단계: &e+" + currentStage));
        lore.add(color("&8────────────"));
        for (Option option : options) lore.add(color("&b" + optionName(option.id()) + " &7[" + tierName(option.tier()) + "] &f" + format(option.value(), option.id())));
        meta.setLore(lore);
        item.setItemMeta(meta);
    }
    private String optionName(String id) {
        return switch (id) {
            case "attack-damage" -> "공격력"; case "attack-speed" -> "공격 속도"; case "mining-speed" -> "채굴 속도";
            case "projectile-damage" -> "투사체 피해"; case "experience-gain" -> "경험치 획득량"; case "max-health" -> "최대 체력";
            case "armor" -> "방어력"; case "armor-toughness" -> "방어 강도"; case "negative-attack-damage" -> "공격력 감소";
            case "negative-attack-speed" -> "공격 속도 감소"; case "negative-max-health" -> "최대 체력 감소";
            case "negative-projectile-damage" -> "투사체 피해 감소"; case "glow" -> "발광 저주"; default -> id;
        };
    }
    private String tierName(Tier tier) { return switch (tier) { case WEAK -> "약함"; case NORMAL -> "보통"; case STRONG -> "강함"; }; }
    private String format(double value, String id) {
        if (id.contains("speed") || id.contains("projectile") || id.contains("experience")) return String.format(Locale.US, "%.0f%%", value * 100);
        if (id.equals("glow")) return "손에 들면 2초 발광";
        return (value > 0 ? "+" : "") + String.format(Locale.US, "%.1f", value);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        // 불멸 석궁의 투사체 피해 옵션만 적용합니다. 기본 피해 배율은 바닐라와 동일(1.0)입니다.
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Projectile projectile
                && projectile.getPersistentDataContainer().has(projectileMultiplierKey, PersistentDataType.DOUBLE)) {
            double multiplier = projectile.getPersistentDataContainer().getOrDefault(projectileMultiplierKey, PersistentDataType.DOUBLE, 1.0);
            event.setDamage(Math.max(0, event.getDamage() * multiplier));
        }

        if (!(event.getEntity() instanceof Player player)) return;
        double[] totals = equippedImmortalArmorTotals(player);
        if (totals == null) return;

        var armorAttribute = player.getAttribute(Attribute.ARMOR);
        var toughnessAttribute = player.getAttribute(Attribute.ARMOR_TOUGHNESS);
        if (armorAttribute == null || toughnessAttribute == null) return;

        double actualArmor = armorAttribute.getValue();
        double actualToughness = toughnessAttribute.getValue();
        double targetArmor = Math.max(actualArmor, totals[0]);
        double targetToughness = Math.max(actualToughness, totals[1]);
        double rawDamage = event.getDamage();
        double excessArmor = Math.max(0.0, totals[0] - 30.0);
        double excessArmorReduction = excessArmor * 0.01;
        boolean armorMitigatesThisDamage = switch (event.getCause()) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, BLOCK_EXPLOSION, ENTITY_EXPLOSION -> true;
            default -> false;
        };

        boolean applied = armorMitigatesThisDamage
                && (targetArmor > actualArmor + 1.0E-6 || excessArmorReduction > 0)
                && rawDamage > 0;
        double vanillaEstimated = armorAdjustedDamage(rawDamage, actualArmor, actualToughness);
        double targetEstimated = armorAdjustedDamage(rawDamage, targetArmor, targetToughness);
        double finalEstimated = targetEstimated * Math.max(0.0, 1.0 - excessArmorReduction);

        if (applied) {
            // 방어력 30 초과분은 1당 1% 추가 감소시킵니다.
            // 목표 방어력으로 계산한 최종 피해가 바닐라 방어력 계산 후 나오도록 원 피해량을 역산합니다.
            double low = 0.0;
            double high = rawDamage;
            for (int i = 0; i < 64; i++) {
                double mid = (low + high) / 2.0;
                if (armorAdjustedDamage(mid, actualArmor, actualToughness) < finalEstimated) low = mid;
                else high = mid;
            }
            event.setDamage(high);
        }

        if (armorDebugEnabled) {
            String status = applied ? "적용" : (armorMitigatesThisDamage ? "미적용(보정 불필요 또는 피해 0)" : "미적용(대상 피해 유형 아님)");
            String message = String.format(Locale.US,
                    "[ArmorDebug] player=%s cause=%s status=%s | raw=%.3f | armor(total/attribute/used)=%.2f/%.2f/%.2f | toughness(total/attribute/used)=%.2f/%.2f/%.2f | excess=%.2f | extraReduction=%.2f%% | vanillaEstimatedFinal=%.3f | immortalEstimatedFinal=%.3f | eventDamageAfter=%.3f",
                    player.getName(), event.getCause(), status, rawDamage,
                    totals[0], actualArmor, targetArmor,
                    totals[1], actualToughness, targetToughness,
                    excessArmor, excessArmorReduction * 100.0,
                    vanillaEstimated, finalEstimated, event.getDamage());
            getLogger().info(message);
        }
    }

    private double armorAdjustedDamage(double damage, double armor, double toughness) {
        if (damage <= 0) return 0;
        double effectiveArmor = Math.min(20.0, Math.max(armor / 5.0, armor - damage / (2.0 + toughness / 4.0)));
        return damage * (1.0 - effectiveArmor / 25.0);
    }

    // null이면 불멸 방어구 4부위 풀세트가 아닙니다. 배열은 [방어력, 방어 강도]입니다.
    private double[] equippedImmortalArmorTotals(Player player) {
        ItemStack[] armorItems = player.getInventory().getArmorContents();
        if (armorItems.length != 4) return null;
        double armor = 0;
        double toughness = 0;
        Set<String> equippedTypes = new HashSet<>();
        for (ItemStack item : armorItems) {
            if (!isImmortal(item) || !isArmorType(itemType(item))) return null;
            String type = itemType(item);
            if (!equippedTypes.add(type)) return null;
            armor += switch (type) { case "helmet" -> 6; case "chestplate" -> 12; case "leggings" -> 9; case "boots" -> 6; default -> 0; };
            toughness += switch (type) { case "helmet" -> 4; case "chestplate" -> 7; case "leggings" -> 6; case "boots" -> 3; default -> 0; };
            if (rolled(item)) for (Option option : readOptions(item)) {
                if (option.id().equals("armor")) armor += option.value();
                else if (option.id().equals("armor-toughness")) toughness += option.value();
            }
        }
        return equippedTypes.size() == 4 ? new double[]{armor, toughness} : null;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getBow() != null && isImmortal(event.getBow()))) return;
        ItemStack bow = event.getBow();
        if (!"crossbow".equals(itemType(bow))) return;
        double multiplier = 1.0;
        if (rolled(bow)) for (Option option : readOptions(bow)) {
            if (option.id().equals("projectile-damage")) multiplier *= 1.0 + option.value();
            else if (option.id().equals("negative-projectile-damage")) multiplier *= Math.max(0, 1.0 + option.value());
        }
        if (event.getProjectile() instanceof Projectile projectile)
            projectile.getPersistentDataContainer().set(projectileMultiplierKey, PersistentDataType.DOUBLE, multiplier);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExperienceChange(PlayerExpChangeEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (!isImmortal(held) || !rolled(held) || event.getAmount() <= 0) return;
        for (Option option : readOptions(held)) if (option.id().equals("experience-gain"))
            event.setAmount((int) Math.ceil(event.getAmount() * (1.0 + option.value())));
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(this, () -> ensureEquippedStats(event.getPlayer()));
    }
    @EventHandler public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        removeOwnedGlow(player);
        Bukkit.getScheduler().runTask(this, () -> ensureEquippedStats(player));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Bukkit.getScheduler().runTask(this, () -> ensureEquippedStats(event.getPlayer()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEquipmentInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) Bukkit.getScheduler().runTask(this, () -> ensureEquippedStats(player));
    }
    private void refreshGlow() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            boolean cursed = isImmortal(hand) && rolled(hand) && readOptions(hand).stream().anyMatch(option -> option.id().equals("glow"));
            if (cursed) {
                UUID id = player.getUniqueId();
                if (glowOwned.add(id)) {
                    PotionEffect previous = player.getPotionEffect(PotionEffectType.GLOWING);
                    if (previous != null) {
                        long expiry = previous.getDuration() < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + previous.getDuration() * 50L;
                        glowBackups.put(id, new GlowBackup(previous, expiry));
                    }
                }
                int durationTicks = Math.max(25, getConfig().getInt("options.glow.duration-seconds", 2) * 20 + 5);
                player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, durationTicks, 0, true, false, false));
            } else {
                removeOwnedGlow(player);
            }
        }
    }

    private void removeOwnedGlow(Player player) {
        UUID id = player.getUniqueId();
        if (!glowOwned.remove(id)) return;
        player.removePotionEffect(PotionEffectType.GLOWING);
        GlowBackup backup = glowBackups.remove(id);
        if (backup == null) return;
        int remainingTicks = backup.effect().getDuration() < 0 ? -1
                : (int) Math.max(0, (backup.expiresAtMillis() - System.currentTimeMillis()) / 50L);
        if (remainingTicks != 0) {
            PotionEffect old = backup.effect();
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, remainingTicks,
                    old.getAmplifier(), old.isAmbient(), old.hasParticles(), old.hasIcon()));
        }
    }

    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    private void msg(CommandSender sender, String key) {
        sender.sendMessage(color(getConfig().getString("messages.prefix", "&8[&b불멸&8] &r") + getConfig().getString("messages." + key, "&c오류가 발생했습니다.")));
    }
}
