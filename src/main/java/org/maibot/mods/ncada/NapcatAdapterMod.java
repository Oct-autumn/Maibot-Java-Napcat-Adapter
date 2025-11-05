package org.maibot.mods.ncada;

import org.maibot.sdk.ioc.AutoInject;
import org.maibot.sdk.mod.Mod;
import org.maibot.sdk.mod.ModMainClass;
import org.maibot.sdk.net.WsProcessors;
import org.maibot.sdk.net.WsRouter;

import java.util.List;

@ModMainClass(author = "Maibot Team", description = "A mod provides protocol support for QQ-Napcat.")
public class NapcatAdapterMod extends Mod {
    /// 平台名称
    public static final String PLATFORM_NAME = "qq";

    // This is a placeholder for the NapcatAdapterMod class.
    @AutoInject
    private NapcatAdapterMod(WsRouter wsRouter) {
        wsRouter.registerProcessor(new WsProcessors("/ws/napcat", List.of(NcProtocolDecoder.class)));
    }
}