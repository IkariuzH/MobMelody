package com.mobmusic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Watches the mobs from the config that this client has loaded and decides which
 * song should be playing: the one of the highest-priority mob that is still around.
 *
 * "Around" simply means the client still has the entity loaded, so the detection range
 * is the one the game itself uses for each mob (bosses are tracked from far away).
 */
@SideOnly(Side.CLIENT)
public class MobTracker {

    private static final int CHECK_EVERY_TICKS = 10;
    private static final int CHECKS_BEFORE_PAUSING = 4;

    /** Mobs from the list that this client has loaded right now. */
    private final List<Entity> watched = new ArrayList<>();

    private int tickCounter = 0;
    private int checksWithoutMobs = 0;

    /** Id of the mob whose song is "playing" now (null = nothing is playing). */
    private String playing = null;

    /** Whether the game was paused / the player was dead at the last check. */
    private boolean wasSuspended = false;

    /** When an entity appears: if it is in the list, we watch it. */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent e) {
        if (!e.getWorld().isRemote) {
            return; // client world only
        }
        String id = idOf(e.getEntity());
        if (id != null && MobMusicConfig.isListed(id) && !watched.contains(e.getEntity())) {
            watched.add(e.getEntity());
        }
    }

    /** Every client tick: clean up, pick by priority, tell the extension if something changes. */
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        boolean suspended = mc.world != null && isSuspendedNow(mc);

        // Normally we check every CHECK_EVERY_TICKS ticks, but a change in the
        // paused / dead state is handled right away so the music reacts instantly.
        tickCounter++;
        if (tickCounter < CHECK_EVERY_TICKS && suspended == wasSuspended) {
            return;
        }
        tickCounter = 0;
        wasSuspended = suspended;

        if (mc.world == null) {
            reset();
            return;
        }

        // 1) Drop the ones that are gone (died, went out of range, or the world changed)
        Iterator<Entity> it = watched.iterator();
        while (it.hasNext()) {
            Entity m = it.next();
            if (m.isDead || mc.world.getEntityByID(m.getEntityId()) != m) {
                it.remove();
            }
        }

        // 2) Pick the highest priority: the first entry in the list that has a living mob
        MobMusicConfig.Entry best = null;
        for (MobMusicConfig.Entry entry : MobMusicConfig.ENTRIES) {
            for (Entity m : watched) {
                if (entry.mobId.equals(idOf(m))) {
                    best = entry;
                    break;
                }
            }
            if (best != null) {
                break;
            }
        }

        // 3) Decide what "plays"
        if (best != null) {
            checksWithoutMobs = 0;
            if (suspended) {
                // The mob is still around, but the game is paused or the player is dead:
                // pause the song (soft pause). It resumes by itself when that ends.
                if (playing != null && !MusicState.isSuspended()) {
                    MusicState.suspend();
                    debug("Song suspended (game paused or player dead)");
                }
            } else if (!best.mobId.equals(playing) || MusicState.isSuspended()) {
                playing = best.mobId;
                MusicState.play(best.song, best.mobId);
                debug("Requested song: " + best.song + "  (for " + best.mobId + ")");
            }
        } else if (playing != null) {
            checksWithoutMobs++;
            if (checksWithoutMobs >= CHECKS_BEFORE_PAUSING) {
                debug("Requested pause (no more listed mobs around)");
                reset();
            }
        }
    }

    /** When the client world is closed, clear everything. */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload e) {
        if (e.getWorld().isRemote) {
            reset();
        }
    }

    /** When the config screen is closed with changes: reload the list without restarting the game. */
    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent e) {
        if (!MobMusic.MODID.equals(e.getModID())) {
            return;
        }
        MobMusicConfig.sync();
        rescan();
    }

    /** Looks again at the mobs that were already loaded, in case the list changed. */
    private void rescan() {
        watched.clear();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return;
        }
        for (Entity entity : mc.world.loadedEntityList) {
            String id = idOf(entity);
            if (id != null && !entity.isDead && MobMusicConfig.isListed(id)) {
                watched.add(entity);
            }
        }
    }

    /** Clears everything. If something was playing, sends a hard pause. */
    private void reset() {
        if (playing != null) {
            MusicState.pause();
        }
        watched.clear();
        playing = null;
        checksWithoutMobs = 0;
        tickCounter = 0;
    }

    /**
     * True while the music should be paused even though the mob is still there:
     * the game is paused, or the player is dead.
     *
     * isGamePaused() is only true in single player with a pausing screen open (the
     * world really freezes). In multiplayer the game keeps running, so the music does too.
     */
    private static boolean isSuspendedNow(Minecraft mc) {
        return mc.isGamePaused() || isPlayerDead(mc);
    }

    private static boolean isPlayerDead(Minecraft mc) {
        EntityPlayerSP player = mc.player;
        return mc.currentScreen instanceof GuiGameOver
                || (player != null && (player.isDead || player.getHealth() <= 0.0F));
    }

    /** "minecraft:wither", or "modid:name" for mobs from other mods. */
    private static String idOf(Entity entity) {
        ResourceLocation id = EntityList.getKey(entity);
        return id == null ? null : id.toString();
    }

    private static void debug(String text) {
        if (!MobMusicConfig.DEBUG_CHAT) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player != null) {
            player.sendMessage(new TextComponentString("[MobMusic] " + text));
        }
    }
}
