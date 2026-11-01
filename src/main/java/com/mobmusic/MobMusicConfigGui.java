package com.mobmusic;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.GuiConfig;

public class MobMusicConfigGui extends GuiConfig {

    public MobMusicConfigGui(GuiScreen parent) {
        super(parent,
                new ConfigElement(MobMusicConfig.config.getCategory(MobMusicConfig.CATEGORY))
                        .getChildElements(),
                MobMusic.MODID, false, false, "MobMusic");
    }
}
