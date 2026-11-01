package com.mobmusic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Silences the game's own sound categories (the music, by default) while a song from
 * YouTube Music is playing.
 *
 * How it works, and why it is done this way:
 *
 * - Minecraft 1.12.2 has no "live volume" for a single category. SoundHandler.setSoundLevel()
 *   only has a real effect for MASTER; for any other category it just re-applies the volume
 *   saved in the game options, so setting MUSIC to 0 through it does nothing.
 * - Changing the volume in the game options would work, but those values are written to
 *   options.txt by the game (F11, the options screen...), and the player could end up with
 *   the music slider stuck at 0. This mod promises never to touch options.txt.
 *
 * So instead: while "muted", every new sound of a muted category is cancelled before it
 * starts (PlaySoundEvent), and the sounds of those categories that were already playing
 * are stopped. Nothing needs to be restored afterwards, except MASTER, which does have a
 * real live volume. The game's own music comes back by itself on its normal schedule.
 */
@SideOnly(Side.CLIENT)
public class GameMuter {

    private static final int CHECK_EVERY_TICKS = 5;

    /** How long we wait for the extension to confirm "playing" before giving up. */
    private static final long CONFIRM_TIMEOUT_MS = 8000;

    /** How long we wait for the extension to confirm "paused" before un-muting anyway. */
    private static final long PAUSE_TIMEOUT_MS = 3000;

    private int tickCounter = 0;
    private volatile boolean muted = false;

    /** The categories we are muting right now (copied from the config when muting starts). */
    private final List<SoundCategory> mutedCategories = new CopyOnWriteArrayList<>();

    /**
     * Sounds of the configured categories that the engine has started, so we can stop them
     * when muting begins. Pruned every check.
     */
    private final List<ISound> tracked = new CopyOnWriteArrayList<>();

    /** Every sound the game wants to play passes through here first. */
    @SubscribeEvent
    public void onPlaySound(PlaySoundEvent e) {
        ISound sound = e.getSound();
        if (sound == null || !MobMusicConfig.MUTE_GAME) {
            return;
        }
        SoundCategory category = sound.getCategory();
        if (category == null || category == SoundCategory.MASTER) {
            return; // MASTER is handled with its live volume, not by cancelling sounds
        }

        if (muted) {
            if (mutedCategories.contains(category)) {
                e.setResultSound(null); // cancelled: it never starts
            }
        } else if (MobMusicConfig.MUTED_CATEGORIES.contains(category)) {
            tracked.add(sound); // remember it, in case it is still playing when we need to mute
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        tickCounter++;
        if (tickCounter < CHECK_EVERY_TICKS) {
            return;
        }
        tickCounter = 0;
        check();
    }

    /** If the world is closed while muted, go back to normal. */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload e) {
        if (e.getWorld().isRemote) {
            if (muted) {
                restore();
            }
            tracked.clear();
        }
    }

    private void check() {
        Minecraft mc = Minecraft.getMinecraft();
        pruneTracked(mc);
        boolean shouldMute = false;

        if (MobMusicConfig.MUTE_GAME && mc.world != null) {
            long sinceChange = System.currentTimeMillis() - MusicState.getChangedAtMs();
            String report = MusicState.getReport();

            if (MusicState.getCommand().equals("play")) {
                // Mute while the song plays, or while we wait for the extension to confirm.
                // On "failed", or when the wait runs out with no answer, do NOT mute.
                if (report.equals("playing")) {
                    shouldMute = true;
                } else if (report.isEmpty() && sinceChange < CONFIRM_TIMEOUT_MS) {
                    shouldMute = true;
                }
            } else if (MusicState.isSuspended()) {
                // The mob is still around and the song only paused (game paused or player dead).
                // Stay muted so the game music does not start again for a moment.
                if (muted) {
                    shouldMute = true;
                }
            } else {
                // Hard stop: stay muted until the extension confirms the pause, or until
                // the timeout runs out.
                if (muted && !report.equals("paused") && sinceChange < PAUSE_TIMEOUT_MS) {
                    shouldMute = true;
                }
            }
        }

        if (shouldMute && !muted) {
            mute();
        } else if (!shouldMute && muted) {
            restore();
        }
    }

    /** Forgets the sounds that already finished. */
    private void pruneTracked(Minecraft mc) {
        SoundHandler handler = mc.getSoundHandler();
        List<ISound> finished = new ArrayList<>();
        for (ISound sound : tracked) {
            if (!handler.isSoundPlaying(sound)) {
                finished.add(sound);
            }
        }
        tracked.removeAll(finished);
    }

    private void mute() {
        Minecraft mc = Minecraft.getMinecraft();
        SoundHandler handler = mc.getSoundHandler();

        mutedCategories.clear();
        mutedCategories.addAll(MobMusicConfig.MUTED_CATEGORIES);
        muted = true; // from now on, new sounds of those categories are cancelled

        // MASTER is the one category with a real live volume.
        if (mutedCategories.contains(SoundCategory.MASTER)) {
            handler.setSoundLevel(SoundCategory.MASTER, 0.0F);
        }

        // Stop what is already playing in the muted categories (the game music, usually).
        for (ISound sound : tracked) {
            if (mutedCategories.contains(sound.getCategory())) {
                handler.stopSound(sound);
            }
        }
        tracked.clear();

        MobMusic.LOGGER.info("Game sound muted: " + mutedCategories);
    }

    private void restore() {
        Minecraft mc = Minecraft.getMinecraft();

        if (mutedCategories.contains(SoundCategory.MASTER)) {
            // Back to the volume set in the game options (not forced to 100%).
            mc.getSoundHandler().setSoundLevel(SoundCategory.MASTER,
                    mc.gameSettings.getSoundLevel(SoundCategory.MASTER));
        }
        mutedCategories.clear();
        muted = false;
        MobMusic.LOGGER.info("Game sound restored");
    }
}
