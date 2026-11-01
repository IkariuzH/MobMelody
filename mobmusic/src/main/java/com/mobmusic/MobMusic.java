package com.mobmusic;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

/**
 * MobMusic: plays a song from your own YouTube Music playlist while a configured
 * mob (usually a boss) is nearby. The mod only tells a small browser extension what
 * to do; it never downloads or plays audio itself.
 */
@Mod(modid = MobMusic.MODID, name = MobMusic.NAME, version = MobMusic.VERSION,
        acceptedMinecraftVersions = "[1.12.2]", clientSideOnly = true,
        guiFactory = "com.mobmusic.MobMusicGuiFactory")
public class MobMusic {

    public static final String MODID = "mobmusic";
    public static final String NAME = "MobMusic";
    public static final String VERSION = "0.9.1";

    public static Logger LOGGER;

    @EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        LOGGER = e.getModLog();

        // Client only: this way a dedicated server never touches screen classes.
        if (e.getSide().isClient()) {
            MobMusicConfig.load(e.getSuggestedConfigurationFile());
            LocalServer.start(MobMusicConfig.PORT);
            MinecraftForge.EVENT_BUS.register(new MobTracker());
            MinecraftForge.EVENT_BUS.register(new GameMuter());
            MinecraftForge.EVENT_BUS.register(new MediaOverlay());
        }
    }
}
