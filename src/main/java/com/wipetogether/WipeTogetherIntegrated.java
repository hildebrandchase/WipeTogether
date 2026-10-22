package com.wipetogether;

public final class WipeTogetherIntegrated extends WipeTogether {
    @Override
    public void onEnable() {
        super.onEnable();
        if (isEnabled()) WipeTogetherTimerIntegrated.bootstrap(this);
    }

    @Override
    public void onDisable() {
        WipeTogetherTimerIntegrated.shutdown();
        super.onDisable();
    }
}
