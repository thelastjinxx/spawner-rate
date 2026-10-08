package com.thelastjinxx.spawnerrate;

import net.fabricmc.api.ClientModInitializer;

public class SpawnerRateMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SpawnerRateCommand.register();
    }
}
