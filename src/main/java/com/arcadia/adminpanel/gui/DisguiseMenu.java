package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.DisguiseManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks a disguise from a grid instead of typing an entity id.
 *
 * <p>Typing {@code /disguise Bob minecraft:zoglin} works, but nobody remembers the id of the mob
 * they actually want, least of all in a modpack with four hundred of them. This lists every living
 * type the server knows, drawn with its spawn egg where one exists, with the presentation options on
 * the control strip.</p>
 *
 * <p>The living-type list is expensive to determine (each candidate has to be created and discarded
 * once), so it is computed the first time somebody opens this menu and cached for the rest of the
 * session.</p>
 *
 * @author vyrriox
 */
public class DisguiseMenu extends PagedMenu {

    private static final int SLOT_CLEAR = 46;
    private static final int SLOT_BABY = 47;
    private static final int SLOT_SCALE = 48;
    private static final int SLOT_NAME = 50;
    private static final int SLOT_RANDOM = 51;

    /** Living entity ids, sorted, resolved once per server session. */
    private static volatile List<ResourceLocation> livingTypes;
    /** Cached spawn egg per entity type, so the icon lookup is not repeated on every page turn. */
    private static final Map<ResourceLocation, Item> ICONS = new ConcurrentHashMap<>();

    private final UUID target;
    private final String targetName;

