// ============================================================
// File: src/main/java/dev/nova/novadebug/NovaDebugAddon.java
//
// This is the main entrypoint for the Nova Debug v2 addon.
// Meteor Client discovers this class via the "meteor" entrypoint
// declared in fabric.mod.json. Meteor calls onInitialize()
// automatically when the client loads.
//
// Registration flow:
//   1. Meteor reads fabric.mod.json entrypoints["meteor"]
//   2. Instantiates NovaDebugAddon and calls onInitialize()
//   3. We register our module category and all modules here.
// ============================================================

package dev.nova.novadebug;

import dev.nova.novadebug.modules.GoofyDebug;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

/**
 * Nova Debug v2 — Meteor Client Addon Entrypoint.
 *
 * <p>MeteorAddon is the base class all Meteor addons must extend.
 * Meteor's addon loader calls {@link #onInitialize()} once during
 * client startup, giving us a chance to register modules, commands,
 * HUD elements, etc.</p>
 */
public class NovaDebugAddon extends MeteorAddon {

    /** Display name shown in the Meteor UI for this addon's category. */
    public static final String NAME = "Nova Debug";

    /**
     * Called by Meteor during client initialization.
     * Register every module this addon provides here.
     */
    @Override
    public void onInitialize() {
        // Register our chunk-analysis module with Meteor's module registry.
        // Modules.get().add(...) makes the module appear in Meteor's GUI
        // under the category we specify inside the module itself.
        Modules.get().add(new GoofyDebug());
    }

    /**
     * The package that contains this addon's mixins (none in this addon).
     * Return null to indicate no mixin package is needed.
     */
    @Override
    public String getPackage() {
        return "dev.nova.novadebug";
    }
}
