package dev.nova.novadebug;

import dev.nova.novadebug.modules.AutoRelog;
import dev.nova.novadebug.modules.PlayerSignal;
import dev.nova.novadebug.modules.RenderMeta;
import dev.nova.novadebug.modules.SpawnerBeam;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class NovaDebugAddon extends MeteorAddon {
    public static final String NAME = "Saint's Addon";
    public static final String AUTHOR = "Saint";
    public static final Category CATEGORY = new Category("Saint's Addon");

    @Override
    public void onInitialize() {
        Modules.get().add(new SpawnerBeam());
        Modules.get().add(new PlayerSignal());
        Modules.get().add(new AutoRelog());
        Modules.get().add(new RenderMeta());
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
