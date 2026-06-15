package dev.nova.novadebug;

import dev.nova.novadebug.modules.SpawnerBeam;
import dev.nova.novadebug.modules.PlayerSignal;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class NovaDebugAddon extends MeteorAddon {
    public static final String NAME = "Nova Debug v2";
    public static final String AUTHOR = "Saint";
    public static final Category CATEGORY = new Category("Nova Debug");

    @Override
    public void onInitialize() {
        Modules.get().add(new SpawnerBeam());
        Modules.get().add(new PlayerSignal());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.nova.novadebug";
    }
}
