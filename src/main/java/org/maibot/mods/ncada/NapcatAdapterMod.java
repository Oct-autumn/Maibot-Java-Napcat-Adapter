package org.maibot.mods.ncada;

import org.maibot.mods.ncada.decoder.NcProtocolDecoder;
import org.maibot.mods.ncada.encoder.NcProtocolEncoder;
import org.maibot.sdk.ioc.AutoInject;
import org.maibot.sdk.ioc.Value;
import org.maibot.sdk.mod.Mod;
import org.maibot.sdk.mod.ModMainClass;
import org.maibot.sdk.msg.SelfInfo;
import org.maibot.sdk.msg.SelfInfoRegistry;
import org.maibot.sdk.net.WsProcessors;
import org.maibot.sdk.net.WsRouter;

import java.util.List;

@ModMainClass(author = "Maibot Team", description = "A mod provides protocol support for QQ-Napcat.")
public class NapcatAdapterMod extends Mod {
    /// 平台名称
    public static final String PLATFORM_NAME = "qq";

    WsRouter wsRouter;
    /// Core 侧的平台自身信息注册表
    SelfInfoRegistry selfInfoRegistry;
    /// 适配器配置
    Config config;

    @AutoInject
    private NapcatAdapterMod(
      WsRouter wsRouter,
      SelfInfoRegistry selfInfoRegistry,
      @Value("${napcat-adapter:*}") Config config
    ) {
        this.wsRouter = wsRouter;
        this.selfInfoRegistry = selfInfoRegistry;
        this.config = config;
    }

    @Override
    public void onEnable() {
        // 注册该路径的处理管线：
        //  - NcProtocolDecoder：入站，把 OneBot 事件解码成 Core 的消息事件
        //  - NcProtocolEncoder：出站，把 Core 的通用消息实例编码成 OneBot 请求帧
        // WsProcessors 同时声明平台标识，Core 据此把出站消息路由到本连接
        wsRouter.registerProcessor(
          new WsProcessors(
            "/ws/napcat",
            List.of(NcProtocolDecoder.class, NcProtocolEncoder.class),
            PLATFORM_NAME
          )
        );

        // 注册自身账号信息：Core 用它给 Bot 自己发出的消息标注 assistant 角色
        selfInfoRegistry.register(
          PLATFORM_NAME,
          new SelfInfo(config.qqAccount(), config.nickname())
        );
    }
}
