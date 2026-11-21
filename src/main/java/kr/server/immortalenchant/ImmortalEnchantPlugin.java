package kr.server.immortalenchant;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class ImmortalEnchantPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey typeKey, rolledKey, stageKey, optionsKey;
    private final Random random = new Random();
    private final Map<UUID, Long> glowRefresh = new HashMap<>();

    private enum Tier { WEAK, NORMAL, STRONG }
    private enum Kind { POSITIVE, NEGATIVE }
    private record Option(String id, Tier tier, double value) {}

    private static final Map<String, String> NAMES = Map.of(
            "sword", "불멸의 단죄", "axe", "불멸의 분쇄자", "pickaxe", "불멸의 개척자",
            "crossbow", "불멸의 심판자", "helmet", "불멸의 관", "chestplate", "불멸의 심장갑",
            "leggings", "불멸의 전투각반", "boots", "불멸의 발걸음");

    @Override public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "immortal_type");
        rolledKey = new NamespacedKey(this, "options_rolled");
        stageKey = new NamespacedKey(this, "upgrade_stage");
        optionsKey = new NamespacedKey(this, "rolled_options");
        Objects.requireNonNull(getCommand("immortal")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("불멸 인챈트 플러그인이 활성화되었습니다.");
        // 손에 든 장비의 발광 부정 옵션은 2초 단위로 갱신합니다.
        getServer().getScheduler().runTaskTimer(this, this::refreshGlow, 1L, 20L);
    }

    @Override public void onDisable() { glowRefresh.clear(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage(color("&b/immortal give <sword|axe|pickaxe|crossbow|helmet|chestplate|leggings|boots>")); sender.sendMessage(color("&b/immortal enchant &7- 최초 옵션 생성 GUI")); sender.sendMessage(color("&b/immortal anvil &7- 강화 GUI")); return true; }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("immortalenchant.admin")) { msg(sender, "no-permission"); return true; }
            reloadConfig(); sender.sendMessage(color("&a불멸 인챈트 설정을 다시 불러왔습니다.")); return true;
        }
        if (!(sender instanceof Player player)) { msg(sender, "player-only"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> {
                if (!player.hasPermission("immortalenchant.admin")) { msg(player, "no-permission"); return true; }
                if (args.length < 2 || !NAMES.containsKey(args[1].toLowerCase(Locale.ROOT))) { player.sendMessage(color("&c사용법: /immortal give <sword|axe|pickaxe|crossbow|helmet|chestplate|leggings|boots>")); return true; }
                player.getInventory().addItem(createItem(args[1].toLowerCase(Locale.ROOT)));
                player.sendMessage(color("&a" + NAMES.get(args[1].toLowerCase(Locale.ROOT)) + "&a 지급 완료. 테스트용 장비입니다."));
            }
            case "enchant" -> openRollMenu(player);
            case "anvil", "upgrade" -> openUpgradeMenu(player);
            default -> player.sendMessage(color("&c알 수 없는 명령어입니다."));
        }
        return true;
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
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        meta.getPersistentDataContainer().set(rolledKey, PersistentDataType.BYTE, (byte) 0);
        meta.getPersistentDataContainer().set(stageKey, PersistentDataType.INTEGER, 0);
        meta.setUnbreakable(true);
        meta.setLore(List.of(color("&7불멸 장비"), color("&f강화 단계: &e+0"), color("&8아직 잠재력이 개방되지 않았습니다.")));
        item.setItemMeta(meta);
        applyBaseStats(item, type);
        return item;
    }

    private void applyBaseStats(ItemStack item, String type) {
        ItemMeta meta = item.getItemMeta();
        // 기본 피해/공속은 장비의 속성으로 지정합니다. 나머지 옵션 속성은 옵션 생성 시 갱신합니다.
        Attribute damage = Attribute.ATTACK_DAMAGE;
        Attribute speed = Attribute.ATTACK_SPEED;
        double d = switch (type) { case "sword" -> 12; case "axe" -> 15; case "pickaxe" -> 9; default -> 0; };
        double s = switch (type) { case "sword" -> 1.6; case "axe" -> 1.0; case "pickaxe" -> 1.2; default -> 0; };
        if (d > 0) meta.addAttributeModifier(damage, new AttributeModifier(new NamespacedKey(this, "base_damage"), d - 1.0 - vanillaDamage(type), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        if (s > 0) meta.addAttributeModifier(speed, new AttributeModifier(new NamespacedKey(this, "base_speed"), (s - 4.0) - vanillaSpeed(type), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        if (type.equals("helmet") || type.equals("chestplate") || type.equals("leggings") || type.equals("boots")) {
            double armor = switch (type) { case "helmet" -> 6; case "chestplate" -> 12; case "leggings" -> 9; default -> 6; };
            double tough = switch (type) { case "helmet" -> 4; case "chestplate" -> 7; case "leggings" -> 6; default -> 3; };
            double kb = switch (type) { case "helmet" -> .15; case "chestplate" -> .25; case "leggings", "boots" -> .20; default -> 0; };
            EquipmentSlotGroup slot = switch (type) { case "helmet" -> EquipmentSlotGroup.HEAD; case "chestplate" -> EquipmentSlotGroup.CHEST; case "leggings" -> EquipmentSlotGroup.LEGS; default -> EquipmentSlotGroup.FEET; };
            meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(new NamespacedKey(this, "base_armor"), armor - vanillaArmor(type), AttributeModifier.Operation.ADD_NUMBER, slot));
            meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(new NamespacedKey(this, "base_toughness"), tough, AttributeModifier.Operation.ADD_NUMBER, slot));
            meta.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(new NamespacedKey(this, "base_knockback"), kb, AttributeModifier.Operation.ADD_NUMBER, slot));
        }
        item.setItemMeta(meta);
    }
    private double vanillaDamage(String t) { return switch (t) { case "sword" -> 3; case "axe" -> 10; case "pickaxe" -> 5; default -> 0; }; }
    private double vanillaSpeed(String t) { return switch (t) { case "sword" -> -2.4; case "axe" -> -3.1; case "pickaxe" -> -2.8; default -> 0; }; }
    private double vanillaArmor(String t) { return switch (t) { case "helmet" -> 2; case "chestplate" -> 5; case "leggings" -> 4; case "boots" -> 1; default -> 0; }; }

    private boolean isImmortal(ItemStack item) { return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(typeKey, PersistentDataType.STRING); }
    private String itemType(ItemStack item) { return item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING); }
    private int stage(ItemStack item) { return item.getItemMeta().getPersistentDataContainer().getOrDefault(stageKey, PersistentDataType.INTEGER, 0); }
    private boolean rolled(ItemStack item) { return item.getItemMeta().getPersistentDataContainer().getOrDefault(rolledKey, PersistentDataType.BYTE, (byte) 0) == 1; }

    private void openRollMenu(Player p) {
        if (!isImmortal(p.getInventory().getItemInMainHand())) { msg(p, "no-item"); return; }
        Inventory inv = Bukkit.createInventory(null, 45, color("&8불멸의 잠재력 개방"));
        fill(inv);
        inv.setItem(13, p.getInventory().getItemInMainHand().clone());
        inv.setItem(29, named(Material.EXPERIENCE_BOTTLE, "&e필요 경험치: 1레벨", "&7옵션 생성 시 차감됩니다."));
        inv.setItem(31, named(Material.NETHER_STAR, "&b광물 블록 9종 필요", "&7석탄, 철, 금, 다이아몬드, 에메랄드", "&7청금석, 레드스톤, 네더라이트, 구리"));
        inv.setItem(33, named(Material.ENCHANTING_TABLE, "&a잠재력 개방", "&7클릭하면 재료를 확인하고 옵션을 생성합니다."));
        p.openInventory(inv);
    }

    private void openUpgradeMenu(Player p) {
        if (!isImmortal(p.getInventory().getItemInMainHand())) { msg(p, "no-item"); return; }
        ItemStack item = p.getInventory().getItemInMainHand();
        Inventory inv = Bukkit.createInventory(null, 45, color("&8불멸 장비 강화"));
        fill(inv);
        inv.setItem(13, item.clone());
        int n = stage(item);
        double chance = n >= 5 ? 0 : getConfig().getDouble("upgrade.success-chances." + n, .8 - .1 * n);
        inv.setItem(29, named(Material.EXPERIENCE_BOTTLE, "&e필요 경험치: 10레벨"));
        inv.setItem(31, named(Material.AMETHYST_SHARD, "&d자수정 조각: 32개"));
        inv.setItem(33, named(Material.ANVIL, n >= 5 ? "&c강화 최대 단계 도달" : "&a강화하기", "&7현재 단계: &e+" + n, "&7다음 성공 확률: &e" + (int)(chance * 100) + "%", "&7성공과 실패 모두 강화 단계가 1 상승합니다."));
        p.openInventory(inv);
    }

    private void fill(Inventory inv) {
        ItemStack glass = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) if (i < 9 || i >= 36 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, glass);
    }
    private ItemStack named(Material m, String name, String... lore) { ItemStack i = new ItemStack(m); ItemMeta meta = i.getItemMeta(); meta.setDisplayName(color(name)); meta.setLore(Arrays.stream(lore).map(this::color).toList()); i.setItemMeta(meta); return i; }

    @EventHandler public void onInventoryDrag(InventoryDragEvent e) {
        String title = e.getView().getTitle();
        if (title.equals(color("&8불멸의 잠재력 개방")) || title.equals(color("&8불멸 장비 강화"))) e.setCancelled(true);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (!title.equals(color("&8불멸의 잠재력 개방")) && !title.equals(color("&8불멸 장비 강화"))) return;
        e.setCancelled(true);
        if (e.getRawSlot() != 33) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!isImmortal(held)) { msg(p, "no-item"); p.closeInventory(); return; }
        if (title.equals(color("&8불멸의 잠재력 개방"))) {
            if (rolled(held)) { msg(p, "already-rolled"); return; }
            if (!hasRollCost(p)) { msg(p, "not-enough-cost"); return; }
            takeRollCost(p);
            List<Option> options = rollOptions(itemType(held));
            storeOptions(held, options);
            ItemMeta meta = held.getItemMeta(); meta.getPersistentDataContainer().set(rolledKey, PersistentDataType.BYTE, (byte)1); updateLore(held, meta, options);
            p.sendMessage(color("&8[&b불멸&8] &a장비의 잠재력이 개방되었습니다!"));
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1, 1);
            p.closeInventory();
        } else {
            int n = stage(held);
            if (n >= 5) { msg(p, "max-attempts"); return; }
            if (p.getLevel() < getConfig().getInt("upgrade.experience-levels", 10) || count(p, Material.AMETHYST_SHARD) < getConfig().getInt("upgrade.AMETHYST_SHARD", 32)) { msg(p, "not-enough-cost"); return; }
            p.setLevel(p.getLevel() - getConfig().getInt("upgrade.experience-levels", 10)); remove(p, Material.AMETHYST_SHARD, getConfig().getInt("upgrade.AMETHYST_SHARD", 32));
            List<Option> options = readOptions(held);
            boolean success = random.nextDouble() < getConfig().getDouble("upgrade.success-chances." + n, .8 - .1 * n);
            Option upgradedOption = null;
            if (success && !options.isEmpty()) {
                int selected = random.nextInt(options.size()); Option old = options.get(selected);
                double inc = getConfig().getDouble("options." + old.id() + ".upgrade-increment", 0);
                if (old.id().equals("attack-damage") && n == 4) inc += getConfig().getDouble("options.attack-damage.fifth-success-bonus", .5);
                upgradedOption = new Option(old.id(), old.tier(), old.value() + inc);
                options.set(selected, upgradedOption);
                storeOptions(held, options);
            }
            ItemMeta meta = held.getItemMeta(); meta.getPersistentDataContainer().set(stageKey, PersistentDataType.INTEGER, n + 1); updateLore(held, meta, options);
            p.sendMessage(color("&8[&b불멸&8] " + (success ? "&a강화에 성공했습니다!" : "&c강화에 실패했습니다. 옵션 수치는 유지됩니다.")));
            p.sendMessage(color("&7강화 단계: &e+" + n + " &7→ &e+" + (n + 1)));
            if (upgradedOption != null) p.sendMessage(color("&7강화된 옵션: &f" + optionName(upgradedOption.id()) + " &7(" + format(upgradedOption.value(), upgradedOption.id()) + ")"));
            p.playSound(p.getLocation(), success ? Sound.BLOCK_ANVIL_USE : Sound.BLOCK_ANVIL_LAND, 1, 1);
            p.closeInventory();
        }
    }

    private boolean hasRollCost(Player p) { if (p.getLevel() < getConfig().getInt("initial-roll.experience-levels",1)) return false; for (String key : getConfig().getConfigurationSection("initial-roll.required-blocks").getKeys(false)) if (count(p, Material.matchMaterial(key)) < getConfig().getInt("initial-roll.required-blocks."+key,1)) return false; return true; }
    private void takeRollCost(Player p) { p.setLevel(p.getLevel() - getConfig().getInt("initial-roll.experience-levels",1)); for (String key : getConfig().getConfigurationSection("initial-roll.required-blocks").getKeys(false)) remove(p, Material.matchMaterial(key), getConfig().getInt("initial-roll.required-blocks."+key,1)); }
    private int count(Player p, Material m) { if (m == null) return 0; int n=0; for (ItemStack i:p.getInventory().getStorageContents()) if(i!=null && i.getType()==m)n+=i.getAmount(); return n; }
    private void remove(Player p, Material m, int amount) { if(m==null)return; for(int slot=0;slot< p.getInventory().getStorageContents().length && amount>0;slot++){ ItemStack i=p.getInventory().getItem(slot); if(i==null||i.getType()!=m)continue; int take=Math.min(amount,i.getAmount()); i.setAmount(i.getAmount()-take); amount-=take; } }

    private List<Option> rollOptions(String type) {
        List<String> fixed = switch(type) {
            case "sword" -> List.of("attack-damage","attack-speed");
            case "axe" -> List.of("attack-damage","mining-speed");
            case "crossbow" -> List.of("projectile-damage","experience-gain");
            case "pickaxe" -> List.of("mining-speed","attack-speed");
            default -> List.of("max-health","armor");
        };
        List<Option> result = new ArrayList<>();
        for(String id:fixed) result.add(makeOption(id));
        result.add(makeOption(negativePool(type).get(random.nextInt(negativePool(type).size()))));
        boolean positive = random.nextDouble() < getConfig().getDouble("roll.positive-chance", .5);
        List<String> pool = positive ? positivePool(type) : negativePool(type);
        result.add(makeOption(pool.get(random.nextInt(pool.size()))));
        return result;
    }
    private List<String> positivePool(String type) { return switch(type) {
        case "sword" -> List.of("attack-damage","attack-speed");
        case "axe" -> List.of("attack-damage","mining-speed");
        case "crossbow" -> List.of("projectile-damage","experience-gain");
        case "pickaxe" -> List.of("mining-speed","attack-speed");
        default -> List.of("max-health","armor","armor-toughness");
    }; }
    private List<String> negativePool(String type) { List<String> list=new ArrayList<>(List.of("negative-max-health","glow")); if(type.equals("sword")||type.equals("axe")||type.equals("pickaxe")) { list.add("negative-attack-damage"); list.add("negative-attack-speed"); } if(type.equals("crossbow")) list.add("negative-projectile-damage"); return list; }
    private Option makeOption(String id) { Tier[] tiers=Tier.values(); Tier tier=tiers[random.nextInt(tiers.length)]; String path="options."+id+"."+tier.name().toLowerCase(Locale.ROOT); double value=getConfig().getDouble(path, id.equals("glow")?2:0); return new Option(id,tier,value); }
    private void storeOptions(ItemStack item,List<Option> options) { ItemMeta meta=item.getItemMeta(); meta.getPersistentDataContainer().set(optionsKey,PersistentDataType.STRING,options.stream().map(o->o.id()+"~"+o.tier().name()+"~"+o.value()).reduce((a,b)->a+";"+b).orElse("")); item.setItemMeta(meta); }
    private List<Option> readOptions(ItemStack item) { String raw=item.getItemMeta().getPersistentDataContainer().get(optionsKey,PersistentDataType.STRING); List<Option> out=new ArrayList<>(); if(raw==null||raw.isBlank())return out; for(String part:raw.split(";")){String[] p=part.split("~"); if(p.length==3)try{out.add(new Option(p[0],Tier.valueOf(p[1]),Double.parseDouble(p[2])));}catch(Exception ignored){}} return out; }
    private void updateLore(ItemStack item,ItemMeta meta,List<Option> options) { int s=meta.getPersistentDataContainer().getOrDefault(stageKey,PersistentDataType.INTEGER,0); List<String> lore=new ArrayList<>(List.of(color("&7불멸 장비"),color("&f강화 단계: &e+"+s),color("&8────────────"))); for(Option o:options) lore.add(color("&b"+optionName(o.id())+" &7["+tierName(o.tier())+"] &f"+format(o.value(),o.id()))); meta.setLore(lore); item.setItemMeta(meta); }
    private String optionName(String id) { return switch(id) { case "attack-damage" -> "공격력"; case "attack-speed" -> "공격 속도"; case "mining-speed" -> "채굴 속도"; case "projectile-damage" -> "투사체 피해"; case "experience-gain" -> "경험치 획득량"; case "max-health" -> "최대 체력"; case "armor" -> "방어력"; case "armor-toughness" -> "방어 강도"; case "negative-attack-damage" -> "공격력 감소"; case "negative-attack-speed" -> "공격 속도 감소"; case "negative-max-health" -> "최대 체력 감소"; case "negative-projectile-damage" -> "투사체 피해 감소"; case "glow" -> "발광 저주"; default -> id; }; }
    private String tierName(Tier t) { return switch(t){case WEAK->"약함";case NORMAL->"보통";case STRONG->"강함";}; }
    private String format(double v,String id) { if(id.contains("speed")||id.contains("projectile")||id.contains("experience"))return String.format(Locale.US,"%.0f%%",v*100); if(id.equals("glow"))return "손에 들면 2초 발광"; return (v>0?"+":"")+String.format(Locale.US,"%.1f",v); }

    @EventHandler public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return; ItemStack item=p.getInventory().getItemInMainHand(); if(!isImmortal(item)||!rolled(item))return;
        for(Option o:readOptions(item)) { if(o.id().equals("attack-damage")) e.setDamage(e.getDamage()+o.value()); if(o.id().equals("negative-attack-damage")) e.setDamage(Math.max(0,e.getDamage()+o.value())); }
    }
    @EventHandler public void onBlockBreak(BlockBreakEvent e) {
        ItemStack item=e.getPlayer().getInventory().getItemInMainHand(); if(!isImmortal(item)||!rolled(item))return;
        for(Option o:readOptions(item)) if(o.id().equals("experience-gain") && e.getExpToDrop()>0) e.setExpToDrop((int)Math.ceil(e.getExpToDrop()*(1+o.value())));
    }
    @EventHandler public void onHeld(PlayerItemHeldEvent e) { e.getPlayer().removePotionEffect(PotionEffectType.GLOWING); }
    private void refreshGlow() {
        for(Player p:Bukkit.getOnlinePlayers()) { ItemStack hand=p.getInventory().getItemInMainHand(); boolean glow=isImmortal(hand)&&rolled(hand)&&readOptions(hand).stream().anyMatch(o->o.id().equals("glow")); if(glow)p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,45,0,true,false,false)); else if(p.hasPotionEffect(PotionEffectType.GLOWING) && glowRefresh.containsKey(p.getUniqueId())) p.removePotionEffect(PotionEffectType.GLOWING); if(glow)glowRefresh.put(p.getUniqueId(),System.currentTimeMillis()); else glowRefresh.remove(p.getUniqueId()); }
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&',s); }
    private void msg(CommandSender s,String key) { s.sendMessage(color(getConfig().getString("messages.prefix","&8[&b불멸&8] &r")+getConfig().getString("messages."+key,"&c오류가 발생했습니다."))); }
}