    public static void open(ServerPlayer admin, UUID target, String targetName) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new DisguiseMenu(id, inv, (ServerPlayer) p, target, targetName),
                PanelTitles.of(LanguageHelper.getText("disguise.menu.title", admin) + ": " + targetName)));
    }

    public DisguiseMenu(int id, Inventory playerInv, ServerPlayer admin, UUID target, String targetName) {
        super(id, playerInv, admin);
        this.target = target;
        this.targetName = targetName;
        ensureTypes(admin.getServer());
        rebuild();
    }

    /** Server stop: the registry can differ between worlds, so the cache must not outlive one. */
    public static void invalidate() {
        livingTypes = null;
        ICONS.clear();
    }

    private static void ensureTypes(MinecraftServer server) {
        if (livingTypes != null || server == null) return;
        ServerLevel level = server.overworld();
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (type == null) continue;
            try {
                Entity probe = type.create(level);
                boolean living = probe instanceof LivingEntity;
                if (probe != null) probe.discard();
                if (living) out.add(id);
            } catch (Exception ignored) {
                // A type that throws on creation is a type we must never try to render either.
            }
        }
        out.sort(Comparator.comparing(ResourceLocation::toString));
        livingTypes = out;
    }

    private static Item iconFor(ResourceLocation id) {
        return ICONS.computeIfAbsent(id, key -> {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(key);
            if (type != null) {
                SpawnEggItem egg = SpawnEggItem.byId(type);
                if (egg != null) return egg;
            }
            return Items.ARMOR_STAND;
        });
    }

    // -- Content -------------------------------------------------------------

    @Override
    protected int contentSize() {
        List<ResourceLocation> types = livingTypes;
        return types == null ? 0 : types.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "disguise.menu.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        List<ResourceLocation> types = livingTypes;
        if (types == null) return;
        ResourceLocation id = types.get(index);
        DisguiseManager.DisguiseData current = DisguiseManager.getInstance().getData(target);
        boolean active = current != null && current.type().equals(id);

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        String label = type != null ? type.getDescription().getString() : id.getPath();

        ItemBuilder b = ItemBuilder.of(iconFor(id))
                .name(Component.literal((active ? "§a" : "§f") + label))
                .addLore(Component.literal("§8" + id));
        if (active) {
            b.addLore(Component.literal("§a" + LanguageHelper.getText("disguise.menu.active", admin)));
        } else {
            b.addLore(Component.literal("§7" + LanguageHelper.getText("disguise.menu.click", admin)));
        }
        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        if (!AdminPermissions.DISGUISE.check(admin)) return;
        List<ResourceLocation> types = livingTypes;
        if (types == null || index >= types.size()) return;
        MinecraftServer server = admin.getServer();
        if (server == null) return;

        DisguiseManager.getInstance().setDisguise(target, types.get(index));
        DisguiseManager.getInstance().broadcastUpdate(server, target);
        SoundHelper.success(admin);
        rebuild();
    }

    // -- Controls ------------------------------------------------------------

    @Override
    protected void renderExtraControls() {
        DisguiseManager.DisguiseData current = DisguiseManager.getInstance().getData(target);

        this.getContainer().setItem(SLOT_CLEAR, ItemBuilder.of(Items.BARRIER)
                .name(Component.literal("§c" + LanguageHelper.getText("disguise.menu.clear", admin)))
                .build());

        this.getContainer().setItem(SLOT_RANDOM, ItemBuilder.of(Items.ENDER_PEARL)
                .name(Component.literal("§d" + LanguageHelper.getText("disguise.menu.random", admin)))
                .build());

        if (current == null) return;

        this.getContainer().setItem(SLOT_BABY, ItemBuilder
                .of(current.baby() ? Items.EGG : Items.BONE)
                .name(Component.literal((current.baby() ? "§a" : "§7")
                        + LanguageHelper.getText("disguise.menu.baby", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText(
                        current.baby() ? "misc.on" : "misc.off", admin)))
                .build());

        this.getContainer().setItem(SLOT_SCALE, ItemBuilder.of(Items.SCAFFOLDING)
                .name(Component.literal("§e" + LanguageHelper.getText("disguise.menu.scale", admin)
                        + " §f" + String.format("%.2f", current.scale())))
                .addLore(Component.literal("§8" + LanguageHelper.getText("disguise.menu.scale.hint", admin)))
                .build());

        this.getContainer().setItem(SLOT_NAME, ItemBuilder.of(Items.NAME_TAG)
                .name(Component.literal((current.showMobName() ? "§a" : "§7")
                        + LanguageHelper.getText("disguise.menu.name", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText(
                        current.showMobName() ? "misc.on" : "misc.off", admin)))
                .build());
    }

    @Override
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {
        if (!AdminPermissions.DISGUISE.check(admin)) return;
        MinecraftServer server = admin.getServer();
        if (server == null) return;
        DisguiseManager mgr = DisguiseManager.getInstance();

        switch (slot) {
            case SLOT_CLEAR -> {
                mgr.clearDisguise(target);
                mgr.broadcastUpdate(server, target);
                SoundHelper.success(admin);
            }
            case SLOT_RANDOM -> {
                List<ResourceLocation> types = livingTypes;
                if (types == null || types.isEmpty()) return;
                ResourceLocation id = types.get(server.overworld().getRandom().nextInt(types.size()));
                mgr.setDisguise(target, id);
                mgr.broadcastUpdate(server, target);
                SoundHelper.success(admin);
            }
            case SLOT_BABY -> {
                if (mgr.mutate(target, d -> d.withBaby(!d.baby())) == null) return;
                mgr.broadcastUpdate(server, target);
                SoundHelper.playAt(admin, SoundHelper.CLICK);
            }
            case SLOT_SCALE -> {
                // Left click grows, right click shrinks, in quarter steps between the manager bounds.
                float step = button == 1 ? -0.25F : 0.25F;
                if (mgr.mutate(target, d -> d.withScale(d.scale() + step)) == null) return;
                mgr.broadcastUpdate(server, target);
                SoundHelper.playAt(admin, SoundHelper.CLICK);
            }
            case SLOT_NAME -> {
                if (mgr.mutate(target, d -> d.withShowMobName(!d.showMobName())) == null) return;
                mgr.broadcastUpdate(server, target);
                SoundHelper.playAt(admin, SoundHelper.CLICK);
            }
            default -> { return; }
        }
        com.arcadia.adminpanel.util.AuditManager.record(admin,
                com.arcadia.adminpanel.util.AdminAction.DISGUISE, target, targetName, "");
        rebuild();
    }

    @Override
    protected void goBack() {
        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(target) != null;
        PlayerToolsMenu.open(admin, target, targetName, online);
    }
}
